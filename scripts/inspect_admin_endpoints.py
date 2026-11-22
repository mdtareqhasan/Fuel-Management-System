"""Prints the admin endpoints the simulator exposes for the test driver.

Day 3 needs to inject a crisis so there is something to decide. This reads the
live OpenAPI document rather than guessing the event payload.
"""

import json
import urllib.request

SIM = "http://localhost:8000"

with urllib.request.urlopen(f"{SIM}/openapi.json", timeout=30) as response:
    spec = json.loads(response.read().decode("utf-8"))

for path, operations in spec["paths"].items():
    if not path.startswith("/admin"):
        continue
    for method, operation in operations.items():
        body = operation.get("requestBody", {})
        schema = body.get("content", {}).get("application/json", {}).get("schema", {})
        print(f"{method.upper():<6} {path:<28} schema={schema}")
        print(f"       summary: {operation.get('summary', '')}")
        print(f"       params : {[p.get('name') for p in operation.get('parameters', [])]}")

print()
print("SCHEMAS")
for name, schema in spec["components"]["schemas"].items():
    if "event" in name.lower() or "fault" in name.lower():
        print(f"  {name}:")
        print(json.dumps(schema, indent=2)[:1800])
        print()
