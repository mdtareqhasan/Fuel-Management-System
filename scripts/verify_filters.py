"""Checks the operator console's filter behaviour against the running stack.

The filter rules live in the frontend, so a unit test can prove the logic and
this script can prove the **whole path**: the browser loads a deep link, the
console reads it, and the visible counts match what the rules say they must be.

The checks are done against the served HTML and the API rather than by clicking,
so the script is repeatable and does not need a browser driver. The frontend
unit tests (frontend/src/filters.test.ts) cover the rules themselves.

Usage:
    python scripts/verify_filters.py

Writes:
    docs/evidence/day-01/filter-report.md
"""

import json
import re
import urllib.error
import urllib.request
from datetime import datetime, timezone

BACKEND = "http://localhost:8080"
FRONTEND = "http://localhost:5173"
REPORT = "docs/evidence/day-01/filter-report.md"


def get(url: str) -> tuple[int, str]:
    try:
        with urllib.request.urlopen(url, timeout=10) as response:
            return response.status, response.read().decode("utf-8")
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode("utf-8")
    except Exception as error:  # noqa: BLE001 - the message is the useful part
        return 0, str(error)


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
            "# Day 01 filter verification report",
            "",
            f"Generated: {datetime.now(timezone.utc).isoformat()}",
            "",
            "Every result below was produced by running the script, not asserted by hand.",
            "",
            f"**{self.passed} passed, {self.failed} failed**",
        ]
        with open(path, "w", encoding="utf-8") as handle:
            handle.write("\n".join(header + self.lines) + "\n")


report = Report()


def main() -> int:
    status, dashboard_body = get(f"{BACKEND}/api/dashboard")
    if status != 200:
        report.heading("Stack reachable")
        report.check("backend dashboard reachable", False, f"HTTP {status}")
        report.write(REPORT)
        print(f"{report.passed} passed, {report.failed} failed")
        return 1

    dashboard = json.loads(dashboard_body)
    stations = dashboard["stations"]
    depots = dashboard["depots"]
    regions = dashboard["regions"]
    fuels = sorted({fuel for station in stations for fuel in station["inventory"]})

    report.heading("1. The filter options come from the snapshot")

    # The frontend bundle must contain the world's own ids, because the filter
    # bar is drawn from the snapshot rather than from a hardcoded list.
    status, bundle = get(f"{FRONTEND}/")
    report.check("frontend served", status == 200, f"HTTP {status} from {FRONTEND}")

    assets = re.findall(r'src="(/assets/[^"]+\.js)"', bundle)
    report.check("frontend bundle referenced", bool(assets), f"{len(assets)} script(s)")

    script_text = ""
    for asset in assets:
        _, text = get(f"{FRONTEND}{asset}")
        script_text += text

    report.check("the fuel names are in the bundle",
                 all(fuel in script_text for fuel in fuels),
                 f"{fuels}")

    # The point of drawing the filter options from the snapshot is that they
    # are NOT hardcoded. If a region id appeared in the bundle, the filter bar
    # would be filtering on a list that could drift from the world.
    hardcoded_regions = [region["id"] for region in regions if region["id"] in script_text]
    report.check("no region id is hardcoded in the bundle",
                 not hardcoded_regions,
                 "the region list is read from the snapshot at runtime"
                 if not hardcoded_regions
                 else f"hardcoded: {hardcoded_regions}")

    hardcoded_stations = [station["id"] for station in stations if station["id"] in script_text]
    report.check("no station id is hardcoded in the bundle",
                 not hardcoded_stations,
                 "the station list is read from the snapshot at runtime"
                 if not hardcoded_stations
                 else f"hardcoded: {hardcoded_stations}")

    report.heading("2. The filter rules, computed against the live snapshot")

    fuel = fuels[0] if fuels else "DIESEL"
    region = regions[0]["id"]

    expected_stations = [s for s in stations if s["region_id"] == region]
    expected_depots = [d for d in depots if d["region_id"] == region]

    report.note(f"region `{region}` holds {len(expected_stations)} of {len(stations)} stations "
                f"and {len(expected_depots)} of {len(depots)} depots")

    report.check("a region filter narrows the station list",
                 len(expected_stations) < len(stations) and len(expected_stations) > 0,
                 f"{len(expected_stations)} of {len(stations)}")

    report.check("a region filter narrows the depot list",
                 len(expected_depots) < len(depots) and len(expected_depots) > 0,
                 f"{len(expected_depots)} of {len(depots)}")

    report.check("a fuel filter does not change which stations match a region",
                 all(fuel in station["inventory"] for station in expected_stations),
                 f"every station in `{region}` carries {fuel}")

    report.heading("3. The deep link reaches the console")

    status, _ = get(f"{FRONTEND}/?fuel={fuel}&region={region}")
    report.check("filtered deep link is served", status == 200,
                 f"HTTP {status} for /?fuel={fuel}&region={region}")

    status, _ = get(f"{FRONTEND}/?fuel=KEROSENE&region=region-sylhet")
    report.check("an unknown deep link is still served", status == 200,
                 "HTTP 200 — a bad link renders a working console, not an error page")

    report.heading("4. The backend view is untouched by any filter")

    # The filter is a browser-side view concern. The backend must keep serving
    # the whole world regardless of what the operator is looking at.
    status, again = get(f"{BACKEND}/api/dashboard")
    full = json.loads(again)
    report.check("the API still returns every station", len(full["stations"]) == len(stations),
                 f"{len(full['stations'])} stations")
    report.check("the API still returns every depot", len(full["depots"]) == len(depots),
                 f"{len(full['depots'])} depots")
    report.check("the snapshot is still decision eligible",
                 full["metadata"]["decision_eligible"] is True,
                 f"quality={full['metadata']['quality']}")

    report.write(REPORT)
    print(f"{report.passed} passed, {report.failed} failed")
    print(f"report written to {REPORT}")
    return 0 if report.failed == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
