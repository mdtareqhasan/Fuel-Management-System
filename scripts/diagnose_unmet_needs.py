"""Diagnose the allocation plan's unmet needs.

Prints each unmet need with its gap, and counts how many times each
station and fuel appears, so a duplicate is visible rather than assumed.
"""

import json
import urllib.request
from collections import Counter

BACKEND = "http://localhost:8080"


def get(path):
    with urllib.request.urlopen(f"{BACKEND}{path}", timeout=60) as response:
        return json.loads(response.read().decode("utf-8"))


risk = get("/api/risk")
unmet = risk["unmet_needs"]

print(f"unmet needs: {len(unmet)}")
print()
print(f"{'station':<22} {'fuel':<8} {'need':>14} {'met':>12} {'gap':>12}  reason")
print("-" * 92)
for need in unmet:
    gap = need["need_liters"] - need["met_liters"]
    print(f"{need['station_id']:<22} {need['fuel']:<8} {need['need_liters']:>14.3f} "
          f"{need['met_liters']:>12.3f} {gap:>12.3f}  {need['reason_code']}")

print()
counts = Counter((n["station_id"], n["fuel"]) for n in unmet)
duplicates = {key: count for key, count in counts.items() if count > 1}
print(f"distinct station/fuel pairs: {len(counts)}")
print(f"duplicated pairs: {len(duplicates)}")
for key, count in sorted(duplicates.items()):
    print(f"  {key[0]:<22} {key[1]:<8} appears {count} times")

print()
print("GAP ANALYSIS")
gaps = sorted(n["need_liters"] - n["met_liters"] for n in unmet)
print(f"  smallest gap: {gaps[0]:.4f}")
print(f"  largest gap:  {gaps[-1]:.4f}")
tiny = [g for g in gaps if g < 1.0]
print(f"  gaps below 1 litre: {len(tiny)} of {len(gaps)}")
if tiny:
    print("  a gap under one litre is a rounding residue, not a real shortfall")

print()
print("PROPOSALS")
for proposal in risk["proposals"]:
    print(f"  {proposal['station_id']:<22} {proposal['fuel']:<8} "
          f"{proposal['quantity_liters']:>10.3f}  need {proposal['need_liters']:.3f}  "
          f"gap {proposal['need_liters'] - proposal['quantity_liters']:.3f}")
