"""Lists the simulator's admin endpoints from its OpenAPI spec.

Used during verification to find the exact fault-injection contract instead of
guessing the request body.
"""

import json
import sys


def main(path: str) -> int:
    with open(path, encoding="utf-8") as handle:
        spec = json.load(handle)

    for route, operations in sorted(spec.get("paths", {}).items()):
        for method, operation in operations.items():
            summary = operation.get("summary", "")
            print(f"{method.upper():6} {route:34} {summary}")

    print()
    print("--- schemas with fault / event shapes ---")
    for name, schema in sorted(spec.get("components", {}).get("schemas", {}).items()):
        lowered = name.lower()
        if "fault" in lowered or "event" in lowered or "inject" in lowered:
            print(f"\n{name}:")
            print(json.dumps(schema, indent=2)[:1800])

    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "openapi.json"))
