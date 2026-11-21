"""Day 2 acceptance evidence: the anomaly detector, measured.

ADR 005 requires two numbers, measured rather than assumed:

1. The **false flag count** in a quiet run. A quiet scenario must produce no flags.
2. The **detection delay** after an injected demand spike: how many ticks pass
   before R1 and R2 fire.

The script drives the official simulator through its admin endpoints (which bypass
fault injection), reads the demand history and evaluates the ratios itself, using the
same measured profile constants the backend uses. It then cross-checks the backend's
own detector against the independent calculation.

Usage
-----
    python scripts/verify_anomaly_detection.py
"""

import json
import urllib.error
import urllib.request

SIM = "http://localhost:8000"
BACKEND = "http://localhost:8080"

DAILY_LITERS = {
    "urban_high": {"DIESEL": 8500, "PETROL": 10500, "OCTANE": 5600},
    "industrial": {"DIESEL": 14000, "PETROL": 4500, "OCTANE": 2200},
    "highway": {"DIESEL": 10500, "PETROL": 11000, "OCTANE": 6200},
    "regional": {"DIESEL": 7200, "PETROL": 7600, "OCTANE": 3600},
}

# The busy windows re-measured against the running simulator on 2026-11-21.
BUSY = {
    "industrial": (list(range(6, 18)), 1.55, 0.45),
    "highway": (list(range(6, 10)) + list(range(16, 21)), 1.35, 0.75),
    "urban_high": (list(range(7, 10)) + list(range(16, 21)), 1.45, 0.70),
    "regional": (list(range(7, 21)), 1.25, 0.65),
}

RATIO_THRESHOLD = 1.35
RECOVERY_THRESHOLD = 1.15
CONSECUTIVE = 3
REGIONAL_STATIONS = 2
FUELS = ("DIESEL", "PETROL", "OCTANE")


def call(url, method="GET", body=None):
    data = json.dumps(body).encode() if body is not None else None
    headers = {"Content-Type": "application/json"} if data is not None else {}
    request = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            text = response.read().decode()
            return json.loads(text) if text.strip() else None
    except urllib.error.HTTPError as error:
        print(f"  call failed {method} {url}: {error.code} {error.read().decode()[:200]}")
        return None


def hour_factor(profile, hour):
    busy_hours, busy_factor, off_factor = BUSY[profile]
    return busy_factor if hour in busy_hours else off_factor


def ratio_series(station, rows, region_factor, ticks_per_day):
    """The observed/expected ratio for one station and fuel, oldest tick first."""
    profile = station["demand_profile"]
    series = []
    for row in sorted(rows, key=lambda r: r["tick"]):
        if row["fuel_type"] not in FUELS:
            continue
        fuel = row["fuel_type"]
        hour = int(row["sim_time"][11:13])
        expected = (DAILY_LITERS[profile][fuel] / ticks_per_day
                    * hour_factor(profile, hour) * region_factor)
        if expected <= 0:
            continue
        series.append((row["tick"], row["demand_liters"] / expected))
    return series


def evaluate(series):
    """Applies R1 and returns the tick the rule fired on, or None."""
    consecutive = 0
    for tick, ratio in series:
        if ratio >= RATIO_THRESHOLD:
            consecutive += 1
            if consecutive >= CONSECUTIVE:
                return tick
        else:
            consecutive = 0
    return None


def step(ticks):
    for _ in range(ticks):
        call(f"{SIM}/admin/step", method="POST")


