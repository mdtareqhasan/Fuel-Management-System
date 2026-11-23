"""Measure forecast fallback during a simulator demand-history failure."""

import json
import time
import urllib.request

SIM = "http://localhost:8000"
BACKEND = "http://localhost:8080"
PROM = "http://localhost:9090"


def request(url, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data,
        method="POST" if body is not None else "GET",
        headers={"Content-Type": "application/json"} if body is not None else {})
    with urllib.request.urlopen(req, timeout=20) as response:
        raw = response.read().decode()
        return json.loads(raw) if raw else None


def metric():
    raw = urllib.request.urlopen(BACKEND + "/actuator/prometheus", timeout=20).read().decode()
    return [line for line in raw.splitlines()
            if line.startswith("fuel_forecast_fallback_total")]


def snapshot(label):
    status = request(BACKEND + "/api/system-status")
    risk = request(BACKEND + "/api/risk")
    methods = {}
    for forecast in risk.get("forecasts", {}).values():
        method = forecast.get("method", "unknown")
        methods[method] = methods.get(method, 0) + 1
    print(f"{label}: quality={status.get('data_quality')} eligible={status.get('decision_eligible')} breaker={status.get('circuit_breaker')}")
    print(f"  forecast_methods={json.dumps(methods, sort_keys=True)}")
    print(f"  fallback_metric={metric()}")


print("FORECAST FALLBACK fault: error_rate rate=1.0, duration=25s")
request(SIM + "/admin/faults/clear", {})
snapshot("before")
fault = request(SIM + "/admin/faults", {
    "type": "error_rate", "duration_seconds": 25,
    "parameters": {"rate": 1.0}})
print(f"fault injected: {json.dumps(fault, sort_keys=True)}")
time.sleep(7)
snapshot("during")
request(SIM + "/admin/faults/clear", {})
print("fault cleared; waiting 35s for the breaker recovery window")
time.sleep(35)
snapshot("after recovery")
