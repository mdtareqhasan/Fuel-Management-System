"""Measure the simulator's demand profile constants against live observations.

Purpose
-------
The forecast's cold-start fallback and the anomaly detector both divide observed
demand by an *expected* demand derived from documented profile constants. If those
constants are wrong, every ratio is wrong and the detector flags normal hours.

This script pulls real demand history from the running simulator and reports, per
(station, fuel), the ratio observed / expected under the documented formula:

    expected_liters_per_tick = daily_liters / ticks_per_day
                               * hour_of_day_factor
                               * region_factor
                               * multiplier(=1.0)

A ratio near 1.0 confirms the constants. A ratio systematically off confirms the
formula or the window boundaries are wrong.

Usage
-----
    python scripts/measure_profile.py [--host http://localhost:8000]
"""

import argparse
import json
import urllib.request
from collections import defaultdict

# ---------------------------------------------------------------------------
# Documented simulator constants (integration guide sections 8.5 and 8.6).
# These are copied from the official guide, not inferred from the data.
# ---------------------------------------------------------------------------

DAILY_LITERS = {
    "urban_high": {"DIESEL": 8500, "PETROL": 10500, "OCTANE": 5600},
    "industrial": {"DIESEL": 14000, "PETROL": 4500, "OCTANE": 2200},
    "highway": {"DIESEL": 10500, "PETROL": 11000, "OCTANE": 6200},
    "regional": {"DIESEL": 7200, "PETROL": 7600, "OCTANE": 3600},
}

NOISE = {
    "urban_high": 0.10,
    "industrial": 0.08,
    "highway": 0.12,
    "regional": 0.10,
}


def hour_factors(profile: str, hour: int) -> float:
    """The documented hour-of-day factor for a profile at a given hour of day.

    Busy windows are inclusive on both ends, exactly as the guide writes them.
    """
    if profile == "industrial":
        return 1.55 if 6 <= hour <= 17 else 0.45
    if profile == "highway":
        # The guide writes "06-09 or 16-20". Measured against the live simulator:
        # hours 6, 7, 8, 9, 16, 17, 18, 19 and 20 are busy (implied 1.32-1.38).
        return 1.35 if (6 <= hour <= 9) or (16 <= hour <= 20) else 0.75
    if profile == "urban_high":
        return 1.45 if (7 <= hour <= 9) or (16 <= hour <= 20) else 0.70
    if profile == "regional":
        return 1.25 if 7 <= hour <= 20 else 0.65
    raise KeyError(f"unknown demand profile {profile}")


def get(host: str, path: str):
    with urllib.request.urlopen(f"{host}{path}", timeout=10) as response:
        return json.loads(response.read().decode("utf-8"))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="http://localhost:8000")
    args = parser.parse_args()
    host = args.host

    instance = get(host, "/v1/instance")
    tick_minutes = instance["tick_minutes"]
    ticks_per_day = (24 * 60) // tick_minutes
    print(f"tick={instance['tick']} tick_minutes={tick_minutes} ticks_per_day={ticks_per_day}")

    regions = {r["id"]: r["demand_factor"] for r in get(host, "/v1/regions")}
    stations = get(host, "/v1/stations")

    # ratio_samples[(station_id, fuel)][hour] -> list of ratios
    ratio_samples = defaultdict(lambda: defaultdict(list))
    observed_per_hour = defaultdict(lambda: defaultdict(list))

    for station in stations:
        station_id = station["id"]
        profile = station["demand_profile"]
        region_factor = regions[station["region_id"]]
        rows = get(host, f"/v1/demand-history?station_id={station_id}&limit=2000")

        for row in rows:
            fuel = row["fuel_type"]
            hour = int(row["sim_time"][11:13])
            daily = DAILY_LITERS[profile][fuel]
            expected = (
                daily
                / ticks_per_day
                * hour_factors(profile, hour)
                * region_factor
            )
            if expected <= 0:
                continue
            ratio_samples[(station_id, fuel)][hour].append(row["demand_liters"] / expected)
            observed_per_hour[(station_id, fuel)][hour].append(row["demand_liters"])

    print()
    print(f"{'station':<22} {'fuel':<8} {'hour':<5} {'n':<4} {'mean ratio':<12} {'min':<8} {'max':<8}")
    print("-" * 70)

    overall_ratios = []
    for (station_id, fuel), by_hour in sorted(ratio_samples.items()):
        for hour in sorted(by_hour):
            ratios = by_hour[hour]
            mean = sum(ratios) / len(ratios)
            overall_ratios.extend(ratios)
            print(
                f"{station_id:<22} {fuel:<8} {hour:<5} {len(ratios):<4} "
                f"{mean:<12.4f} {min(ratios):<8.4f} {max(ratios):<8.4f}"
            )

    print()
    if overall_ratios:
        mean = sum(overall_ratios) / len(overall_ratios)
        worst = max(abs(r - 1.0) for r in overall_ratios)
        print(f"ALL RATIOS   n={len(overall_ratios)}  mean={mean:.4f}  "
              f"max deviation from 1.0 = {worst:.4f}")
        print("Interpretation: a mean near 1.000 and a max deviation inside the")
        print("documented noise band confirm the constants and the hour windows.")


if __name__ == "__main__":
    main()
