"""Print the simulator's admin event schema, so injections use the right payload."""

import json
import urllib.request

SIM = "http://localhost:8000"

with urllib.request.urlopen(f"{SIM}/openapi.json", timeout=30) as response:
    spec = json.loads(response.read().decode("utf-8"))

print("ADMIN PATHS")
for path, operations in spec["paths"].items():
    if "admin" in path:
        for method, operation in operations.items():
            if method in ("post", "put"):
                body = operation.get("requestBody", {})
                ref = body.get("content", {}).get("application/json", {}).get("schema", {})
                print(f"  {method.upper():<5} {path:<28} schema={ref}")

print()
print("EVENT SCHEMAS")
for name, schema in spec["components"]["schemas"].items():
    if "event" in name.lower():
        print(f"  {name}: {json.dumps(schema)[:600]}")

print()
print("EVENT TYPE ENUM")
for name, schema in spec["components"]["schemas"].items():
    props = schema.get("properties", {})
    if "type" in props and "enum" in props["type"]:
        print(f"  {name}.type = {props['type']['enum']}")
