"""Prints the backend's current risk plan.

Day 3 needs to know whether the planner has anything to propose, because a
proposal can only be created from a non-empty plan. This reads the same
endpoint the operator console reads.
"""

import json
import sys
import urllib.request

BACKEND = "http://localhost:8080"


def get(path):
    with urllib.request.urlopen(f"{BACKEND}{path}", timeout=30) as response:
        return json.loads(response.read().decode("utf-8"))


risk = get("/api/risk")
proposals = risk.get("proposals", [])
unmet = risk.get("unmet_needs", [])

print(f"tick          : {risk.get('tick')}")
print(f"quality       : {risk.get('quality')}")
print(f"proposals     : {len(proposals)}")
print(f"unmet needs   : {len(unmet)}")
print(f"policy version: {risk.get('policy_version')}")
print()

if proposals:
    print("PROPOSALS")
    for proposal in proposals:
        print(
            f"  {proposal['station_id']:<22} {proposal['fuel']:<8} "
            f"qty={proposal['quantity_liters']:<8} "
            f"need={proposal['need_liters']:<8} "
            f"depot={proposal['depot_id']:<18} route={proposal['route_id']}"
        )
        for reason in proposal.get("reasons", []):
            print(f"      reason: {reason}")
    print()

if unmet:
    print("UNMET NEEDS")
    for need in unmet:
        print(
            f"  {need['station_id']:<22} {need['fuel']:<8} "
            f"need={need['need_liters']:<8} {need['reason_code']:<26} {need['reason']}"
        )

sys.exit(0)
