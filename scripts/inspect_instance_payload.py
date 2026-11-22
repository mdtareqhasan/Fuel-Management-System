"""Prints the simulator's instance payload and the pause endpoint's contract."""

import json
import urllib.request

SIM = "http://localhost:8000"

with urllib.request.urlopen(f"{SIM}/v1/instance", timeout=60) as response:
    instance = json.loads(response.read().decode("utf-8"))

print("=" * 78)
print("INSTANCE PAYLOAD")
print("=" * 78)
print(json.dumps(instance, indent=2)[:2000])

print()
print("=" * 78)
print("PAUSE / TOGGLE CONTRACT")
print("=" * 78)
with urllib.request.urlopen(f"{SIM}/openapi.json", timeout=30) as response:
    spec = json.loads(response.read().decode("utf-8"))

for path in ["/admin/pause", "/admin/toggle", "/admin/step", "/admin/run"]:
    print(f"--- {path} ---")
    print(json.dumps(spec["paths"][path], indent=2)[:1200])
    print()
