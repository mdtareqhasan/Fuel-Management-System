"""Prints the JSON keys the backend actually emits for one collection.

Used to catch a mismatch between the simulator's field names and the DTO
mappings, which shows up as a silently null field.
"""

import json
import sys


def main(path: str) -> int:
    with open(path, encoding="utf-8") as handle:
        payload = json.load(handle)

    for collection in ("regions", "depots", "stations", "routes", "supplyArrivals", "events", "allocations"):
        rows = payload.get(collection) or []
        if not rows:
            print(f"{collection:16} (empty)")
            continue
        print(f"{collection:16} {sorted(rows[0].keys())}")
        print(f"{'':16} sample: {json.dumps(rows[0], ensure_ascii=False)}")
        print()

    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else "dashboard_probe.json"))
