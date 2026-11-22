"""Measures the simulator's baseline response latency and its pause contract.

Two things matter for the Day 3 decision-path checks:

1. How long a simulator request takes with NO fault. The backend's read
   timeout is SIMULATOR_RESPONSE_TIMEOUT_MS=2000. If a clean request already
   takes longer than that, the backend is timing out on ordinary reads and
   every check that depends on a fresh world is measuring the timeout
   instead of the rule.

2. Whether the simulator can be PAUSED. A paused world does not move, so a
   proposal stays reviewable. In RUNNING mode the world advances ~5.46
   ticks/sec, so a proposal is stale before an operator can approve it.
"""

import json
import time
import urllib.request

SIM = "http://localhost:8000"


def get(url, timeout=60):
    started = time.monotonic()
    with urllib.request.urlopen(url, timeout=timeout) as response:
        response.read()
        return time.monotonic() - started


def post(url, body=None, timeout=60):
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(url, data=data, method="POST" if body is not None else "GET")
    if data is not None:
        request.add_header("Content-Type", "application/json")
    started = time.monotonic()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            response.read()
            return response.status, time.monotonic() - started
    except urllib.error.HTTPError as error:
        error.read()
        return error.code, time.monotonic() - started


print("=" * 78)
print("1. BASELINE LATENCY (faults cleared)")
print("=" * 78)
post(f"{SIM}/admin/faults/clear")
for path in ["/v1/instance", "/v1/allocations", "/v1/stations", "/v1/instance"]:
    print(f"  GET {path:<22} {get(f'{SIM}{path}'):.3f}s")

print()
print("=" * 78)
print("2. DOES THE TICK ADVANCE WHILE THE SIMULATOR IS IDLE?")
print("=" * 78)
_, instance, _ = (None, None, None)
for _ in range(4):
    body = json.loads(urllib.request.urlopen(f"{SIM}/v1/instance", timeout=60).read().decode())
    print(f"  tick={body.get('tick')}  mode={body.get('mode')}  speed={body.get('speed')}")
    time.sleep(1.0)

print()
print("=" * 78)
print("3. THE PAUSE ENDPOINT")
print("=" * 78)
with urllib.request.urlopen(f"{SIM}/openapi.json", timeout=30) as response:
    spec = json.loads(response.read().decode("utf-8"))

for path in ["/admin/pause", "/admin/toggle"]:
    for method, operation in spec["paths"][path].items():
        print(f"{method.upper()} {path} -> {operation.get('summary', '')}")
        body = operation.get("requestBody")
        if body:
            print(f"    body: {json.dumps(body)[:500]}")

print()
print("=" * 78)
print("4. PAUSE IT AND MEASURE AGAIN")
print("=" * 78)
status, elapsed = post(f"{SIM}/admin/pause")
print(f"  POST /admin/pause -> HTTP {status} in {elapsed:.3f}s")
body = json.loads(urllib.request.urlopen(f"{SIM}/v1/instance", timeout=60).read().decode())
print(f"  mode now: {body.get('mode')}  tick={body.get('tick')}")
for path in ["/v1/instance", "/v1/allocations"]:
    print(f"  GET {path:<22} {get(f'{SIM}{path}'):.3f}s")

print()
print("=" * 78)
print("5. WHILE PAUSED, DOES THE TICK MOVE?")
print("=" * 78)
for _ in range(4):
    body = json.loads(urllib.request.urlopen(f"{SIM}/v1/instance", timeout=60).read().decode())
    print(f"  tick={body.get('tick')}  mode={body.get('mode')}")
    time.sleep(1.0)
