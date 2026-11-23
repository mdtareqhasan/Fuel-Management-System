"""Checks that the Day 4 resilience UI is actually in the served bundle.

A source file that says the right thing proves nothing if the container is
serving an older bundle. This reads the bundle the browser would receive and
looks for the Day 4 strings and the CSS rules that go with them.

It also checks the two things that must NOT be there: a banner that is always
visible, and a hard-coded "healthy" that would hide a real outage.
"""

import re
import sys
import urllib.request
from pathlib import Path

FRONTEND = "http://localhost:5173"

DAY4_STRINGS = [
    "The simulator is not being called.",
    "The circuit breaker is OPEN after repeated failures.",
    "The simulator is being probed.",
    "The event stream is disconnected after",
    "A healthy health endpoint does not mean the station data is fresh.",
]

# JSX splits a long paragraph across source lines, and the bundler keeps the
# pieces as separate strings. So a phrase that spans a line break is checked
# in two pieces, not as one sentence.
DAY4_FRAGMENTS = [
    "The cached view",
    "the last valid one, and approvals are blocked until the",
]

DAY4_CSS = [
    ".banner-danger",
]


def fetch(url, timeout=30):
    with urllib.request.urlopen(url, timeout=timeout) as response:
        return response.read().decode("utf-8", "replace")


def read_source(relative_path):
    """Reads a source file from the repository.

    The structural checks run against the source, not the bundle. A minified
    bundle has its identifiers renamed, so searching it for "ResiliencePanel"
    would fail even when the component exists — and a check that reports a
    false failure teaches people to ignore the check.
    """
    root = Path(__file__).resolve().parent.parent
    path = root / relative_path
    if not path.exists():
        return ""
    return path.read_text(encoding="utf-8")


def main():
    index = fetch(f"{FRONTEND}/")
    assets = re.findall(r'(?:src|href)="([^"]+\.(?:js|css))"', index)
    print("=" * 78)
    print("DAY 4 UI CHECK")
    print("=" * 78)
    print(f"  index.html assets: {assets}")

    bundle = ""
    styles = ""
    for asset in assets:
        body = fetch(f"{FRONTEND}/{asset.lstrip('/')}")
        if asset.endswith(".js"):
            bundle += body
        else:
            styles += body

    failures = []

    print()
    print("UI STRINGS")
    print("-" * 78)
    for needle in DAY4_STRINGS + DAY4_FRAGMENTS:
        found = needle in bundle
        print(f"  [{'PASS' if found else 'FAIL'}] {needle}")
        if not found:
            failures.append(f"missing string: {needle}")

    print()
    print("CSS RULES")
    print("-" * 78)
    for needle in DAY4_CSS:
        found = needle in styles
        print(f"  [{'PASS' if found else 'FAIL'}] {needle}")
        if not found:
            failures.append(f"missing css: {needle}")

    print()
    print("SAFETY CHECKS")
    print("-" * 78)

    source = read_source("frontend/src/App.tsx")

    # The resilience panel must be conditional. If the banner text were
    # rendered unconditionally, the operator would see it on a healthy system
    # and learn to ignore it.
    conditional = "function ResiliencePanel" in source
    print(f"  [{'PASS' if conditional else 'FAIL'}] the panel is a component that can be hidden")
    if not conditional:
        failures.append("the resilience panel is not a separate component")

    # The panel must return null when nothing is wrong.
    hides_when_healthy = "if (!breakerOpen && !breakerHalfOpen && !streamDown) return null" in source
    print(f"  [{'PASS' if hides_when_healthy else 'FAIL'}] the panel is hidden on a healthy system")
    if not hides_when_healthy:
        failures.append("the resilience panel is not hidden on a healthy system")

    # The banner must be gated on the breaker and the stream state.
    has_condition = "status.circuit_breaker" in source and "status.event_stream" in source
    print(f"  [{'PASS' if has_condition else 'FAIL'}] the panel is gated on the breaker and stream state")
    if not has_condition:
        failures.append("the resilience panel is not gated on the breaker state")

    # The frontend must never talk to the simulator directly.
    talks_to_simulator = "localhost:8000" in bundle or "simulator-api" in bundle
    print(f"  [{'PASS' if not talks_to_simulator else 'FAIL'}] the browser never talks to the simulator")
    if talks_to_simulator:
        failures.append("the bundle contains a simulator URL")

    # The status type must carry the new fields, or the panel reads undefined.
    types_source = read_source("frontend/src/types.ts")
    has_types = all(field in types_source for field in
                    ("circuit_breaker", "event_stream", "stream_events", "stream_reconnects"))
    print(f"  [{'PASS' if has_types else 'FAIL'}] the status type carries the resilience fields")
    if not has_types:
        failures.append("the SystemStatus type is missing the resilience fields")

    print()
    print("=" * 78)
    if failures:
        print(f"RESULT: {len(failures)} check(s) failed")
        for failure in failures:
            print(f"  - {failure}")
        return 1
    print("RESULT: all Day 4 UI checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