def main():
    instance = call(f"{SIM}/v1/instance")
    ticks_per_day = (24 * 60) // instance["tick_minutes"]
    regions = {r["id"]: r["demand_factor"] for r in call(f"{SIM}/v1/regions")}
    stations = call(f"{SIM}/v1/stations")

    print("=" * 78)
    print("MEASUREMENT 1: false flags in a quiet run")
    print("=" * 78)
    print(f"starting at tick {instance['tick']}, tick_minutes={instance['tick_minutes']}, "
          f"ticks_per_day={ticks_per_day}")

    step(24)
    quiet_flags = 0
    for station in stations:
        rows = call(f"{SIM}/v1/demand-history?station_id={station['id']}&limit=2000")
        region_factor = regions[station["region_id"]]
        series = ratio_series(station, rows, region_factor, ticks_per_day)
        fired = evaluate(series)
        max_ratio = max((r for _, r in series), default=0)
        status = f"FLAGGED at tick {fired}" if fired is not None else "no flag"
        print(f"  {station['id']:<22} {station['demand_profile']:<12} "
              f"n={len(series):<5} max ratio={max_ratio:.4f}  {status}")
        if fired is not None:
            quiet_flags += 1

    print()
    print(f"QUIET RUN FALSE FLAGS: {quiet_flags}")
    print()

    print("=" * 78)
    print("MEASUREMENT 2: detection delay after an injected demand spike")
    print("=" * 78)

    spike_tick = call(f"{SIM}/v1/instance")["tick"]
    call(f"{SIM}/admin/events", method="POST", body={
        "type": "demand_spike",
        "start_tick": spike_tick,
        "duration_ticks": 24,
        "parameters": {"region_ids": ["region-dhaka"], "multiplier": 1.8},
    })
    print(f"injected demand_spike multiplier=1.8 on region-dhaka at tick {spike_tick}")

    step(16)

    station_ticks = {}
    for station in stations:
        rows = call(f"{SIM}/v1/demand-history?station_id={station['id']}&limit=2000")
        region_factor = regions[station["region_id"]]
        series = ratio_series(station, rows, region_factor, ticks_per_day)
        station_ticks[station["id"]] = evaluate(series)

    print()
    print(f"{'station':<22} {'region':<20} {'R1 fired at tick':<20} {'delay after spike'}")
    print("-" * 85)
    dhaka = [s for s in stations if s["region_id"] == "region-dhaka"]
    first_fire = None
    for station in stations:
        fired = station_ticks[station["id"]]
        if fired is None:
            print(f"  {station['id']:<22} {station['region_id']:<20} {'never':<20} -")
        else:
            print(f"  {station['id']:<22} {station['region_id']:<20} {fired:<20} "
                  f"{fired - spike_tick} ticks")
            if station["region_id"] == "region-dhaka" and (first_fire is None or fired < first_fire):
                first_fire = fired

    dhaka_fires = [station_ticks[s["id"]] for s in dhaka if station_ticks[s["id"]] is not None]
    print()
    if first_fire is not None:
        print(f"R1 FIRED: {first_fire - spike_tick} ticks after the spike was injected")
    else:
        print("R1 NEVER FIRED")

    if len(dhaka_fires) >= REGIONAL_STATIONS:
        r2_tick = max(dhaka_fires)
        print(f"R2 FIRED: {r2_tick - spike_tick} ticks after the spike was injected "
              f"({len(dhaka_fires)} stations of region-dhaka flagged)")
    else:
        print(f"R2 DID NOT FIRE: only {len(dhaka_fires)} of region-dhaka flagged, "
              f"{REGIONAL_STATIONS} required")

    print()
    print("CROSS-CHECK: the backend's own detector")
    risk = call(f"{BACKEND}/api/risk")
    print(f"  backend tick={risk['tick']} detector_status={risk['detector_status']} "
          f"flags={len(risk['anomaly_flags'])}")
    for flag in risk["anomaly_flags"]:
        print(f"    {flag['severity']:<8} {flag['station_id']:<22} {flag['fuel']:<8} "
              f"ratio={flag['ratio']:<8.4f} cause={flag['cause']:<13} "
              f"at tick {flag['detected_at_tick']}")

    backend_regional = [f for f in risk["anomaly_flags"] if f["severity"] == "REGIONAL"]
    print()
    print(f"  backend REGIONAL flags: {len(backend_regional)} "
          f"(independent calculation found {len(dhaka_fires)} spiking stations)")

    # Clear the spike so the world returns to normal for the remaining checks.
    call(f"{SIM}/admin/events", method="POST", body={
        "type": "demand_spike",
        "start_tick": call(f"{SIM}/v1/instance")["tick"],
        "duration_ticks": 1,
        "parameters": {"region_ids": ["region-dhaka"], "multiplier": 1.0},
    })


if __name__ == "__main__":
    main()
