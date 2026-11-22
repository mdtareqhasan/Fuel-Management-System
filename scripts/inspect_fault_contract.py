"""Prints the fault types the simulator can inject.

Day 3 has to prove that a lost response is reconciled without a duplicate
shipment. The way to lose a response is to inject the fault that drops it, so
this reads the fault contract from the live simulator.
"""

import json
import urllib.request

SIM = "http://localhost:8000"

with urllib.request.urlopen(f"{SIM}/openapi.json", timeout=30) as response:
    spec = json.loads(response.read().decode("utf-8"))

schema = spec["components"]["schemas"].get("FaultCreate")
print("FaultCreate:")
print(json.dumps(schema, indent=2))
print()

_, faults, _ = None, None, None
try:
    with urllib.request.urlopen(f"{SIM}/admin/faults", timeout=30) as response:
        faults = json.loads(response.read().decode("utf-8"))
    print("currently injected faults:")
    print(json.dumps(faults, indent=2)[:1500])
except Exception as error:
    print(f"could not read the current faults: {error}")
