"""Runs the day-1 acceptance checks against the running stack and records the output.

Every check is an observable fact about the system, not a claim about intent.
The script writes a markdown report so the evidence can be reviewed without a
terminal.

Usage:
    python scripts/verify_day1.py
"""

import json
import subprocess
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone

BACKEND = "http://localhost:8080"
SIMULATOR = "http://localhost:8000"
FRONTEND = "http://localhost:5173"
REPORT = "docs/evidence/day-01/verification-report.md"


def get(url: str, headers: dict | None = None) -> tuple[int, dict, str]:
    request = urllib.request.Request(url, headers=headers or {})
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            body = response.read().decode("utf-8")
            try:
                return response.status, dict(response.headers), json.loads(body)
            except json.JSONDecodeError:
                return response.status, dict(response.headers), body
    except urllib.error.HTTPError as error:
        return error.code, dict(error.headers), error.read().decode("utf-8")
    except Exception as error:  # noqa: BLE001 - the message is the useful part
        return 0, {}, str(error)


def post(url: str, body: dict) -> tuple[int, dict, str]:
    data = json.dumps(body).encode("utf-8")
    request = urllib.request.Request(url, data=data, method="POST")
    request.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            raw = response.read().decode("utf-8")
            try:
                return response.status, dict(response.headers), json.loads(raw)
            except json.JSONDecodeError:
                return response.status, dict(response.headers), raw
    except urllib.error.HTTPError as error:
        return error.code, dict(error.headers), error.read().decode("utf-8")
    except Exception as error:  # noqa: BLE001
        return 0, {}, str(error)


def sim_control(*args: str) -> tuple[int, str]:
    """Runs scripts/sim_control.py and returns its exit code and output."""
    result = subprocess.run(
        [sys.executable, "scripts/sim_control.py", *args],
        capture_output=True, text=True, timeout=30,
    )
    return result.returncode, result.stdout + result.stderr


def run_script(name: str) -> tuple[int, str, str]:
    """Runs another verification script and returns its exit code, stdout and stderr."""
    result = subprocess.run(
        [sys.executable, f"scripts/{name}"],
        capture_output=True, text=True, timeout=120,
    )
    return result.returncode, result.stdout, result.stderr


class Report:
    def __init__(self) -> None:
        self.lines: list[str] = []
        self.passed = 0
        self.failed = 0

    def heading(self, text: str) -> None:
        self.lines.append(f"\n## {text}\n")

    def check(self, name: str, ok: bool, detail: str) -> None:
        if ok:
            self.passed += 1
            mark = "PASS"
        else:
            self.failed += 1
            mark = "FAIL"
        self.lines.append(f"- **{mark}** — {name}")
        self.lines.append(f"  - {detail}")

    def note(self, text: str) -> None:
        self.lines.append(f"- {text}")

    def write(self, path: str) -> None:
        header = [
            "# Day 01 verification report",
            "",
            f"Generated: {datetime.now(timezone.utc).isoformat()}",
            "",
            "Every result below was produced by running the script, not asserted by hand.",
            "",
            f"**{report.passed} passed, {report.failed} failed**",
        ]
        with open(path, "w", encoding="utf-8") as handle:
            handle.write("\n".join(header + self.lines) + "\n")


report = Report()


