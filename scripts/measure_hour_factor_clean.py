"""Re-measure the implied hour factor with a clean, event-free world.

Why this script exists separately from measure_hour_factor.py
-------------------------------------------------------------
The first measurement was taken while a demand_spike event was still ACTIVE in
region-dhaka. That inflated exactly the two region-dhaka stations by the spike
multiplier (1.8), which looked like a profile mismatch and was not one. The
measurement had to be redone on a world with no events.

This script therefore:
  1. resets the simulator, which clears every scheduled event,
  2. runs a full day so every hour of every profile is covered,
  3. refuses to report anything unless no event is active.

The last point matters. A measurement that silently includes an injected fault
is worse than no measurement, because it produces confident wrong numbers.
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
    "industrial": (list(range(6, 18)), 1.55, 0.45),
    "highway": (list(range(6, 10)) + list(range(16, 21)), 1.35, 0.75),
    "urban_high": (list(range(7, 10)) + list(range(16, 21)), 1.45, 0.70),
    "regional": (list(range(7, 21)), 1.25, 0.65),
}


def call(path, method="GET", body=None):
    data = json.dumps(body).encode() if body is not None else None
    headers = {"Content-Type": "application/json"} if data is not None else {}
    request = urllib.request.Request(f"{HOST}{path}", data=data, method=method, headers=headers)
    with urllib.request.urlopen(request, timeout=60) as response:
        text = response.read().decode()
        return json.loads(text) if text.strip() else None


def get(path):
    return call(path)


def main():
    print("Resetting the simulator so no event is active...")
    call("/admin/reset", method="POST")
    instance = get("/v1/instance")
    ticks_per_day = (24 * 60) // instance["tick_minutes"]
    print(f"  tick={instance['tick']} tick_minutes={instance['tick_minutes']} "
          f"ticks_per_day={ticks_per_day}")

    print(f"Stepping {ticks_per_day} ticks to cover a full day...")
    for _ in range(ticks_per_day):
        call("/admin/step", method="POST")

    events = get("/v1/events") or []
    active = [e for e in events if str(e.get("status", "")).upper() == "ACTIVE"]
    print(f"  events on the world: {len(events)}, active: {len(active)}")
    if active:
        print("  REFUSING TO MEASURE: an event is still active, the numbers would be wrong.")
        for event in active:
            print(f"    {event.get('type')} {event.get('status')} {event.get('parameters')}")
        return 1

    regions = {r["id"]: r["demand_factor"] for r in get("/v1/regions")}
    stations = get("/v1/stations")

    implied = defaultdict(lambda: defaultdict(list))
    for station in stations:
        profile = station["demand_profile"]
        region_factor = regions[station["region_id"]]
        for row in get(f"/v1/demand-history?station_id={station['id']}&limit=2000"):
            base = DAILY_LITERS[profile][row["fuel_type"]] / ticks_per_day * region_factor
            if base <= 0:
                continue
            implied[profile][int(row["sim_time"][11:13])].append(row["demand_liters"] / base)

    print()
    print("IMPLIED HOUR FACTOR vs DOCUMENTED")
    print(f"{'profile':<12} {'hour':<6} {'n':<5} {'median':<12} {'documented':<12} {'match'}")
    print("-" * 62)

    mismatches = []
    for profile in sorted(implied):
        busy_hours, busy_factor, off_factor = DOCUMENTED[profile]
        for hour in sorted(implied[profile]):
            values = sorted(implied[profile][hour])
            median = values[len(values) // 2]
            documented = busy_factor if hour in busy_hours else off_factor
            # The documented factor is the median; noise is multiplicative and
            # centred on it, so the median is the right comparison.
            match = "yes" if abs(median / documented - 1.0) < 0.08 else "NO"
            if match == "NO":
                mismatches.append((profile, hour, median, documented))
            print(f"{profile:<12} {hour:<6} {len(values):<5} {median:<12.4f} "
                  f"{documented:<12.2f} {match}")

    print()
    if mismatches:
        print("MISMATCHES (median implied vs documented):")
        for profile, hour, median, documented in mismatches:
            print(f"  {profile} hour {hour}: implied median {median:.4f} vs documented {documented}")
            print(f"      the detector would compute a ratio of {median / documented:.4f} for this hour")
        print()
        print("A mismatch matters only if it pushes the ratio over the anomaly")
        print("threshold of 1.35. A ratio below that produces no flag, so the")
        print("mismatch is a description inaccuracy rather than a detection fault.")
    else:
        print("No mismatch: every hour's median implied factor matches the documented table.")

    # The decisive question: does any hour's implied ratio reach the threshold?
    threshold = 1.35
    worst = 0.0
    worst_where = ""
    for profile in sorted(implied):
        busy_hours, busy_factor, off_factor = DOCUMENTED[profile]
        for hour in sorted(implied[profile]):
            values = sorted(implied[profile][hour])
            median = values[len(values) // 2]
            documented = busy_factor if hour in busy_hours else off_factor
            ratio = median / documented
            if ratio > worst:
                worst = ratio
                worst_where = f"{profile} hour {hour}"

    print()
    print(f"WORST HOUR RATIO: {worst:.4f} at {worst_where}")
    print(f"ANOMALY THRESHOLD: {threshold}")
    if worst < threshold:
        print("VERDICT: no hour reaches the threshold, so no hour can raise a false flag.")
    else:
        print("VERDICT: at least one hour reaches the threshold and would raise a false flag.")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
