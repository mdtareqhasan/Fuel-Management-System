# Day 5 — Load test report

**Run date:** 23 November 2026  
**Run ID:** `1edec77f-5c28-4611-83bf-fb7610ea4ae5`  
**Machine:** Windows 11, AMD64, 12 logical CPUs  
**Raw result:** [`load-summary.json`](load-summary.json)  
**Harness:** [`scripts/day5_load_test.py`](../../../scripts/day5_load_test.py)

## Executive summary

The bounded read-path probe sent simultaneous bursts of one HTTP GET per
virtual user to the dashboard, risk, and system-status APIs. PAUSED stages of
1, 5, 10, and 25 users returned 200 with no recorded request errors. The 25
user RUNNING burst also had no HTTP errors, but `/api/risk` took about **11.77
seconds** (p50 11.762 s; p95 11.770 s). Dashboard and system status stayed near
60 ms and 53 ms p95 respectively. This indicates a costly risk calculation
while simulator time advances; the run is far too small to define a supported
user capacity.

A separate repeated stress attempt (not the same bounded burst) exceeded the
official simulator's SQLAlchemy connection pool (5 base connections plus 10
overflow), causing connection checkout timeouts. The attempt was stopped;
simulator and backend were restarted and their recovery was verified. See
[`simulator-overload-logs.txt`](simulator-overload-logs.txt). Do not interpret
the bounded burst's zero HTTP errors as evidence that sustained load is safe.

## Test design

- **Purpose:** measure API read latency under operator-console-like concurrent
  reads, comparing the simulator paused versus advancing.
- **Endpoints:** `GET /api/dashboard`, `GET /api/risk`, and
  `GET /api/system-status`.
- **PAUSED stages:** 1, 5, 10, and 25 concurrent virtual users.
- **RUNNING stage:** 25 concurrent virtual users.
- Each virtual user sent **one request only**. A thread barrier released all
  users at approximately the same time. Endpoint assignment was round-robin;
  because stage sizes are not all divisible by three, endpoint request counts
  differ slightly.
- Request timeout was 12 seconds. The harness used local IPv4 and disabled
  proxy use to avoid Windows IPv6 fallback/proxy delays contaminating latency.
- The harness required a fresh, decision-eligible starting view and restored
  the simulator to its initial mode. It began PAUSED and ended PAUSED, FRESH,
  decision-eligible, with no request errors in the recorded stages.
- This probe did not use a browser, generate user think-time, sustain traffic,
  exercise write endpoints, or measure multiple hosts.

## Results

Latencies are milliseconds. With only 1–25 samples per stage, p95/p99 are
sample maxima or near-maxima and should be read as observations, not stable
service-level percentiles.

| Simulator mode | VUs | Requests | Errors | Overall p50 | Overall p95 | Overall max | `/api/dashboard` p95 | `/api/risk` p95 | `/api/system-status` p95 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| PAUSED | 1 | 1 | 0 | 30.9 | 30.9 | 30.9 | 30.9 | — | — |
| PAUSED | 5 | 5 | 0 | 16.4 | 257.9 | 257.9 | 16.4 | 257.9 | 15.6 |
| PAUSED | 10 | 10 | 0 | 19.7 | 28.5 | 28.5 | 27.9 | 26.9 | 28.5 |
| PAUSED | 25 | 25 | 0 | 50.4 | 73.9 | 81.1 | 68.6 | 81.1 | 62.6 |
| RUNNING | 25 | 25 | 0 | 52.6 | 11,768.2 | 11,770.0 | 59.7 | 11,770.0 | 53.0 |

The paused 5-user risk outlier (258 ms) also shows that these tiny samples can
vary. In the RUNNING stage, seven of eight risk requests were fast and one or
more outliers dominated the risk percentile; this is consistent with the
reported p50/p95 gap, but needs repeated measurement before attributing a stable
distribution. The stage completed in 11.788 seconds and recorded 2.12 total
requests/second; that throughput is the completed requests divided by one
burst's duration, **not** a sustainable RPS rating.

## Separate sustained-stress observation

The separate repeated-read stress attempt ran enough concurrent work to
exhaust the simulator's SQLAlchemy pool (`size 5 + overflow 10`). Simulator
calls began timing out at connection checkout. This is an upstream simulator
capacity boundary observed in this local Compose topology; the evidence does
not establish whether the backend, simulator, or both would be the first
constraint under another deployment or traffic mix. The recovery record is in
[`simulator-overload-logs.txt`](simulator-overload-logs.txt).

## Interpretation and next measurements

**Current conclusion:** suitable as local functional/demo evidence, but there
is not enough load evidence to publish a supported concurrency or capacity
claim. In particular, the RUNNING `/api/risk` outlier and simulator pool
exhaustion need attention before expecting sustained concurrent usage.

For a deployment decision, repeat from a fresh/stable environment with a
realistic browser/API mix, warm-up, 1/5/10/25+ concurrent users, at least
10–15-minute steady stages, repeated runs, and both PAUSED/RUNNING conditions.
Record p50/p95/p99, error rate, throughput over the steady window, backend CPU
and memory, DB/simulator pool utilization, and Prometheus metrics. Set an
explicit latency/error acceptance target first, optimize the RUNNING risk
path and upstream connection use, then rerun. Keep write/approval load testing
separate and use a disposable database/simulator because it mutates domain
state.

## Reproduce the bounded probe

From the repository root, with the Compose stack available:

```powershell
python scripts/day5_load_test.py
```

The command overwrites `load-summary.json` and changes the simulator between
PAUSED and RUNNING while it measures, then restores its original mode. Do not
run it against a preserved live operational environment.
