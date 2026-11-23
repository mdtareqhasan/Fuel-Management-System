"""Step a deterministic combined demand/route crisis and capture decisions."""

import json
import sys
import time
import urllib.request

SIM = "http://localhost:8000"
BACKEND = "http://localhost:8080"


def post(path, body=None):
    data = json.dumps(body if body is not None else {}).encode()
    req = urllib.request.Request(SIM + path, data=data, method="POST",
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=20) as response:
        raw = response.read().decode()
        return json.loads(raw) if raw else None


def get(base, path):
    return json.loads(urllib.request.urlopen(base + path, timeout=20).read().decode())


def main():
    multiplier = float(sys.argv[1]) if len(sys.argv) > 1 else 1.8
    post("/admin/pause")
    post("/admin/faults/clear")
    initial = get(SIM, "/v1/instance")
    start = int(initial["tick"])
    print(f"warmup: PAUSED, starting_tick={start}, stepping to tick 30")
    for _ in range(max(0, 30 - start)):
        post("/admin/step")
    warmed = get(SIM, "/v1/instance")
    print(f"warmup complete: tick={warmed['tick']} mode={warmed['status']}")

    event_tick = int(warmed["tick"]) + 1
    demand = post("/admin/events", {
        "type": "demand_spike", "start_tick": event_tick, "duration_ticks": 12,
        "parameters": {"region_ids": ["region-dhaka"], "multiplier": multiplier}})
    route = post("/admin/events", {
        "type": "route_disruption", "start_tick": event_tick, "duration_ticks": 12,
        "parameters": {"route_id": "route-gazipur-mirpur"}})
    print(f"demand multiplier={multiplier}")
    print(f"injected demand={json.dumps(demand, sort_keys=True)}")
    print(f"injected route={json.dumps(route, sort_keys=True)}")

    for offset in range(1, 5):
        step = post("/admin/step")
        time.sleep(0.5)
        if offset < 4:
            print(f"step {offset}: tick={step.get('tick')}")

    instance = get(SIM, "/v1/instance")
    events = get(SIM, "/v1/events")
    routes = get(SIM, "/v1/routes")
    risk = get(BACKEND, "/api/risk")
    relevant_events = [e for e in events if e.get("id") in {demand.get("id"), route.get("id")}]
    mirpur = [p for p in risk.get("proposals", []) if p.get("station_id") == "station-mirpur"]
    print(f"observed tick={instance['tick']} mode={instance['status']}")
    print("active events=" + json.dumps(relevant_events, sort_keys=True))
    print("simulator route status=" + json.dumps([
        {"id": r.get("id"), "status": r.get("status")}
        for r in routes if r.get("id") in {"route-gazipur-mirpur", "route-patiya-mirpur"}], sort_keys=True))
    print("anomaly flags=" + json.dumps([
        {"station_id": f.get("station_id"), "fuel": f.get("fuel"),
         "cause": f.get("cause"), "ratio": f.get("ratio"), "severity": f.get("severity")}
        for f in (risk.get("anomaly_flags", []) or [])], sort_keys=True))
    print("mirpur plan=" + json.dumps([
        {"route_id": p.get("route_id"), "quantity_liters": p.get("quantity_liters"),
         "alternatives": p.get("alternatives", [])}
        for p in mirpur], sort_keys=True))
    print("remaining unmet needs=" + json.dumps(risk.get("unmet_needs", []), sort_keys=True)[:3000])
    post("/admin/pause")
    print("cleanup: simulation paused; no reset performed")


if __name__ == "__main__":
    main()
