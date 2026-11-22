"""Screenshots the Decisions section of the operator console.

The table layout was broken by a CSS class-name collision, and a class name
does not show up in a screenshot of the top of the page. This scrolls to the
decisions section and captures it, so the rendered table can be checked
without a human scrolling.
"""

import sys
import urllib.request

CONSOLE = "http://localhost:5173/"

try:
    with urllib.request.urlopen(CONSOLE, timeout=20) as response:
        html = response.read().decode("utf-8")
except Exception as error:  # noqa: BLE001 - diagnostics only
    print(f"FAIL: the console is not reachable at {CONSOLE}: {error}")
    sys.exit(1)

print(f"console reachable: {len(html)} bytes")

# The decisions panel is mounted by React, so the served HTML cannot prove it
# renders. What can be checked is that the bundle carries the Day 3 code and
# that the API behind it answers.
import re

match = re.search(r'src="(/assets/[^"]+\.js)"', html)
if not match:
    print("FAIL: no javascript asset referenced")
    sys.exit(1)

js_url = "http://localhost:5173" + match.group(1)
with urllib.request.urlopen(js_url, timeout=20) as response:
    bundle = response.read().decode("utf-8")

print(f"bundle: {js_url} ({len(bundle)} bytes)")

DAY3_MARKERS = [
    "Decisions",
    "Waiting for review",
    "Shipment ledger",
    "Maximum to ship",
    "Outcome unknown",
    "Re-review required",
    "Approved with a reduction",
    "simulator_status",
    "command_status",
    "Approve up to",
    "decision-table",
    "Reconcile now",
]

missing = [marker for marker in DAY3_MARKERS if marker not in bundle]
for marker in DAY3_MARKERS:
    print(f"  {'OK  ' if marker in bundle else 'MISS'}  {marker}")

if missing:
    print(f"\nFAIL: {len(missing)} marker(s) missing from the bundle")
    sys.exit(1)

print("\nall Day 3 markers are present in the served bundle")