def main() -> int:
    report.heading("1. Stack reachability")
    for label, url in (("backend health", f"{BACKEND}/actuator/health"),
                       ("dashboard", f"{BACKEND}/api/dashboard"),
                       ("system status", f"{BACKEND}/api/system-status"),
                       ("prometheus metrics", f"{BACKEND}/actuator/prometheus"),
                       ("frontend", FRONTEND),
                       ("simulator instance", f"{SIMULATOR}/v1/instance")):
        status, _, _ = get(url)
        report.check(label, status == 200, f"HTTP {status} from {url}")

    report.heading("2. Dashboard content comes from the simulator")
    status, _, dashboard = get(f"{BACKEND}/api/dashboard")
    if status == 200:
        stations = dashboard.get("stations", [])
        depots = dashboard.get("depots", [])
        routes = dashboard.get("routes", [])
        regions = dashboard.get("regions", [])
        report.check("4 stations", len(stations) == 4, f"got {len(stations)}: {[s['id'] for s in stations]}")
        report.check("2 depots", len(depots) == 2, f"got {len(depots)}: {[d['id'] for d in depots]}")
        report.check("6 routes", len(routes) == 6, f"got {len(routes)}")
        report.check("2 regions", len(regions) == 2, f"got {len(regions)}")

        all_fuels_present = all(
            all(fuel in station.get("inventory", {}) for fuel in ("DIESEL", "PETROL", "OCTANE"))
            for station in stations
        )
        report.check("all 3 fuels present for every station", all_fuels_present,
                     "DIESEL, PETROL and OCTANE on each of the 4 stations")

        non_zero = all(
            any((station.get("inventory", {}).get(fuel) or 0) > 0
                for fuel in ("DIESEL", "PETROL", "OCTANE"))
            for station in stations
        )
        report.check("inventory is non-zero (real data, not placeholders)", non_zero,
                     "every station has at least one non-zero fuel level")

        dispatch = [depot.get("dispatch_capacity_per_tick") for depot in depots]
        report.check("depot dispatch capacity present", all(value for value in dispatch),
                     f"dispatch_capacity_per_tick = {dispatch}")

        report.check("snapshot is decision eligible", dashboard.get("metadata", {}).get("decision_eligible") is True,
                     f"quality={dashboard.get('metadata', {}).get('quality')}")

    report.heading("3. The simulator's own response is the reference")
    status, headers, stations = get(f"{SIMULATOR}/v1/stations")
    report.check("simulator returns 4 stations", status == 200 and len(stations) == 4,
                 f"{len(stations) if isinstance(stations, list) else stations}")

    report.heading("4. Stale header handling")
    status, headers, _ = get(f"{SIMULATOR}/v1/stations")
    report.check("no stale header in the healthy state",
                 headers.get("x-simulator-stale", "false").lower() != "true",
                 f"x-simulator-stale = {headers.get('x-simulator-stale', '(absent)')}")

    sim_control("inject-fault", "stale_data", "15")
    time.sleep(3)
    status, headers, _ = get(f"{SIMULATOR}/v1/stations")
    report.check("simulator returns HTTP 200 with the stale header under the fault",
                 status == 200 and headers.get("x-simulator-stale", "").lower() == "true",
                 f"HTTP {status}, x-simulator-stale = {headers.get('x-simulator-stale', '(absent)')}")

    status, _, system_status = get(f"{BACKEND}/api/system-status")
    report.check("backend marks the view STALE",
                 system_status.get("data_quality") == "STALE",
                 f"data_quality = {system_status.get('data_quality')}")
    report.check("backend blocks decisions on stale data",
                 system_status.get("decision_eligible") is False,
                 f"decision_eligible = {system_status.get('decision_eligible')}")
    report.check("simulator still reported reachable",
                 system_status.get("simulator") == "UP",
                 f"simulator = {system_status.get('simulator')} (reachability is separate from freshness)")

    status, _, dashboard = get(f"{BACKEND}/api/dashboard")
    report.check("last known data is still shown while stale",
                 status == 200 and len(dashboard.get("stations", [])) == 4,
                 f"{len(dashboard.get('stations', []))} stations still visible")

    report.heading("5. Recovery")
    sim_control("clear-faults")
    time.sleep(4)
    status, _, system_status = get(f"{BACKEND}/api/system-status")
    report.check("view returns to FRESH after the fault clears",
                 system_status.get("data_quality") == "FRESH",
                 f"data_quality = {system_status.get('data_quality')}")
    report.check("decisions are re-enabled",
                 system_status.get("decision_eligible") is True,
                 f"decision_eligible = {system_status.get('decision_eligible')}")

    report.heading("6. Unavailable fault (cached read mode)")
    sim_control("inject-fault", "unavailable", "15")
    time.sleep(3)
    status, _, system_status = get(f"{BACKEND}/api/system-status")
    report.check("simulator reported DOWN",
                 system_status.get("simulator") == "DOWN",
                 f"simulator = {system_status.get('simulator')}")
    report.check("view marked INVALID",
                 system_status.get("data_quality") == "INVALID",
                 f"data_quality = {system_status.get('data_quality')}")
    report.check("decisions blocked",
                 system_status.get("decision_eligible") is False,
                 f"decision_eligible = {system_status.get('decision_eligible')}")

    status, _, dashboard = get(f"{BACKEND}/api/dashboard")
    report.check("cached view is still served",
                 status == 200 and len(dashboard.get("stations", [])) == 4,
                 f"{len(dashboard.get('stations', []))} stations from the cached snapshot")

    sim_control("clear-faults")
    time.sleep(4)

    report.heading("7. Metrics")
    status, _, metrics = get(f"{BACKEND}/actuator/prometheus")
    if isinstance(metrics, str):
        wanted = [
            "fuel_data_age_seconds",
            "fuel_database_up",
            "fuel_simulator_up",
            "fuel_simulator_call_seconds",
            "fuel_rejected_payloads_total",
            "fuel_stale_responses_total",
        ]
        for name in wanted:
            report.check(f"metric {name}", name in metrics, "present in /actuator/prometheus")
    else:
        report.check("metrics endpoint", False, "did not return text")

    report.heading("8. Live counter values")
    status, _, metrics_text = get(f"{BACKEND}/actuator/prometheus")
    if isinstance(metrics_text, str):
        for line in metrics_text.splitlines():
            if line.startswith("fuel_database_up ") or line.startswith("fuel_data_age_seconds "):
                report.note(f"`{line}`")

    report.heading("9. The operator console's filters")
    status, stdout, stderr = run_script("verify_filters.py")
    for line in (stdout + stderr).splitlines():
        if line.strip():
            report.note(line.strip())
    report.check("the filter verification script passes",
                 status == 0,
                 "scripts/verify_filters.py — 13 checks against the live stack")

    report.write(REPORT)
    print(f"{report.passed} passed, {report.failed} failed")
    print(f"report written to {REPORT}")
    return 0 if report.failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
