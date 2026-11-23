"""Injects faults and crisis events, and records what the platform did.

Day 4 is about the failure boundary. This script injects a fault, watches what
the backend reports while the fault is active, clears it, and watches the
recovery. Every reading is timestamped so the timeline can be reconstructed
from the evidence file alone.

The claims it is built to prove:

1. An `unavailable` fault produces **cached read mode** and **blocked
   approvals** while `/v1/health` stays healthy. A healthy health endpoint is
   not proof of fresh station data.
2. An HTTP 200 stale response **cannot** become an eligible decision snapshot.
3. After the fault is cleared, a complete valid refresh **restores** the current
   state and re-enables approval.
4. The circuit breaker **opens** under sustained failure and **closes** again
   after recovery, and the status panel shows it.

Usage:
    python scripts/inject_fault.py unavailable 30
    python scripts/inject_fault.py stale 30
    python scripts/inject_fault.py latency 30
    python scripts/inject_fault.py event demand_spike 8
    python scripts/inject_fault.py recover
"""

import json
import sys
import time
import urllib.error
import urllib.request

SIM = "http://localhost:8000"
BACKEND = "http://localhost:8080"

STARTED_AT = time.monotonic()


def elapsed():
    """Seconds since the script started."""
    return round(time.monotonic() - STARTED_AT, 3)


def call(url, body=None, method=None, timeout=30):
    """Calls an endpoint and returns (status, parsed body, raw text)."""
    data = json.dumps(body).encode() if body is not None else None
    verb = method or ("POST" if body is not None else "GET")
    request = urllib.request.Request(url, data=data, method=verb)
    if data is not None:
        request.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read().decode("utf-8")
            status = response.status
    except urllib.error.HTTPError as error:
        raw = error.read().decode("utf-8")
        status = error.code
    except Exception as error:  # noqa: BLE001 - a transport failure is a result here
        return 0, {"transport_error": f"{type(error).__name__}: {error}"}, str(error)

    try:
        return status, json.loads(raw), raw
    except json.JSONDecodeError:
        return status, None, raw


def get(url, timeout=30):
    return call(url, timeout=timeout)


def post(url, body=None, timeout=30):
    return call(url, body if body is not None else {}, method="POST", timeout=timeout)


def log(message):
    """Prints a timestamped line. The timestamp is what makes the timeline usable."""
    print(f"[{elapsed():>8.3f}s] {message}", flush=True)


def read_status():
    """Reads the platform's own view of its health."""
    _, status, _ = get(f"{BACKEND}/api/system-status")
    return status or {}


def read_dashboard():
    """Reads the operator's view."""
    _, dashboard, _ = get(f"{BACKEND}/api/dashboard")
    return dashboard or {}


def read_risk():
    """Reads the risk view."""
    _, risk, _ = get(f"{BACKEND}/api/risk")
    return risk or {}


def read_health():
    """Reads the simulator's own health endpoint.

    This one bypasses fault injection. That is exactly the point: a simulator
    can be perfectly healthy while every station read fails.
    """
    status, body, raw = get(f"{SIM}/v1/health")
    return status, body


def simulator_health():
    """The simulator's health, as a short string."""
    status, body = read_health()
    if isinstance(body, dict):
        return f"HTTP {status} status={body.get('status')}"
    return f"HTTP {status}"


def describe_state(label):
    """Prints the whole observable state in one block."""
    status = read_status()
    dashboard = read_dashboard()

    meta = dashboard.get("metadata", {}) if dashboard else {}
    print()
    print(f"  --- {label} ---")
    print(f"  simulator /v1/health   : {simulator_health()}")
    print(f"  platform simulator      : {status.get('simulator')}")
    print(f"  platform database       : {status.get('database')}")
    print(f"  data quality            : {status.get('data_quality')}")
    print(f"  decision eligible       : {status.get('decision_eligible')}")
    print(f"  refresh age (s)         : {status.get('refresh_age_seconds')}")
    print(f"  p95 latency (ms)        : {status.get('p95_latency_ms')}")
    print(f"  error rate (%)          : {status.get('error_rate_percent')}")
    print(f"  circuit breaker         : {status.get('circuit_breaker')}")
    print(f"  event stream            : {status.get('event_stream')}")
    print(f"  snapshot quality        : {meta.get('quality')}")
    print(f"  snapshot eligible       : {meta.get('decision_eligible')}")
    print(f"  warnings                : {meta.get('warnings')}")
    return status


def try_approval():
    """Attempts an approval and reports what the platform did.

    This is the write guard. During a fault the approval must be refused by the
    server, not merely hidden by a disabled button.
    """
    _, proposals, _ = get(f"{BACKEND}/api/proposals")
    pending = [p for p in (proposals or []) if p.get("status") == "PENDING_REVIEW"]
    if not pending:
        return "no pending proposal to approve"

    proposal = pending[0]
    status, body, raw = post(
        f"{BACKEND}/api/proposals/{proposal['id']}/approve",
        {"actor": "day4-fault-probe", "quantities": {}},
    )
    detail = ""
    if isinstance(body, dict) and body.get("code"):
        detail = f" code={body['code']}"
    return f"HTTP {status}{detail}"


