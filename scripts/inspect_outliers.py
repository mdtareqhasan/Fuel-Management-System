"""Print the highest observed/expected ratios with full row detail.

Used during Day 2 to explain any ratio that sits outside the documented noise
band, so the cause is identified before the constants are trusted.
"""

import json
import urllib.request

HOST = "http://localhost:8000"

DAILY_LITERS = {
    "urban_high": {"DIESEL": 8500, "PETROL": 10500, "OCTANE": 5600},
    "industrial": {"DIESEL": 14000, "PETROL": 4500, "OCTANE": 2200},
    "highway": {"DIESEL": 10500, "PETROL": 11000, "OCTANE": 6200},
    "regional": {"DIESEL": 7200, "PETROL": 7600, "OCTANE": 3600},
}


def hour_factors(profile, hour):
    if profile == "industrial":
        return 1.55 if 6 <= hour <= 17 else 0.45
    if profile == "highway":
        return 1.35 if (7 <= hour <= 9) or (16 <= hour <= 20) else 0.75
    if profile == "urban_high":
        return 1.45 if (7 <= hour <= 9) or (16 <= hour <= 20) else 0.70
    if profile == "regional":
        return 1.25 if 7 <= hour <= 20 else 0.65
    raise KeyError(profile)


def get(path):
    with urllib.request.urlopen(f"{HOST}{path}", timeout=15) as response:
        return json.loads(response.read().decode("utf-8"))


instance = get("/v1/instance")
ticks_per_day = (24 * 60) // instance["tick_minutes"]
regions = {r["id"]: r["demand_factor"] for r in get("/v1/regions")}

scored = []
for station in get("/v1/stations"):
    profile = station["demand_profile"]
    region_factor = regions[station["region_id"]]
    for row in get(f"/v1/demand-history?station_id={station['id']}&limit=2000"):
        hour = int(row["sim_time"][11:13])
        expected = (
            DAILY_LITERS[profile][row["fuel_type"]]
            / ticks_per_day
            * hour_factors(profile, hour)
            * region_factor
        )
        if expected > 0:
            scored.append(
                {
                    "ratio": row["demand_liters"] / expected,
                    "station": station["id"],
                    "profile": profile,
                    "fuel": row["fuel_type"],
                    "tick": row["tick"],
                    "sim_time": row["sim_time"],
                    "hour": hour,
                    "demand": row["demand_liters"],
                    "served": row["served_liters"],
                    "unmet": row["unmet_liters"],
                }
            )

scored.sort(key=lambda r: -r["ratio"])

print("TOP 15 RATIOS")
for r in scored[:15]:
    print(
        f"ratio={r['ratio']:.4f} {r['station']} {r['profile']} {r['fuel']} "
        f"tick={r['tick']} sim={r['sim_time']} hour={r['hour']} "
        f"demand={r['demand']} served={r['served']} unmet={r['unmet']}"
    )

print()
print("BOTTOM 5 RATIOS")
for r in scored[-5:]:
    print(
        f"ratio={r['ratio']:.4f} {r['station']} {r['profile']} {r['fuel']} "
        f"tick={r['tick']} sim={r['sim_time']} hour={r['hour']} "
        f"demand={r['demand']} served={r['served']} unmet={r['unmet']}"
    )

print()
unmet_total = sum(r["unmet"] for r in scored)
served_zero = sum(1 for r in scored if r["served"] == 0)
print(f"rows={len(scored)} unmet_total={unmet_total:.3f} rows_with_served_zero={served_zero}")
print(f"mean_ratio={sum(r['ratio'] for r in scored) / len(scored):.4f}")
