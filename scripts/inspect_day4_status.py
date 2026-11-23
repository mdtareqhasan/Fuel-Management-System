"""Prints the platform's system status and the Day 4 metrics.

Used to confirm that the resilience fields and the new Prometheus series are
actually live, rather than only present in the source.
"""

import json
import urllib.request

BACKEND = "http://localhost:8080"

with urllib.request.urlopen(f"{BACKEND}/api/system-status", timeout=30) as response:
    status = json.loads(response.read().decode("utf-8"))

print("=" * 78)
print("SYSTEM STATUS")
print("=" * 78)
for key, value in status.items():
    print(f"  {key:<26} {value}")

print()
print("=" * 78)
print("DAY 4 METRICS")
print("=" * 78)
with urllib.request.urlopen(f"{BACKEND}/actuator/prometheus", timeout=30) as response:
    metrics = response.read().decode("utf-8")

prefixes = (
    "fuel_circuit_breaker",
    "fuel_stream",
    "fuel_approvals_",
    "fuel_approval_seconds",
    "fuel_refresh_",
)
found = [line for line in metrics.splitlines()
         if line.startswith(prefixes) and not line.startswith("#")]
for line in sorted(found):
    print(f"  {line}")

if not found:
    print("  (none of the Day 4 metrics are registered yet)")
