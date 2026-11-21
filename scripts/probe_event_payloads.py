"""Try event injections and print the server's error detail for each attempt.

Used to find the parameter names the simulator actually accepts for each event type,
instead of guessing from the PDF.
"""

import json
import urllib.error
import urllib.request

SIM = "http://localhost:8000"


def attempt(label, body):
    request = urllib.request.Request(
        f"{SIM}/admin/events", data=json.dumps(body).encode(), method="POST",
        headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            print(f"  {label}: OK {response.read().decode()[:300]}")
            return True
    except urllib.error.HTTPError as error:
        detail = error.read().decode()[:400]
        print(f"  {label}: {error.code} {detail}")
        return False


with urllib.request.urlopen(f"{SIM}/v1/instance", timeout=30) as response:
    tick = json.loads(response.read().decode())["tick"]

print(f"current tick={tick}")
print()
print("demand_spike attempts:")
attempt("region_ids list", {
    "type": "demand_spike", "start_tick": tick, "duration_ticks": 24,
    "parameters": {"region_ids": ["region-dhaka"], "multiplier": 1.8}})
attempt("regions list", {
    "type": "demand_spike", "start_tick": tick, "duration_ticks": 24,
    "parameters": {"regions": ["region-dhaka"], "multiplier": 1.8}})
attempt("region_id single", {
    "type": "demand_spike", "start_tick": tick, "duration_ticks": 24,
    "parameters": {"region_id": "region-dhaka", "multiplier": 1.8}})
attempt("no parameters", {
    "type": "demand_spike", "start_tick": tick, "duration_ticks": 24})

print()
print("other event types, minimal payload:")
for event_type in ("shipment_delay", "route_disruption", "station_outage",
                   "depot_constraint", "supply_shortfall"):
    attempt(event_type, {"type": event_type, "start_tick": tick, "duration_ticks": 24})
