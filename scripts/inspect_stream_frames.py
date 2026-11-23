"""Prints the raw SSE bytes the simulator sends when the world moves.

The stream reader in the backend needs to know the exact frame format: the
event name, the data payload, and how a tick looks. This runs the simulator in
RUNNING mode for a short window and dumps every byte it receives.
"""

import json
import time
import urllib.request

SIM = "http://localhost:8000"


def post(path, body=None):
    """Calls an admin endpoint. A POST with no body still needs the POST method."""
    data = json.dumps(body).encode() if body is not None else b""
    request = urllib.request.Request(f"{SIM}{path}", data=data, method="POST")
    request.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(request, timeout=30) as response:
        raw = response.read().decode("utf-8")
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return raw


print("=" * 78)
print("STARTING THE SIMULATOR AND READING THE STREAM")
print("=" * 78)

post("/admin/faults/clear")
status = post("/admin/run")
print(f"simulator mode: {status}")

request = urllib.request.Request(f"{SIM}/v1/stream", method="GET")
deadline = time.monotonic() + 25
frames = []
try:
    with urllib.request.urlopen(request, timeout=25) as response:
        print(f"status={response.status} content-type={response.headers.get('Content-Type')}")
        while time.monotonic() < deadline:
            line = response.readline()
            if not line:
                break
            text = line.decode("utf-8", "replace").rstrip("\n")
            if text:
                frames.append(text)
                print(f"  {text}")
except Exception as error:  # noqa: BLE001 - this is the measurement
    print(f"stream ended: {type(error).__name__}: {error}")

print()
print("=" * 78)
print(f"FRAME SUMMARY — {len(frames)} non-empty lines")
print("=" * 78)
kinds = {}
for frame in frames:
    if frame.startswith(":"):
        kinds[frame.split(":")[0]] = kinds.get(frame.split(":")[0], 0) + 1
    elif ":" in frame:
        name = frame.split(":", 1)[0]
        kinds[f"event:{name}"] = kinds.get(f"event:{name}", 0) + 1
    else:
        kinds["bare"] = kinds.get("bare", 0) + 1
for kind, count in sorted(kinds.items()):
    print(f"  {kind:<30} {count}")

print()
print("=" * 78)
print("STOPPING THE SIMULATOR")
print("=" * 78)
post("/admin/pause")
print("paused")
