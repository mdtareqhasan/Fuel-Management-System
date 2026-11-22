"""Checks that the served frontend CSS carries the Day 3 table rules.

The bug this guards against: the Day 3 tables used the class name ``grid``,
which already existed in this stylesheet as a CSS *grid container* for cards.
The two rules fought and the table rendered as a grid.
"""

import re
import urllib.request

CSS_URL = "http://localhost:5173/assets/index-Eq5v1d4E.css"

with urllib.request.urlopen("http://localhost:5173/", timeout=20) as response:
    html = response.read().decode("utf-8")

match = re.search(r'href="(/assets/[^"]+\.css)"', html)
if not match:
    print("FAIL: no css asset referenced by index.html")
    raise SystemExit(1)

css_url = "http://localhost:5173" + match.group(1)
with urllib.request.urlopen(css_url, timeout=20) as response:
    css = response.read().decode("utf-8")

print(f"css asset: {css_url}  bytes={len(css)}")
print()

checks = [
    ("decision-table rule present", r"\.decision-table\b"),
    ("decision-table th/td rule present", r"\.decision-table th"),
    ("decision-table hover rule present", r"\.decision-table tbody tr:hover"),
]

failures = 0
for label, pattern in checks:
    found = re.search(pattern, css) is not None
    print(f"  {'PASS' if found else 'FAIL'}  {label}")
    if not found:
        failures += 1

# The card grid must still be a grid container, and must not carry table rules.
grid_container = re.search(r"\.grid\{[^}]*display:grid", css) is not None
print(f"  {'PASS' if grid_container else 'FAIL'}  .grid is still display:grid (card layout)")
if not grid_container:
    failures += 1

# A table rule leaking onto .grid is what broke the layout.
grid_has_table = re.search(r"\.grid th|\.grid td", css) is not None
print(f"  {'PASS' if not grid_has_table else 'FAIL'}  no .grid th/.grid td table rules")
if grid_has_table:
    failures += 1

print()
print("RESULT:", "all checks passed" if failures == 0 else f"{failures} check(s) failed")
raise SystemExit(1 if failures else 0)
