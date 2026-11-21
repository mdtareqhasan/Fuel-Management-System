"""Derive the simulator's implied hour-of-day factor from observations.

Why this exists
---------------
The guide documents an hour-of-day factor per demand profile. If that table is
transcribed wrong, the anomaly detector divides by the wrong number and every
busy hour at that station becomes a permanent false positive.

Rather than trusting a transcription, this script derives the *implied* factor
from the live simulator:

    implied_factor = observed_demand / (daily_liters / ticks_per_day * region_factor)

and prints it per (profile, hour, tick-within-hour). The documented table is
printed next to it for comparison, so any disagreement is visible rather than
buried in a ratio average.
"""

import json
import urllib.request
from collections import defaultdict

HOST = "http://localhost:8000"

DAILY_LITERS = {
    "urban_high": {"DIESEL": 8500, "PETROL": 10500, "OCTANE": 5600},
    "industrial": {"DIESEL": 14000, "PETROL": 4500, "OCTANE": 2200},
    "highway": {"DIESEL": 10500, "PETROL": 11000, "OCTANE": 6200},
    "regional": {"DIESEL": 7200, "PETROL": 7600, "OCTANE": 3600},
}

# As transcribed from the guide, section 8.6.
DOCUMENTED = {
    "industrial": (range(6, 18), 1.55, 0.45),
    "highway": (list(range(6, 10)) + list(range(16, 21)), 1.35, 0.75),    "urban_high": (list(range(7, 10)) + list(range(16, 21)), 1.45, 0.70),
    "regional": (range(7, 21), 1.25, 0.65),
}


def get(path):
    with urllib.request.urlopen(f"{HOST}{path}", timeout=15) as response:
        return json.loads(response.read().decode("utf-8"))


instance = get("/v1/instance")
ticks_per_day = (24 * 60) // instance["tick_minutes"]
ticks_per_hour = 60 // instance["tick_minutes"]
regions = {r["id"]: r["demand_factor"] for r in get("/v1/regions")}

# implied[profile][hour] -> list of implied factors (all fuels, all ticks)
implied = defaultdict(lambda: defaultdict(list))
# per (profile, hour, minute-offset-in-hour) for the intra-hour shape
implied_slot = defaultdict(list)

for station in get("/v1/stations"):
    profile = station["demand_profile"]
    region_factor = regions[station["region_id"]]
    for row in get(f"/v1/demand-history?station_id={station['id']}&limit=2000"):
        base = DAILY_LITERS[profile][row["fuel_type"]] / ticks_per_day * region_factor
        if base <= 0:
            continue
        factor = row["demand_liters"] / base
        hour = int(row["sim_time"][11:13])
        minute_in_hour = int(row["sim_time"][14:16])
        implied[profile][hour].append(factor)
        implied_slot[(profile, hour, minute_in_hour)].append(factor)

print(f"tick_minutes={instance['tick_minutes']} ticks_per_hour={ticks_per_hour}")
print()
print("IMPLIED HOUR FACTOR vs DOCUMENTED")
print(f"{'profile':<12} {'hour':<6} {'n':<5} {'implied mean':<14} {'implied min':<13} {'implied max':<13} {'documented':<12} {'match'}")
print("-" * 100)

mismatches = []
for profile in sorted(implied):
    busy_hours, busy_factor, off_factor = DOCUMENTED[profile]
    for hour in sorted(implied[profile]):
        values = implied[profile][hour]
        mean = sum(values) / len(values)
        documented = busy_factor if hour in busy_hours else off_factor
        # The documented factor is the median; noise is multiplicative and centred
        # on it, so compare against the median rather than the mean.
        ordered = sorted(values)
        median = ordered[len(ordered) // 2]
        match = "yes" if abs(median / documented - 1.0) < 0.08 else "NO"
        if match == "NO":
            mismatches.append((profile, hour, median, documented))
        print(
            f"{profile:<12} {hour:<6} {len(values):<5} {mean:<14.4f} "
            f"{min(values):<13.4f} {max(values):<13.4f} {documented:<12.2f} {match}"
        )

print()
print("INTRA-HOUR SHAPE (mean implied factor per minute offset)")
for (profile, hour, minute), values in sorted(implied_slot.items()):
    if len(values) >= 2:
        print(
            f"  {profile:<12} hour={hour:<3} minute={minute:<3} n={len(values):<4} "
            f"mean={sum(values) / len(values):.4f}"
        )

print()
if mismatches:
    print("MISMATCHES (median implied vs documented):")
    for profile, hour, median, documented in mismatches:
        print(f"  {profile} hour {hour}: implied median {median:.4f} vs documented {documented}")
else:
    print("No mismatch: every hour's median implied factor matches the documented table.")
