"""Print the backend's detector status, notes and flags for one tick."""

import json
import sys
import urllib.request

BACKEND = sys.argv[1] if len(sys.argv) > 1 else "http://localhost:8080"
SIM = "http://localhost:8000"


def get(url):
    with urllib.request.urlopen(url, timeout=60) as response:
        return json.loads(response.read().decode("utf-8"))


risk = get(f"{BACKEND}/api/risk")
instance = get(f"{SIM}/v1/instance")

print(f"simulator tick={instance['tick']} status={instance['status']}")
print(f"backend   tick={risk['tick']} detector_status={risk['detector_status']} "
      f"flags={len(risk['anomaly_flags'])}")
print()
print("detector notes:")
for note in risk["detector_notes"]:
    print(f"  {note}")
print()
print("flags:")
for flag in risk["anomaly_flags"]:
    print(f"  {flag['severity']} {flag['station_id']} {flag['fuel']} "
          f"ratio={flag['ratio']} at tick {flag['detected_at_tick']}")
    print(f"      {flag['reason']}")
