"""Capture a non-destructive Day 4 crisis event timeline from the local stack.

Unlike inject_fault.py's original event demonstration, this script never calls
/admin/reset, so it preserves simulator history and allocations. The selected
event expires naturally after the requested tick window.
"""

import json
import sys
import time
import urllib.error
import urllib.request

SIM = "http://localhost:8000"
BACKEND = "http://localhost:8080"


def call(base, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(
        base + path, data=data, method="POST" if body is not None else "GET",
        headers={"Content-Type": "application/json"} if body is not None else {})
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            raw = response.read().decode()
            return response.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode()


def main():
    if len(sys.argv) < 2:
        raise SystemExit("usage: python scripts/capture_day4_event.py demand_spike|route_disruption|shipment_delay [ticks]")
    kind = sys.argv[1]
    default_ticks = 12 if kind == "demand_spike" else 8
    ticks = int(sys.argv[2]) if len(sys.argv) > 2 else default_ticks
    if kind not in {"demand_spike", "route_disruption", "shipment_delay"}:
        raise SystemExit("supported events: demand_spike, route_disruption, shipment_delay")
    parameters = ({"region_ids": ["region-dhaka"], "multiplier": 1.8}
                  if kind == "demand_spike" else
                  {"route_id": "route-gazipur-mirpur"}
                  if kind == "route_disruption" else {"delay_ticks": 4})

    call(SIM, "/admin/pause", {})
    call(SIM, "/admin/faults/clear", {})
    _, instance = call(SIM, "/v1/instance")
    _, before_status = call(BACKEND, "/api/system-status")
    _, before_risk = call(BACKEND, "/api/risk")
    _, before_dashboard = call(BACKEND, "/api/dashboard")
    start_tick = int(instance["tick"]) + 1
    print(f"EVENT: {kind}; duration_ticks={ticks}; parameters={json.dumps(parameters, sort_keys=True)}")
    print(f"before: tick={instance['tick']} mode={instance.get('status')} quality={before_status.get('data_quality')}")
    print(f"before risk: station_risks={len(before_risk.get('station_risks', []))} depot_risks={len(before_risk.get('depot_risks', []))}")

    status, event = call(SIM, "/admin/events", {
        "type": kind, "start_tick": start_tick, "duration_ticks": ticks,
        "parameters": parameters})
    print(f"event injection -> HTTP {status}: {json.dumps(event, sort_keys=True)}")
    if status not in (200, 201):
        raise SystemExit("event injection failed")

    def capture(label):
        _, current = call(SIM, "/v1/instance")
        _, routes = call(SIM, "/v1/routes")
        _, arrivals = call(SIM, "/v1/supply-arrivals")
        _, risk = call(BACKEND, "/api/risk")
        _, dashboard = call(BACKEND, "/api/dashboard")
        events = [e for e in dashboard.get("events", [])
                  if e.get("id") == (event or {}).get("id")]
        print(f"{label}: tick={current.get('tick')} event={json.dumps(events, sort_keys=True)}")
        print("  routes=" + json.dumps([
            {"id": r.get("id"), "status": r.get("status")}
            for r in (routes or [])], sort_keys=True))
        print("  supply_arrivals=" + json.dumps([
            {"id": a.get("id"), "planned_tick": a.get("planned_tick"),
             "actual_tick": a.get("actual_tick"), "status": a.get("status")}
            for a in (arrivals or [])[-5:]], sort_keys=True))
        print(f"  risk: detector_status={risk.get('detector_status')} anomaly_flags={len(risk.get('anomaly_flags', []))} unmet_needs={len(risk.get('unmet_needs', []))}")
        if kind == "route_disruption":
            affected = [p for p in (risk.get("proposals", []) or [])
                        if p.get("station_id") == "station-mirpur"]
            print("  mirpur_plan=" + json.dumps([
                {"route_id": p.get("route_id"), "alternatives": p.get("alternatives", [])}
                for p in affected], sort_keys=True))
        if kind == "demand_spike":
            print("  anomaly_details=" + json.dumps([
                {"station_id": f.get("station_id"), "fuel": f.get("fuel"),
                 "cause": f.get("cause"), "ratio": f.get("ratio"),
                 "severity": f.get("severity")}
                for f in (risk.get("anomaly_flags", []) or [])], sort_keys=True))
        if kind == "shipment_delay":
            print("  depot_windows=" + json.dumps([
                {"depot_id": d.get("depot_id"), "fuel": d.get("fuel"),
                 "shortfall_tick": d.get("shortfall_tick"),
                 "shortfall_hours": d.get("shortfall_hours"),
                 "supply_changes": d.get("supply_changes", [])}
                for d in (risk.get("depot_risks", []) or [])], sort_keys=True)[:3000])
        for need in (risk.get("unmet_needs", []) or [])[:8]:
            print(f"    unmet: station={need.get('station_id')} fuel={need.get('fuel')} liters={need.get('need_liters')} reason={need.get('reason_code')}")
        return int(current.get("tick", 0))

    # PAUSED + one-step captures the event while active, without a RUNNING
    # simulation racing through the entire window between HTTP requests.
    final_tick = int(instance["tick"])
    for offset in range(1, ticks + 3):
        step_status, step = call(SIM, "/admin/step", {})
        if step_status != 200:
            print(f"step failed at offset={offset}: HTTP {step_status} {step}")
            break
        final_tick = int(step.get("tick", final_tick))
        if offset in (1, 2, 4) or offset == ticks + 2:
            capture(f"step {offset} (tick {final_tick})")

    call(SIM, "/admin/pause", {})

    _, status_after = call(BACKEND, "/api/system-status")
    print(f"after: tick={final_tick} mode=PAUSED quality={status_after.get('data_quality')} eligible={status_after.get('decision_eligible')}")
    capture("after event window")
    print("cleanup: simulation paused; no reset performed; event expired by stepped tick window")


if __name__ == "__main__":
    main()
