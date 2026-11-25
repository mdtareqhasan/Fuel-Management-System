"""Traces what the platform does while the simulator is SLOW.

This is the counterweight to `trace_breaker_during_fault.py`, which traces the
`unavailable` fault. The `unavailable` fault answers quickly with HTTP 503, so
the platform's reaction is easy to sample. The `latency` fault is different: the
simulator accepts the connection and then takes six seconds to answer, so the
platform's reaction is spread over several poll cycles and a single sample can
land on either side of the transition.

That is exactly why this script samples **every second** and prints the whole
timeline, instead of taking three samples and calling it a result.

The claim under test is the one the Day 4 documentation makes:

1. A slow simulator makes the view go INVALID and blocks approvals — the
   platform is honest that it cannot see the world.
2. The circuit breaker stays CLOSED — a slow simulator is not a dead one, and
   opening the breaker would take the whole integration offline for 30 seconds
   to avoid one slow answer.

Usage:
    python scripts/trace_latency_fault.py
"""

import json
import time
import urllib.error
import urllib.request

SIM = "http://localhost:8000"
BACKEND = "http://localhost:8080"


def get_json(url, timeout=20):
    """GETs JSON, returning (parsed_or_None, raw_text)."""
    try:
        with urllib.request.urlopen(url, timeout=timeout) as response:
            raw = response.read().decode("utf-8")
    except urllib.error.HTTPError as error:
        return None, f"HTTP {error.code}"
    except Exception as error:  # noqa: BLE001
        return None, f"{type(error).__name__}"
    try:
        return json.loads(raw), raw
    except json.JSONDecodeError:
        return None, raw[:120]


def post(url, body=None, timeout=30):
    """POSTs JSON and returns (status, raw)."""
    data = json.dumps(body if body is not None else {}).encode("utf-8")
    request = urllib.request.Request(url, data=data, method="POST")
    request.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, response.read().decode("utf-8")
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode("utf-8")
    except Exception as error:  # noqa: BLE001
        return 0, f"{type(error).__name__}: {error}"


def metric(metrics, name):
    """The value of one Prometheus sample, or None when it is absent."""
    for line in metrics.splitlines():
        if line.startswith(name) and not line.startswith("#"):
            return line.rpartition(" ")[2]
    return None


def read_metrics():
    """The raw Prometheus exposition text."""
    try:
        with urllib.request.urlopen(f"{BACKEND}/actuator/prometheus", timeout=20) as response:
            return response.read().decode("utf-8")
    except Exception:  # noqa: BLE001
        return ""


def line_of(parsed, raw):
    """One compact status line."""
    if parsed is None:
        return f"(status unavailable: {raw})"
    return (
        f"quality={parsed.get('data_quality')} "
        f"eligible={parsed.get('decision_eligible')} "
        f"breaker={parsed.get('circuit_breaker')} "
        f"simulator={parsed.get('simulator')} "
        f"age={parsed.get('refresh_age_seconds')}s"
    )


def main():
    print("=" * 78)
    print("BASELINE")
    print("=" * 78)
    parsed, raw = get_json(f"{BACKEND}/api/system-status")
    print(f"  {line_of(parsed, raw)}")

    print()
    print("=" * 78)
    print("INJECTING A LATENCY FAULT (delay_ms=4000, 30 s)")
    print("=" * 78)
    status, body = post(
        f"{SIM}/admin/faults",
        {"type": "latency", "duration_seconds": 30, "parameters": {"delay_ms": 4000}},
    )
    print(f"  fault injection -> HTTP {status}")
    print()
    print("  the simulator answers in ~6 s; the backend's read timeout is 2 s,")
    print("  so every read times out. Sampling every second:")
    print()

    started = time.monotonic()
    observations = []
    for second in range(1, 41):
        time.sleep(1)
        parsed, raw = get_json(f"{BACKEND}/api/system-status")
        metrics = read_metrics()
        opened = metric(metrics, 'fuel_circuit_breaker_opened_total{channel="read"}')
        line = line_of(parsed, raw)
        elapsed = int(time.monotonic() - started)
        print(f"  t+{elapsed:>2}s  {line}  opened_total={opened}")
        observations.append((elapsed, line))

    print()
    print("=" * 78)
    print("CLEARING")
    print("=" * 78)
    status, _ = post(f"{SIM}/admin/faults/clear")
    print(f"  fault cleared -> HTTP {status}")
    print()
    print("  the platform recovers on its own:")
    for second in range(1, 9):
        time.sleep(1)
        parsed, raw = get_json(f"{BACKEND}/api/system-status")
        print(f"  t+{second:>2}s  {line_of(parsed, raw)}")

    print()
    print("=" * 78)
    print("WHAT THE TIMELINE SHOWS")
    print("=" * 78)
    qualities = [line for _, line in observations]
    degraded = [line for line in qualities if "quality=INVALID" in line]
    opened = [line for line in qualities if "breaker=OPEN" in line]
    eligible = [line for line in qualities if "eligible=True" in line]
    print(f"  samples                     : {len(qualities)}")
    print(f"  samples with quality=INVALID: {len(degraded)}")
    print(f"  samples with eligible=False : {len(qualities) - len(eligible)}")
    print(f"  samples with breaker=OPEN   : {len(opened)}")
    print()
    if opened:
        print("  THE BREAKER OPENED — a slow simulator was treated as a dead one.")
    else:
        print("  the breaker never opened: a slow simulator is not a dead one.")
    if degraded:
        print("  the view went INVALID and the approval was blocked, which is")
        print("  correct: the platform cannot see the world while it is slow.")
    else:
        print("  the view stayed usable, which means the fault did not reach the client.")


if __name__ == "__main__":
    main()
