"""Capture a compact status/run-id/metrics handoff for the latest incident."""

import json
import urllib.request

BACKEND = "http://localhost:8080"


def get(path):
    return json.loads(urllib.request.urlopen(BACKEND + path, timeout=20).read().decode())


status = get("/api/system-status")
risk = get("/api/risk")
metrics = urllib.request.urlopen(BACKEND + "/actuator/prometheus", timeout=20).read().decode()
print("INCIDENT HANDOFF")
print(f"run_id={risk.get('metadata', {}).get('run_id')}")
print(f"snapshot_id={risk.get('metadata', {}).get('snapshot_id')}")
print(f"tick={risk.get('tick')} quality={risk.get('metadata', {}).get('quality')} eligible={risk.get('metadata', {}).get('decision_eligible')}")
print("system_status=" + json.dumps(status, sort_keys=True))
print("metrics:")
for line in metrics.splitlines():
    if line.startswith(("fuel_circuit_breaker_opened_total",
                        "fuel_circuit_breaker_rejections_total",
                        "fuel_refresh_events_coalesced_total",
                        "fuel_refresh_triggers_total",
                        "fuel_stream_connected",
                        "fuel_stream_reconnects_total")):
        print("  " + line)
