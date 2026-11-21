"""Print a readable summary of the backend's risk response.

Used during Day 2 to read the /api/risk output without scrolling through raw JSON,
and to capture it as evidence.

The backend serialises records in snake_case, so the keys here are snake_case too.
"""

import json
import sys
import urllib.request

URL = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080/api/risk"


def get(url):
    with urllib.request.urlopen(url, timeout=60) as response:
        return json.loads(response.read().decode("utf-8"))


risk = get(URL)

print(f"tick={risk['tick']} sim_time={risk['sim_time']} tick_minutes={risk['tick_minutes']} "
      f"horizon_hours={risk['horizon_hours']} "
      f"quality={risk['metadata']['quality']} "
      f"decision_eligible={risk['metadata']['decision_eligible']}")
print(f"detector_status={risk['detector_status']} policy={risk['policy_version']}")
print()

print("FORECASTS")
print(f"{'station':<22} {'fuel':<8} {'method':<17} {'confidence':<10} {'n':<4} "
      f"{'central L/h':<13} {'stress L/h':<12} {'ewma MAE':<10} {'naive MAE':<10}")
print("-" * 115)
for key, forecast in sorted(risk["forecasts"].items()):
    station, fuel = key.split("|")
    print(f"{station:<22} {fuel:<8} {forecast['method']:<17} {forecast['confidence']:<10} "
          f"{forecast['sample_count']:<4} {forecast['central_liters_per_hour']!s:<13} "
          f"{forecast['stress_liters_per_hour']!s:<12} {forecast['recent_absolute_error']!s:<10} "
          f"{forecast['baseline_absolute_error']!s}")
print()
print("STATION RISK")
print(f"{'level':<9} {'station':<22} {'fuel':<8} {'stock':<9} {'stockout tick':<14} "
      f"{'hours':<8} {'unmet':<9} {'inbound':<8} flags")
print("-" * 100)
flag_index = {}
for flag in risk["anomaly_flags"]:
    flag_index.setdefault((flag["station_id"], flag["fuel"]), []).append(flag["label"])
for row in risk["station_risks"]:
    flags = flag_index.get((row["station_id"], row["fuel"]), [])
    print(f"{row['level']:<9} {row['station_id']:<22} {row['fuel']:<8} "
          f"{row['current_inventory_liters']:<9} {row['central_stockout_tick']!s:<14} "
          f"{row['central_stockout_hours']!s:<8} {row['projected_unmet_liters']:<9} "
          f"{len(row['inbound_arrivals']):<8} {','.join(flags)}")

print()
print("DEPOT RISK")
print(f"{'level':<9} {'depot':<18} {'fuel':<8} {'stock':<9} {'shortfall tick':<15} "
      f"{'hours':<8} {'required':<10} changes")
print("-" * 100)
for row in risk["depot_risks"]:
    print(f"{row['level']:<9} {row['depot_id']:<18} {row['fuel']:<8} "
          f"{row['current_inventory_liters']:<9} {row['shortfall_tick']!s:<15} "
          f"{row['shortfall_hours']!s:<8} {row['required_replenishment_liters']:<10} "
          f"{len(row['supply_changes'])}")

print()
print(f"ANOMALY FLAGS ({len(risk['anomaly_flags'])})")
for flag in risk["anomaly_flags"]:
    print(f"  {flag['label']} {flag['station_id']} {flag['fuel']} ratio={flag['ratio']} "
          f"cause={flag['cause']} ticks={flag['consecutive_ticks']} at tick {flag['detected_at_tick']}")

print()
print(f"PROPOSALS ({len(risk['proposals'])})")
for proposal in risk["proposals"]:
    print(f"  {proposal['station_id']} {proposal['fuel']}: {proposal['quantity_liters']} litres "
          f"from {proposal['depot_id']} on {proposal['route_id']}, arrives tick "
          f"{proposal['estimated_arrival_tick']} ({proposal['estimated_arrival_hours']} h)")
    for reason in proposal["reasons"]:
        print(f"      reason: {reason}")
    for alternative in proposal["alternatives"]:
        print(f"      alternative: {alternative['route_id']} {alternative['quantity_liters']} litres "
              f"[{alternative['reason_code']}] {alternative['reason']}")

print()
print(f"UNMET NEEDS ({len(risk['unmet_needs'])})")
for need in risk["unmet_needs"]:
    print(f"  {need['station_id']} {need['fuel']}: need {need['need_liters']}, met {need['met_liters']} "
          f"[{need['reason_code']}] {need['reason']}")

print()
print("DETECTOR NOTES")
for note in risk["detector_notes"]:
    print(f"  {note}")
