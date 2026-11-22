"""Prints every simulator endpoint and what it says about faults and latency.

The lost-response check needs a POST to /v1/allocations to be delayed past the
application's read timeout. This prints the paths so the question "does the
latency fault apply to writes as well as reads?" can be answered from the
simulator's own contract rather than by guessing.
"""

import json
import urllib.request

SIM = "http://localhost:8000"

with urllib.request.urlopen(f"{SIM}/openapi.json", timeout=30) as response:
    spec = json.loads(response.read().decode("utf-8"))

print("=" * 78)
print("ALL ENDPOINTS")
print("=" * 78)
for path, operations in spec["paths"].items():
    for method, operation in operations.items():
        print(f"{method.upper():<6} {path:<34} {operation.get('summary', '')}")

print()
print("=" * 78)
print("DESCRIPTION TEXT MENTIONING FAULTS OR LATENCY")
print("=" * 78)
for path, operations in spec["paths"].items():
    for method, operation in operations.items():
        text = json.dumps(operation)
        if "latency" in text.lower() or "delay_ms" in text.lower():
            print(f"--- {method.upper()} {path} ---")
            print(operation.get("description", operation.get("summary", "")))
            print()
