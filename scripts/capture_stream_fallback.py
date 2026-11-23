"""Measure SSE disconnection while confirming the REST poll keeps refreshing."""

import json
import time
import urllib.request

SIM = "http://localhost:8000"
BACKEND = "http://localhost:8080"


def get(url):
    return json.loads(urllib.request.urlopen(url, timeout=15).read().decode())


def post(url, body=None):
    data = json.dumps(body if body is not None else {}).encode()
    req = urllib.request.Request(url, data=data, method="POST",
        headers={"Content-Type": "application/json"})
    raw = urllib.request.urlopen(req, timeout=15).read().decode()
    return json.loads(raw) if raw else {}


def read_metrics():
    raw = urllib.request.urlopen(BACKEND + "/actuator/prometheus", timeout=15).read().decode()
    selected = {}
    for line in raw.splitlines():
        if line.startswith("fuel_refresh_triggers_total") or line.startswith("fuel_stream_"):
            key, value = line.rsplit(" ", 1)
            selected[key] = float(value)
    return selected


def capture(label):
    status = get(BACKEND + "/api/system-status")
    metrics = read_metrics()
    print(f"{label}: tick={status.get('tick')} mode={status.get('simulation_mode')} stream={status.get('event_stream')} events={status.get('stream_events')} reconnects={status.get('stream_reconnects')} quality={status.get('data_quality')}")
    print("  metrics=" + json.dumps(metrics, sort_keys=True))
    return status, metrics


post(SIM + "/admin/pause")
post(SIM + "/admin/faults/clear")
time.sleep(3)
_, before_metrics = capture("before stream fault")
fault = post(SIM + "/admin/faults", {
    "type": "stream_disconnect", "duration_seconds": 20, "parameters": {}})
print("fault=" + json.dumps(fault, sort_keys=True))
time.sleep(8)
_, during_metrics = capture("during stream fault")
post(SIM + "/admin/faults/clear")
print("fault cleared; waiting 20 seconds for stream reconnect")
time.sleep(20)
_, after_metrics = capture("after stream recovery")

poll_key = 'fuel_refresh_triggers_total{trigger="poll"}'
event_key = 'fuel_refresh_triggers_total{trigger="event"}'
print("fault_window_poll_trigger_delta=" + str(
    during_metrics.get(poll_key, 0.0) - before_metrics.get(poll_key, 0.0)))
print("fault_window_event_trigger_delta=" + str(
    during_metrics.get(event_key, 0.0) - before_metrics.get(event_key, 0.0)))
print("recovery_poll_trigger_delta=" + str(
    after_metrics.get(poll_key, 0.0) - during_metrics.get(poll_key, 0.0)))

