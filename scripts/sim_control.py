"""Drives the simulator's admin API from the command line.

Used to inject faults, step the clock and inject crisis events during
verification. A script is used rather than curl because the JSON bodies need
quoting that is easy to get wrong in a shell.

Usage:
    python scripts/sim_control.py faults
    python scripts/sim_control.py inject-fault stale_data 30
    python scripts/sim_control.py clear-faults
    python scripts/sim_control.py step 5
    python scripts/sim_control.py run
    python scripts/sim_control.py pause
    python scripts/sim_control.py instance
    python scripts/sim_control.py event demand_spike 0 4 {"multiplier": 1.8}
"""

import json
import sys
import urllib.error
import urllib.request

BASE = "http://localhost:8000"


def call(method: str, path: str, body: dict | None = None) -> tuple[int, str]:
    url = f"{BASE}{path}"
    data = json.dumps(body).encode("utf-8") if body is not None else None
    request = urllib.request.Request(url, data=data, method=method)
    if data is not None:
        request.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            return response.status, response.read().decode("utf-8")
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode("utf-8")
    except Exception as error:  # noqa: BLE001 - the message is the useful part
        return 0, str(error)


def print_result(label: str, status: int, body: str) -> None:
    print(f"[{label}] HTTP {status}")
    if body:
        try:
            print(json.dumps(json.loads(body), indent=2))
        except json.JSONDecodeError:
            print(body)


def main(argv: list[str]) -> int:
    if not argv:
        print(__doc__)
        return 1

    command = argv[0]

    if command == "faults":
        status, body = call("GET", "/admin/faults")
        print_result("faults", status, body)
    elif command == "inject-fault":
        fault_type = argv[1]
        duration = int(argv[2]) if len(argv) > 2 else 30
        status, body = call("POST", "/admin/faults",
                            {"type": fault_type, "duration_seconds": duration})
        print_result(f"inject {fault_type}", status, body)
    elif command == "clear-faults":
        status, body = call("POST", "/admin/faults/clear")
        print_result("clear-faults", status, body)
    elif command == "step":
        ticks = int(argv[1]) if len(argv) > 1 else 1
        status, body = call("POST", "/admin/step", {"ticks": ticks})
        print_result(f"step {ticks}", status, body)
    elif command == "run":
        status, body = call("POST", "/admin/run")
        print_result("run", status, body)
    elif command == "pause":
        status, body = call("POST", "/admin/pause")
        print_result("pause", status, body)
    elif command == "toggle":
        status, body = call("POST", "/admin/toggle")
        print_result("toggle", status, body)
    elif command == "reset":
        status, body = call("POST", "/admin/reset")
        print_result("reset", status, body)
    elif command == "instance":
        status, body = call("GET", "/v1/instance")
        print_result("instance", status, body)
    elif command == "events":
        status, body = call("GET", "/admin/events")
        print_result("events", status, body)
    elif command == "event":
        event_type = argv[1]
        start_tick = int(argv[2])
        duration_ticks = int(argv[3])
        parameters = json.loads(argv[4]) if len(argv) > 4 else {}
        status, body = call("POST", "/admin/events", {
            "type": event_type,
            "start_tick": start_tick,
            "duration_ticks": duration_ticks,
            "parameters": parameters,
        })
        print_result(f"event {event_type}", status, body)
    elif command == "stats":
        status, body = call("GET", "/admin/stats")
        print_result("stats", status, body)
    else:
        print(f"unknown command: {command}")
        print(__doc__)
        return 1

    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
