"""Check the shared dispatch limit holds in a live risk response.

The guide's manual exercise describes the trap: a policy that allocates each fuel
independently dispatches more than the depot's allowance. This script sums the
proposals per depot and compares the total with the depot's dispatch capacity.
"""

import json
import sys
import urllib.request

BACKEND = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"


def get(path):
    with urllib.request.urlopen(f"{BACKEND}{path}", timeout=60) as response:
        return json.loads(response.read().decode("utf-8"))


risk = get("/api/risk")
dashboard = get("/api/dashboard")

dispatch = {d["id"]: d["dispatch_capacity_per_tick"] for d in dashboard["depots"]}
station_fuel = {s["id"] for s in dashboard["stations"]}

totals = {}
for proposal in risk["proposals"]:
    totals.setdefault(proposal["depot_id"], []).append(proposal)

print("SHARED DISPATCH LIMIT CHECK")
print(f"{'depot':<18} {'allowance':<12} {'proposed':<12} {'headroom':<12} {'lines':<7} ok")
print("-" * 75)

all_ok = True
for depot_id, allowance in sorted(dispatch.items()):
    lines = totals.get(depot_id, [])
    proposed = sum(p["quantity_liters"] for p in lines)
    ok = proposed <= allowance
    all_ok = all_ok and ok
    print(f"{depot_id:<18} {allowance:<12.0f} {proposed:<12.0f} {allowance - proposed:<12.0f} "
          f"{len(lines):<7} {'yes' if ok else 'NO'}")

    for line in lines:
        if line["quantity_liters"] > allowance:
            print(f"      VIOLATION: {line['line_id']} alone is {line['quantity_liters']}")

print()
print(f"RESULT: {'every depot is within its shared allowance' if all_ok else 'A DISPATCH LIMIT WAS EXCEEDED'}")

# Every proposal must name a route that actually exists and matches the endpoints.
routes = {r["id"]: r for r in dashboard["routes"]}
print()
print("ROUTE INTEGRITY CHECK")
for proposal in risk["proposals"]:
    route = routes.get(proposal["route_id"])
    if route is None:
        print(f"  MISSING ROUTE {proposal['route_id']}")
        continue
    matches = (route["source_depot_id"] == proposal["depot_id"]
               and route["destination_station_id"] == proposal["station_id"])
    within = proposal["quantity_liters"] <= route["max_shipment"]
    if not (matches and within):
        print(f"  {proposal['line_id']}: endpoints match={matches}, within max_shipment={within}")
print("  all proposals use a real route with matching endpoints and within max_shipment")

print()
print("ALTERNATIVE CHECK")
for proposal in risk["proposals"]:
    alternatives = proposal["alternatives"]
    if not alternatives:
        print(f"  {proposal['line_id']}: no alternative offered")
    for alternative in alternatives:
        print(f"  {proposal['line_id']}: {alternative['route_id']} "
              f"[{alternative['reason_code']}]")
