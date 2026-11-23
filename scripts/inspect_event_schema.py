"""Prints the simulator's EventCreate and FaultCreate schemas.

Day 4 injects crisis events (demand spike, route disruption, shipment delay)
through the admin console, so the event contract has to be read rather than
guessed.
"""

import json
import urllib.request

SIM = "http://localhost:8000"

with urllib.request.urlopen(f"{SIM}/openapi.json", timeout=30) as response:
    spec = json.loads(response.read().decode("utf-8"))

for name in ["EventCreate", "FaultCreate", "Event"]:
    schema = spec["components"]["schemas"].get(name)
    if schema:
        print("=" * 78)
        print(name)
        print("=" * 78)
        print(json.dumps(schema, indent=2))
        print()
