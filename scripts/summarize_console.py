"""Print a compact text view of the console's sections, for the Day 2 evidence.

Reads the same /api/risk endpoint the browser reads, so what is printed here is
what the operator sees.
"""

import json
import sys
import urllib.request

BACKEND = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"


def get(path):
    with urllib.request.urlopen(f"{BACKEND}{path}", timeout=60) as response:
        return json.loads(response.read().decode("utf-8"))


risk = get("/api/risk")

print("CONSOLE: INTELLIGENCE PANEL")
print(f"  snapshot {risk['metadata']['snapshot_id'][:8]}  tick {risk['tick']}  "
      f"sim_time {risk['sim_time']}  quality {risk['metadata']['quality']}")
print()

print("SUMMARY TILES")
critical = sum(1 for r in risk["station_risks"] if r["level"] == "CRITICAL")
warning = sum(1 for r in risk["station_risks"] if r["level"] == "WARNING")
unexplained = sum(1 for f in risk["anomaly_flags"] if f["cause"] == "UNEXPLAINED")
fallbacks = sum(1 for f in risk["forecasts"].values() if f["method"] == "PROFILE_ESTIMATE")
print(f"  Critical {critical} | Warning {warning} | Anomaly flags {len(risk['anomaly_flags'])} "
      f"({unexplained} unexplained) | Profile fallback {fallbacks} | "
      f"Horizon {risk['horizon_hours']} h | Detector {risk['detector_status']}")
print()

print("STATION RISK TABLE")
for row in risk["station_risks"]:
    forecast = row["forecast"] or {}
    method = forecast.get("method", "-")
    confidence = forecast.get("confidence", "-")
    n = forecast.get("sample_count", "-")
    inbound = row["inbound_arrivals"]
    inbound_text = (
        ", ".join(f"{s['quantity_liters']}L @tick {s['expected_arrival_tick']}"
                  + ("(est)" if s["arrival_is_estimate"] else "")
                  for s in inbound) if inbound else "none"
    )
    print(f"  {row['level']:<8} {row['station_id']:<20} {row['fuel']:<8} "
          f"stock={row['current_inventory_liters']!s:<12} "
          f"dry@tick={row['central_stockout_tick']!s:<7} "
          f"({row['central_stockout_hours']!s}h)  "
          f"inbound={inbound_text:<24} {method}/{confidence}/n={n}")
print()

print("RECOMMENDED SHIPMENTS")
for proposal in risk["proposals"]:
    print(f"  {proposal['station_id']:<20} {proposal['fuel']:<8} "
          f"{proposal['quantity_liters']:>8.0f} L  {proposal['depot_id']} -> {proposal['route_id']}  "
          f"arrives tick {proposal['estimated_arrival_tick']}")
    for alternative in proposal["alternatives"]:
        route = alternative["route_id"] or "(none)"
        print(f"      alternative {route:<28} [{alternative['reason_code']}]")
print()

print("UNMET NEEDS")
for need in risk["unmet_needs"]:
    print(f"  {need['station_id']:<20} {need['fuel']:<8} need {need['need_liters']} "
          f"met {need['met_liters']} [{need['reason_code']}]")
if not risk["unmet_needs"]:
    print("  none")
