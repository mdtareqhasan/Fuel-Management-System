"""Prints the circuit breaker metrics and the simulator call outcomes.

The breaker stayed CLOSED during the unavailable fault, so this shows exactly
which calls were made and how they were counted. The question it answers is:
did the failing calls reach the breaker at all?
"""

import json
import urllib.request

BACKEND = "http://localhost:8080"

with urllib.request.urlopen(f"{BACKEND}/actuator/prometheus", timeout=30) as response:
    metrics = response.read().decode("utf-8")

print("=" * 78)
print("CIRCUIT BREAKER METRICS")
print("=" * 78)
for line in metrics.splitlines():
    if line.startswith("fuel_circuit_breaker"):
        print(f"  {line}")

print()
print("=" * 78)
print("SIMULATOR CALL OUTCOMES")
print("=" * 78)
for line in metrics.splitlines():
    if line.startswith("fuel_simulator_calls_total"):
        print(f"  {line}")

print()
print("=" * 78)
print("REFRESH FAILURES")
print("=" * 78)
for line in metrics.splitlines():
    if line.startswith("fuel_refresh_failures_total") or line.startswith("fuel_refresh_success_total"):
        print(f"  {line}")

print()
print("=" * 78)
print("SIMULATOR CALL LATENCY BY OUTCOME")
print("=" * 78)
for line in metrics.splitlines():
    if line.startswith("fuel_simulator_call_seconds_count"):
        print(f"  {line}")
