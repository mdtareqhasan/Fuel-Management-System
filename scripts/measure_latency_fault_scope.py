"""Measures whether the simulator's latency fault delays a WRITE.

The Day 3 lost-response check needs POST /v1/allocations to take longer than
the application's read timeout (2 s). If the latency fault only delays reads,
the approval succeeds normally and the check measures nothing.

This injects the fault and times a GET and a POST against the same fault, so
the answer comes from the simulator's behaviour rather than from a guess.
"""

import json
import time
import urllib.request

SIM = "http://localhost:8000"
BACKEND = "http://localhost:8080"


def call(url, body=None, timeout=30):
    """Calls an endpoint and returns (status, seconds taken)."""
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(url, data=data, method="POST" if body is not None else "GET")
    if data is not None:
        request.add_header("Content-Type", "application/json")
    started = time.monotonic()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            response.read()
            return response.status, time.monotonic() - started
    except Exception as error:  # noqa: BLE001 - this is the measurement
        return type(error).__name__, time.monotonic() - started


print("=" * 78)
print("BASELINE — no fault")
print("=" * 78)
print(f"GET  /v1/instance  -> {call(f'{SIM}/v1/instance')}")
print(f"GET  /v1/allocations -> {call(f'{SIM}/v1/allocations')}")

print()
print("=" * 78)
print("INJECTING A LATENCY FAULT (delay_ms=4000, 30 s)")
print("=" * 78)
status, elapsed = call(f"{SIM}/admin/faults", {"type": "latency", "duration_seconds": 30,
                                                "parameters": {"delay_ms": 4000}})
print(f"POST /admin/faults -> HTTP {status} in {elapsed:.2f}s")

print()
print("=" * 78)
print("UNDER THE FAULT")
print("=" * 78)
status, elapsed = call(f"{SIM}/v1/instance")
print(f"GET  /v1/instance    -> {status} in {elapsed:.2f}s")
status, elapsed = call(f"{SIM}/v1/allocations")
print(f"GET  /v1/allocations -> {status} in {elapsed:.2f}s")

# A POST that is expected to be refused by the domain, but only after the
# latency has been applied. The point is the TIMING, not the status.
status, elapsed = call(f"{SIM}/v1/allocations", {
    "idempotency_key": "latency-probe-not-a-real-shipment",
    "source_depot_id": "depot-gazipur",
    "destination_station_id": "station-mirpur",
    "route_id": "route-gazipur-mirpur",
    "fuel_type": "DIESEL",
    "quantity": 1,
})
print(f"POST /v1/allocations -> {status} in {elapsed:.2f}s   <-- does a WRITE get delayed?")

print()
print("=" * 78)
print("CLEARING")
print("=" * 78)
status, elapsed = call(f"{SIM}/admin/faults/clear")
print(f"POST /admin/faults/clear -> HTTP {status} in {elapsed:.2f}s")

print()
print("The application's read timeout is SIMULATOR_RESPONSE_TIMEOUT_MS=2000.")
print("A write that takes longer than 2 s is the lost response the Day 3")
print("check needs. A write that returns quickly means the latency fault")
print("does not apply to writes, and the check has to use another fault.")
