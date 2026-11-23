"""Shows how the simulator's latency fault surfaces to an HTTP client.

The circuit breaker must distinguish a slow simulator from a dead one. To do
that, the shape of a latency failure has to be known — is it a read timeout, a
504, or something else?

This is a measurement, not a test.
"""

import json
import time
import urllib.error
import urllib.request

SIM = "http://localhost:8000"


def post(path, body=None, timeout=30):
    data = json.dumps(body if body is not None else {}).encode()
    request = urllib.request.Request(f"{SIM}{path}", data=data, method="POST")
    request.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return response.status, response.read().decode("utf-8")


print("=" * 78)
print("LATENCY FAULT — what does the client actually see?")
print("=" * 78)

post("/admin/faults/clear")
status, raw = post("/admin/faults", {
    "type": "latency",
    "duration_seconds": 8,
    "parameters": {"delay_ms": 3000},
})
print(f"fault injected -> HTTP {status}")

time.sleep(1)

started = time.monotonic()
try:
    request = urllib.request.Request(f"{SIM}/v1/instance")
    with urllib.request.urlopen(request, timeout=10) as response:
        elapsed = time.monotonic() - started
        print(f"HTTP {response.status} after {elapsed:.2f}s")
        print(f"  content-type: {response.headers.get('Content-Type')}")
        print(f"  stale header: {response.headers.get('X-Simulator-Stale')}")
        body = response.read().decode("utf-8")
        print(f"  body: {body[:160]}")
except urllib.error.HTTPError as error:
    elapsed = time.monotonic() - started
    print(f"HTTPError {error.code} after {elapsed:.2f}s")
    print(f"  body: {error.read().decode('utf-8')[:200]}")
except Exception as error:
    elapsed = time.monotonic() - started
    print(f"{type(error).__name__} after {elapsed:.2f}s: {error}")

print()
print("=" * 78)
print("UNAVAILABLE FAULT — what does the client actually see?")
print("=" * 78)

post("/admin/faults/clear")
status, raw = post("/admin/faults", {"type": "unavailable", "duration_seconds": 8})
print(f"fault injected -> HTTP {status}")

time.sleep(1)

started = time.monotonic()
try:
    request = urllib.request.Request(f"{SIM}/v1/instance")
    with urllib.request.urlopen(request, timeout=10) as response:
        print(f"HTTP {response.status} after {time.monotonic() - started:.2f}s")
except urllib.error.HTTPError as error:
    print(f"HTTPError {error.code} after {time.monotonic() - started:.2f}s")
    print(f"  body: {error.read().decode('utf-8')[:200]}")
except Exception as error:
    print(f"{type(error).__name__} after {time.monotonic() - started:.2f}s: {error}")

post("/admin/faults/clear")
print()
print("faults cleared")
