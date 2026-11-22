"""Prints the station risk rows.

Day 3 needs a proposal to review, and a proposal comes from a plan. When the
plan is empty, this says whether the risk layer is producing rows at all.
"""

import json
import urllib.request

BACKEND = "http://localhost:8080"

with urllib.request.urlopen(f"{BACKEND}/api/risk", timeout=30) as response:
    risk = json.loads(response.read().decode("utf-8"))

print(f"tick            : {risk.get('tick')}")
print(f"quality         : {risk.get('quality')}")
print(f"station risks   : {len(risk.get('station_risks', []))}")
print(f"depot risks     : {len(risk.get('depot_risks', []))}")
print(f"forecasts       : {len(risk.get('forecasts', {}))}")
print(f"anomaly flags   : {len(risk.get('anomaly_flags', []))}")
print(f"proposals       : {len(risk.get('proposals', []))}")
print(f"unmet needs     : {len(risk.get('unmet_needs', []))}")
print()

for row in risk.get("station_risks", [])[:10]:
    forecast = row.get("forecast") or {}
    print(
        f"{row['station_id']:<22} {row['fuel']:<8} {row['level']:<9} "
        f"inv={row.get('current_inventory_liters')} "
        f"stockout={row.get('central_stockout_hours')}h "
        f"unmet={row.get('projected_unmet_liters')} "
        f"method={forecast.get('method')} conf={forecast.get('confidence')} "
        f"samples={forecast.get('sample_count')}"
    )
    for reason in row.get("reasons", [])[:3]:
        print(f"      {reason}")

print()
print("FORECASTS (first 6)")
for key, forecast in list(risk.get("forecasts", {}).items())[:6]:
    print(
        f"  {key:<30} method={forecast.get('method'):<16} "
        f"rate={forecast.get('central_liters_per_hour')} "
        f"samples={forecast.get('sample_count')} known={forecast.get('known')}"
    )
    for reason in forecast.get("reasons", [])[:2]:
        print(f"      {reason}")
