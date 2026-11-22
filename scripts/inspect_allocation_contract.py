"""Print the simulator's allocation-creation contract.

Day 3 needs to know exactly what POST /v1/allocations accepts, what it returns and
what the idempotency behaviour is. This reads the live OpenAPI document rather
than assuming, because the guide's wording and the running simulator have already
been shown to differ once.
"""

import json
import urllib.request

SIM = "http://localhost:8000"

with urllib.request.urlopen(f"{SIM}/openapi.json", timeout=30) as response:
    spec = json.loads(response.read().decode("utf-8"))

print("ALLOCATION PATHS")
for path, operations in spec["paths"].items():
    if "allocation" in path:
        for method, operation in operations.items():
            body = operation.get("requestBody", {})
            ref = body.get("content", {}).get("application/json", {}).get("schema", {})
            print(f"  {method.upper():<6} {path:<32} schema={ref}")
            print(f"         summary: {operation.get('summary', '')}")

print()
print("ALLOCATION SCHEMAS")
for name, schema in spec["components"]["schemas"].items():
    if "allocation" in name.lower():
        print(f"  {name}:")
        print(json.dumps(schema, indent=2)[:2200])
        print()

print("ALLOCATION RESPONSE CODES")
for path, operations in spec["paths"].items():
    if "allocation" in path and "post" in operations:
        for code, response in operations["post"].get("responses", {}).items():
            print(f"  POST {path} -> {code}: {response.get('description', '')}")
