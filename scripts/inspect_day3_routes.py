"""Prints the status of every Day 3 route, through the frontend proxy.

The operator opens the console on port 5173, so the routes must be reachable
through that origin and not only on the backend port.
"""

import json
import urllib.error
import urllib.request

ROUTES = [
    ("GET", "/api/proposals", None),
    ("GET", "/api/shipments", None),
    ("GET", "/api/risk", None),
    ("GET", "/api/plan", None),
    ("GET", "/api/stations", None),
    ("GET", "/api/depots", None),
]

for origin, label in (("http://localhost:5173", "frontend proxy"),
                      ("http://localhost:8080", "backend direct")):
    print(f"=== {label} ({origin}) ===")
    for method, path, body in ROUTES:
        request = urllib.request.Request(f"{origin}{path}", method=method)
        try:
            with urllib.request.urlopen(request, timeout=20) as response:
                payload = response.read().decode("utf-8")
                print(f"  {method:<4} {path:<20} {response.status}  bytes={len(payload)}")
        except urllib.error.HTTPError as error:
            payload = error.read().decode("utf-8", errors="replace")
            print(f"  {method:<4} {path:<20} {error.code}  {payload[:160]}")
        except Exception as error:  # noqa: BLE001 - diagnostics only
            print(f"  {method:<4} {path:<20} ERROR {error}")
    print()

print("=== one proposal body ===")
try:
    with urllib.request.urlopen("http://localhost:5173/api/proposals", timeout=20) as response:
        proposals = json.loads(response.read().decode("utf-8"))
    print(f"count={len(proposals)}")
    if proposals:
        print(json.dumps(proposals[0], indent=2)[:1200])
except Exception as error:  # noqa: BLE001 - diagnostics only
    print(f"ERROR {error}")
