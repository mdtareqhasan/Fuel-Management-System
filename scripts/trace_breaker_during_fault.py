"""Traces what the circuit breaker actually receives during a fault.

The unavailable fault ran, the snapshot went INVALID, and the approval was
blocked — but the breaker never opened. This script injects the fault and
watches the breaker metrics second by second, so the reason is visible rather
than guessed.

The question it answers: do the failing calls reach the breaker at all?
"""

import json
import time
import urllib.request

SIM = "http://localhost:8000"
BACKEND = "http://localhost:8080"


def get_text(url, timeout=20):
    try:
        with urllib.request.urlopen(url, timeout=timeout) as response:
            return response.read().decode("utf-8")
    except Exception as error:  # noqa: BLE001
        return f"ERROR {type(error).__name__}: {error}"


def post(url, body=None, timeout=20):
    data = json.dumps(body if body is not None else {}).encode()
    request = urllib.request.Request(url, data=data, method="POST")
    request.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, response.read().decode("utf-8")
    except Exception as error:  # noqa: BLE001
        return 0, f"{type(error).__name__}: {error}"


def metric_values(metrics, prefix):
    """Every sample line for a metric prefix, as (labels, value)."""
    found = []
    for line in metrics.splitlines():
        if line.startswith(prefix) and not line.startswith("#"):
            name, _, value = line.rpartition(" ")
            found.append((name, value))
    return found


def snapshot(label):
    metrics = get_text(f"{BACKEND}/actuator/prometheus")
    print(f"  --- {label} ---")
    for prefix in (
        "fuel_circuit_breaker_opened_total",
        "fuel_circuit_breaker_rejections_total",
        "fuel_circuit_breaker_open",
        "fuel_simulator_call_seconds_count",
        "fuel_refresh_failures_total",
        "fuel_refresh_success_total",
    ):
        for name, value in metric_values(metrics, prefix):
            print(f"    {name} = {value}")


print("=" * 78)
print("BASELINE")
print("=" * 78)
snapshot("before the fault")

print()
print("=" * 78)
print("INJECTING A 20 SECOND UNAVAILABLE FAULT")
print("=" * 78)
status, raw = post(f"{SIM}/admin/faults", {"type": "unavailable", "duration_seconds": 20})
print(f"fault injection -> HTTP {status}")

for second in range(1, 13):
    time.sleep(2)
    status_text = get_text(f"{BACKEND}/api/system-status")
    try:
        parsed = json.loads(status_text)
        brief = (f"quality={parsed.get('data_quality')} "
                 f"breaker={parsed.get('circuit_breaker')} "
                 f"simulator={parsed.get('simulator')}")
    except json.JSONDecodeError:
        brief = f"status endpoint: {status_text[:120]}"
    print(f"  t+{second * 2:>2}s  {brief}")

print()
snapshot("during the fault")

print()
print("=" * 78)
print("CLEARING")
print("=" * 78)
post(f"{SIM}/admin/faults/clear")
time.sleep(5)
snapshot("after the fault")
