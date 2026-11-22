"""Checks that the served frontend bundle contains the Day 3 UI.

The Day 3 decision panel is new in the frontend bundle. If the container is
serving an old image, the panel is silently missing from the operator's screen
— a failure that no backend test would catch. So this checks the bytes that
the browser actually receives.
"""

import re
import sys
import urllib.request

FRONTEND = "http://localhost:5173"

with urllib.request.urlopen(FRONTEND, timeout=30) as response:
    html = response.read().decode("utf-8")

match = re.search(r'src="([^"]+\.js)"', html)
if not match:
    print("could not find the bundle reference in index.html")
    sys.exit(1)

bundle_path = match.group(1)
with urllib.request.urlopen(f"{FRONTEND}{bundle_path}", timeout=30) as response:
    bundle = response.read().decode("utf-8")

print(f"bundle: {bundle_path}  ({len(bundle):,} bytes)")
print()

# The strings that only exist in the Day 3 decision panel.
expectations = [
    ("Routes", "the simulator route inventory section"),
    ("Max shipment", "the route shipment-capacity column"),
    ("Decisions", "the decision section heading"),
    ("Waiting for review", "the pending review list"),
    ("Shipment ledger", "the ledger section"),
    ("Maximum to ship", "the review dialog's upper-bound label"),
    ("Outcome unknown", "the unknown-outcome wording"),
    ("Re-review required", "the changed-proposal wording"),
    ("Approved with a reduction", "the reduced-approval wording"),
    ("simulator_status", "the simulator status column"),
    ("command_status", "the application command status column"),
    ("Approve up to", "the per-line quantity column"),
]

missing = []
for needle, description in expectations:
    present = needle in bundle
    print(f"[{'OK  ' if present else 'MISS'}] {description:<45} ({needle})")
    if not present:
        missing.append(needle)

print()
if missing:
    print(f"MISSING {len(missing)} of {len(expectations)} Day 3 UI strings — the frontend image is stale")
    sys.exit(1)

print(f"all {len(expectations)} Day 3 UI strings are present in the served bundle")