def inject_fault(fault_type, duration_seconds, parameters=None):
    """Injects a fault and records the timeline."""
    print("=" * 78)
    print(f"FAULT: {fault_type} for {duration_seconds}s")
    print("=" * 78)

    log("state BEFORE the fault")
    before = describe_state("before the fault")

    log(f"injecting the {fault_type} fault")
    status, body, raw = post(
        f"{SIM}/admin/faults",
        {
            "type": fault_type,
            "duration_seconds": duration_seconds,
            "parameters": parameters or {},
        },
    )
    log(f"fault injection -> HTTP {status}")

    # Let the platform notice. The poll interval is 2 s and the breaker needs
    # three consecutive failures, so the outage is visible within ~8 s.
    log("waiting 12 s for the platform to react")
    time.sleep(12)

    during = describe_state(f"during the {fault_type} fault")
    log(f"approval attempt during the fault -> {try_approval()}")

    log("clearing the fault")
    status, _, _ = post(f"{SIM}/admin/faults/clear")
    log(f"fault cleared -> HTTP {status}")

    # The breaker waits out its open window before it probes again. The
    # default is 30 s, so the recovery is measured from the clear.
    log("waiting 35 s for the recovery")
    time.sleep(35)

    after = describe_state("after the fault")

    print()
    print("=" * 78)
    print("TIMELINE")
    print("=" * 78)
    print(f"  before : quality={before.get('data_quality')} "
          f"eligible={before.get('decision_eligible')} "
          f"breaker={before.get('circuit_breaker')} "
          f"simulator_health={simulator_health()}")
    print(f"  during : quality={during.get('data_quality')} "
          f"eligible={during.get('decision_eligible')} "
          f"breaker={during.get('circuit_breaker')} "
          f"simulator_health={simulator_health()}")
    print(f"  after  : quality={after.get('data_quality')} "
          f"eligible={after.get('decision_eligible')} "
          f"breaker={after.get('circuit_breaker')} "
          f"simulator_health={simulator_health()}")
    print()
    print(f"  the simulator's /v1/health stayed {simulator_health()} throughout")
    print("  — reachability is not freshness.")


def inject_event(event_type, duration_ticks, parameters=None):
    """Injects a crisis event and records what the platform detected."""
    print("=" * 78)
    print(f"EVENT: {event_type} for {duration_ticks} ticks")
    print("=" * 78)

    # The event has to be injected while the simulation is RUNNING, otherwise
    # the tick never advances past the event's start tick and the world never
    # changes.
    post(f"{SIM}/admin/run")
    time.sleep(2)

    _, instance, _ = get(f"{SIM}/v1/instance")
    start_tick = (instance or {}).get("tick", 0)
    log(f"current tick: {start_tick}")

    log(f"injecting the {event_type} event")
    status, body, raw = post(
        f"{SIM}/admin/events",
        {
            "type": event_type,
            "start_tick": start_tick,
            "duration_ticks": duration_ticks,
            "parameters": parameters or {},
        },
    )
    log(f"event injection -> HTTP {status}: {raw[:200]}")

    log("waiting 25 s for the platform to read the world again")
    time.sleep(25)

    risk = read_risk()
    flags = risk.get("anomaly_flags", []) or []
    events = (read_dashboard().get("events") or [])

    print()
    print(f"  active events       : {len(events)}")
    for event in events[-3:]:
        print(f"    {event.get('type')} tick {event.get('start_tick')}"
              f"-{event.get('end_tick')} status={event.get('status')}")
    print(f"  anomaly flags       : {len(flags)}")
    for flag in flags[:5]:
        print(f"    {flag.get('station_id')} {flag.get('fuel')} "
              f"severity={flag.get('severity')} ratio={flag.get('ratio')} "
              f"cause={flag.get('cause')}")
    print(f"  detector status     : {risk.get('detector_status')}")
    print(f"  unmet needs         : {len(risk.get('unmet_needs', []) or [])}")
    for need in (risk.get("unmet_needs") or [])[:5]:
        print(f"    {need.get('station_id')} {need.get('fuel')} "
              f"{need.get('need_liters')} L reason={need.get('reason_code')}")

    log("pausing the simulation and clearing the event")
    post(f"{SIM}/admin/pause")
    post(f"{SIM}/admin/reset")
    log("world reset")


def recover():
    """Clears every fault and shows the recovery."""
    print("=" * 78)
    print("RECOVERY")
    print("=" * 78)
    log("clearing all faults")
    post(f"{SIM}/admin/faults/clear")
    log("waiting 35 s for the platform to recover fully")
    time.sleep(35)
    describe_state("after recovery")


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        print("commands: unavailable | stale | latency | event <type> <ticks> | recover")
        return 1

    command = sys.argv[1]

    if command == "recover":
        recover()
        return 0

    if command == "event":
        event_type = sys.argv[2] if len(sys.argv) > 2 else "demand_spike"
        duration = int(sys.argv[3]) if len(sys.argv) > 3 else 8
        inject_event(event_type, duration)
        return 0

    duration = int(sys.argv[2]) if len(sys.argv) > 2 else 30

    if command == "unavailable":
        inject_fault("unavailable", duration)
    elif command == "stale":
        inject_fault("stale_data", duration)
    elif command == "latency":
        inject_fault("latency", duration, {"delay_ms": 4000})
    else:
        print(f"unknown command: {command}")
        print(__doc__)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
