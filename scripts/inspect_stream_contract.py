"""Prints the simulator's SSE stream contract and the admin event contract.

Day 4 needs an SSE reader with coalesced refresh and a REST polling fallback.
Before writing one, the stream's actual shape has to be known: what events it
emits, what the payload looks like, and whether it holds the connection open.
"""

import json
import urllib.request

SIM = "http://localhost:8000"

with urllib.request.urlopen(f"{SIM}/openapi.json", timeout=30) as response:
    spec = json.loads(response.read().decode("utf-8"))

print("=" * 78)
print("/v1/stream CONTRACT")
print("=" * 78)
print(json.dumps(spec["paths"].get("/v1/stream"), indent=2)[:1500])

print()
print("=" * 78)
print("/admin/events CONTRACT")
print("=" * 78)
print(json.dumps(spec["paths"].get("/admin/events"), indent=2)[:1500])

print()
print("=" * 78)
print("LIVE STREAM — first 20 seconds of raw bytes")
print("=" * 78)
request = urllib.request.Request(f"{SIM}/v1/stream", method="GET")
try:
    with urllib.request.urlopen(request, timeout=20) as response:
        print(f"status={response.status}")
        print(f"content-type={response.headers.get('Content-Type')}")
        for _ in range(20):
            line = response.readline()
            if not line:
                break
            print(f"  {line.decode('utf-8', 'replace').rstrip()}")
except Exception as error:  # noqa: BLE001 - this is the measurement
    print(f"stream read ended: {type(error).__name__}: {error}")

print()
print("=" * 78)
print("CURRENT EVENTS")
print("=" * 78)
try:
    with urllib.request.urlopen(f"{SIM}/v1/events", timeout=30) as response:
        events = json.loads(response.read().decode("utf-8"))
    print(json.dumps(events[:4], indent=2)[:1500])
except Exception as error:  # noqa: BLE001 - this is the measurement
    print(f"events read failed: {type(error).__name__}: {error}")
