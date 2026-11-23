"""Measure refresh and simulator-call volume during a short RUNNING window."""

import json
import time
import urllib.request

SIM = "http://localhost:8000"
BACKEND = "http://localhost:8080"


def get(url):
    return urllib.request.urlopen(url, timeout=20).read().decode()


def post(path):
    req = urllib.request.Request(SIM + path, data=b"{}", method="POST",
        headers={"Content-Type": "application/json"})
    return urllib.request.urlopen(req, timeout=20).read().decode()


def metrics():
    lines = get(BACKEND + "/actuator/prometheus").splitlines()
    result = {}
    for line in lines:
        if (line.startswith("fuel_refresh_triggers_total")
                or line.startswith("fuel_refresh_success_total")
                or line.startswith("fuel_refresh_events_coalesced_total")):
            key, value = line.rsplit(" ", 1)
            result[key] = float(value)
        elif line.startswith("fuel_simulator_call_seconds_count"):
            key, value = line.rsplit(" ", 1)
            result[key] = float(value)
    return result


def total_calls(values):
    return sum(v for k, v in values.items() if k.startswith("fuel_simulator_call_seconds_count"))


post("/admin/faults/clear")
post("/admin/pause")
ready_started = time.monotonic()
for _ in range(60):
    try:
        get(BACKEND + "/actuator/health/readiness")
        break
    except Exception:
        time.sleep(1)
else:
    raise RuntimeError("backend readiness did not recover")
print(f"backend_ready_after_restart_seconds={time.monotonic()-ready_started:.2f}")
time.sleep(2)
before = json.loads(get(BACKEND + "/api/system-status"))
before_metrics = metrics()
start_tick = before.get("tick")
started = time.monotonic()
post("/admin/run")
time.sleep(12)
post("/admin/pause")
elapsed = time.monotonic() - started
time.sleep(3)
after = json.loads(get(BACKEND + "/api/system-status"))
after_metrics = metrics()
print(f"RUNNING window seconds={elapsed:.2f} ticks={start_tick}->{after.get('tick')} stream_events={before.get('stream_events')}->{after.get('stream_events')}")
for metric in sorted(set(before_metrics) | set(after_metrics)):
    print(f"  {metric}: {before_metrics.get(metric, 0.0)} -> {after_metrics.get(metric, 0.0)} (delta {after_metrics.get(metric, 0.0)-before_metrics.get(metric, 0.0)})")
print(f"simulator_call_count_total: {total_calls(before_metrics)} -> {total_calls(after_metrics)} (delta {total_calls(after_metrics)-total_calls(before_metrics)})")
print(f"final: mode={after.get('simulation_mode')} quality={after.get('data_quality')} eligible={after.get('decision_eligible')} stream={after.get('event_stream')}")
