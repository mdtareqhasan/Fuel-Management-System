"""Measure read-path latency at staged concurrency in PAUSED and RUNNING.

This is an operator-console/API load probe, not a browser-rendering benchmark.
It exercises the three read endpoints the console depends on and restores the
simulator's initial mode when it exits.

Usage (from the repository root):
    python scripts/day5_load_test.py

Each virtual user makes one simultaneous GET request. This is a burst
concurrency comparison, not a sustained soak or browser-rendering benchmark.
RUNNING advances simulator time and the app continues recording observations.
"""

from __future__ import annotations

import concurrent.futures
import json
import math
import platform
import threading
import time
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

# Use IPv4 explicitly: on some Windows setups Python tries ::1 first even
# though Compose publishes only 127.0.0.1, adding a misleading fallback delay.
ROOT = "http://127.0.0.1:8080"
SIM = "http://127.0.0.1:8000"
ENDPOINTS = ("/api/dashboard", "/api/risk", "/api/system-status")
OUT = Path("docs/evidence/day-05/load-summary.json")
LOCAL = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def get_json(url: str, timeout: float = 8.0) -> tuple[int, dict]:
    request = urllib.request.Request(url, headers={"Accept": "application/json"})
    with LOCAL.open(request, timeout=timeout) as response:
        return response.status, json.loads(response.read())


def post(url: str) -> None:
    request = urllib.request.Request(url, data=b"{}", method="POST",
                                     headers={"Content-Type": "application/json"})
    with LOCAL.open(request, timeout=30.0) as response:
        response.read()


def percentile(values: list[float], p: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    index = max(0, math.ceil(p * len(ordered)) - 1)
    return round(ordered[index], 3)


def set_mode(mode: str) -> dict:
    post(f"{SIM}/admin/{'run' if mode == 'RUNNING' else 'pause'}")
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        _, state = get_json(f"{ROOT}/api/system-status")
        if state.get("simulation_mode") == mode:
            if state.get("data_quality") != "FRESH" or not state.get("decision_eligible"):
                raise RuntimeError(f"stack is not fresh and decision-eligible: {state}")
            return state
        time.sleep(0.25)
    raise TimeoutError(f"simulator did not enter {mode}")


def stage(vus: int, label: str) -> dict:
    latencies: list[float] = []
    by_endpoint = {path: [] for path in ENDPOINTS}
    failures: list[str] = []
    lock = threading.Lock()
    gate = threading.Barrier(vus + 1)
    start = time.monotonic()

    def user_once(user_id: int) -> None:
        path = ENDPOINTS[user_id % len(ENDPOINTS)]
        gate.wait()
        before = time.perf_counter()
        try:
            status, body = get_json(f"{ROOT}{path}", timeout=12.0)
            if status != 200 or not isinstance(body, dict):
                raise RuntimeError(f"unexpected response from {path}: HTTP {status}")
            elapsed_ms = (time.perf_counter() - before) * 1000
            with lock:
                latencies.append(elapsed_ms)
                by_endpoint[path].append(elapsed_ms)
        except Exception as exc:  # record the actual failed request
            with lock:
                failures.append(f"{path}: {type(exc).__name__}: {exc}")

    with concurrent.futures.ThreadPoolExecutor(max_workers=vus) as pool:
        futures = [pool.submit(user_once, user) for user in range(vus)]
        gate.wait()
        for future in futures:
            future.result()
    elapsed = time.monotonic() - start
    return {
        "label": label,
        "virtual_users": vus,
        "duration_seconds": round(elapsed, 3),
        "requests": len(latencies),
        "errors": len(failures),
        "requests_per_second": round((len(latencies) + len(failures)) / elapsed, 2) if elapsed else 0,
        "latency_ms": {
            "p50": percentile(latencies, 0.50),
            "p95": percentile(latencies, 0.95),
            "p99": percentile(latencies, 0.99),
            "max": round(max(latencies), 3) if latencies else None,
        },
        "endpoint_latency_ms": {
            path: {
                "requests": len(samples),
                "p50": percentile(samples, 0.50),
                "p95": percentile(samples, 0.95),
                "max": round(max(samples), 3) if samples else None,
            }
            for path, samples in by_endpoint.items()
        },
        "error_samples": failures[:5],
    }


def main() -> int:
    _, initial = get_json(f"{ROOT}/api/system-status")
    if initial.get("data_quality") != "FRESH" or not initial.get("decision_eligible"):
        raise RuntimeError(f"refusing to load-test an ineligible stack: {initial}")
    original_mode = initial.get("simulation_mode")
    if original_mode not in ("PAUSED", "RUNNING"):
        raise RuntimeError(f"unknown initial simulator mode: {original_mode}")

    result = {
        "run_id": get_json(f"{ROOT}/api/dashboard")[1].get("metadata", {}).get("run_id"),
        "captured_at": datetime.now(timezone.utc).isoformat(),
        "machine": {
            "os": platform.platform(),
            "processor": platform.processor() or "not reported by Python",
            "logical_cpu_count": __import__("os").cpu_count(),
        },
        "workload": {
            "kind": "simultaneous HTTP burst; one request per virtual user; not a soak or browser rendering",
            "endpoints": list(ENDPOINTS),
            "requests_per_virtual_user": 1,
            "request_timeout_seconds": 12,
            "paused_stages_vus": [1, 5, 10, 25],
            "running_stage_vus": 25,
        },
        "initial_mode": original_mode,
        "stages": [],
        "restored_mode": None,
    }
    try:
        for vus in (1, 5, 10, 25):
            set_mode("PAUSED")
            result["stages"].append({"mode": "PAUSED", **stage(vus, f"paused-{vus}vu")})
        set_mode("RUNNING")
        result["stages"].append({"mode": "RUNNING", **stage(25, "running-25vu")})
    except Exception as exc:
        result["test_error"] = f"{type(exc).__name__}: {exc}"
    finally:
        try:
            restored = set_mode(original_mode)
            result["restored_mode"] = restored.get("simulation_mode")
            result["final_status"] = {
                "data_quality": restored.get("data_quality"),
                "decision_eligible": restored.get("decision_eligible"),
                "p95_latency_ms": restored.get("p95_latency_ms"),
                "error_rate_percent": restored.get("error_rate_percent"),
                "refresh_age_seconds": restored.get("refresh_age_seconds"),
            }
        except Exception as exc:
            result["restore_error"] = f"{type(exc).__name__}: {exc}"

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2))
    if result.get("restore_error"):
        return 2
    if result.get("test_error"):
        return 1
    if any(s["errors"] for s in result["stages"]):
        return 1
    if result.get("restored_mode") != original_mode:
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
