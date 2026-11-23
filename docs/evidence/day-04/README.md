# Day 4 evidence — Resilience & Observability

Evidence was captured against the **running** local stack on 2026-11-22 and
2026-11-23 (the metrics capture is timestamped 2026-11-23T00:41:52Z). Nothing in this folder is a transcript of what the code was
supposed to do.

## What each file shows

| File | What it is | How to reproduce it |
|---|---|---|
| `fault-unavailable.txt` | The full failure boundary under an `unavailable` fault: `FRESH` → `INVALID` → `FRESH`, the breaker `CLOSED` → `OPEN` → `CLOSED`, and an approval refused with `STALE_OR_INVALID_SNAPSHOT`. The simulator's `/v1/health` answers `HTTP 200 status=ok` throughout. | `python scripts/inject_fault.py unavailable 30` |
| `fault-stale.txt` | The stale guard. HTTP 200 with `X-Simulator-Stale: true` → quality `STALE`, eligibility `false`, approval refused, and the breaker correctly stays `CLOSED` because the simulator is reachable. | `python scripts/inject_fault.py stale 30` |
| `breaker-trace.txt` | The circuit breaker second by second, with the metric counters before, during and after. `breaker=OPEN` from t+4s to t+16s. | `python scripts/trace_breaker_during_fault.py` |
| `event-demand-spike.txt` | A region demand multiplier of 1.8 produces **6 `KNOWN_EVENT` anomaly flags** after four stepped ticks. | `python scripts/capture_day4_event.py demand_spike 12` |
| `metrics-steady-state.txt` | The Prometheus surface: the system status, the resilience series, the **8 alert rules with their health**, and the alerts that are firing. | `python scripts/capture_metrics_evidence.py steady-state docs/evidence/day-04/metrics-steady-state.txt` |
| `metrics-after-clean-start.txt` | Post-startup snapshot is fresh and eligible, all **8 rules healthy**, and no alerts are firing. | `python scripts/capture_metrics_evidence.py after-clean-start docs/evidence/day-04/metrics-after-clean-start.txt` |
| `backend-tests.txt` | `mvn clean test` — **149 tests passed**, 0 failures, 0 errors. | `cd backend && ./mvnw.cmd clean test` |
| `backend-tests-latest.txt` | Latest backend suite after route-event handling and SSE coalescing — **154 tests passed**, 0 failures, 0 errors. | `cd backend && docker build -f Dockerfile.test -t fuel-platform-backend-tests .` |
| `day3-regression.txt` | The previous day's end-to-end verifier run against the Day 4 code — **17/17 checks passed**. This is the run that caught the timeout bug, and the run that proves the fix landed. | `python scripts/verify_day3_end_to_end.py` |
| `frontend-tests.txt` | `npm test` — **31 tests passed**; `npm run build` — clean. | `cd frontend && npm test && npm run build` |
| `frontend-ui-checks.txt` | The Day 4 UI strings present in the **served bundle**, the `.banner-danger` rule present, and the safety checks: the panel is hidden on a healthy system, gated on the breaker state, and the browser never talks to the simulator. | `python scripts/verify_frontend_day4_ui.py` |
| `deployment-topology.txt` | The running topology: six services, their status and their published ports. | `docker compose ps` |
| `forecast-fallback.txt` | Simulator demand-history errors select profile fallback forecasts; the counter increases and recovers to EWMA after the breaker closes. | `python scripts/capture_forecast_fallback.py` |
| `event-route-disruption.txt` | Combined demand and route crisis: six flags, with the Mirpur plan choosing the Patiya alternative while the Gazipur route is disrupted. | `python scripts/capture_combined_route_crisis.py` |
| `event-combined-crisis.txt` | Multiplier 3.0 combined crisis: six `KNOWN_EVENT` flags, Mirpur uses the Patiya route, and 54.1 L remains unmet at Tongi; events resolve at tick 44. | `python scripts/capture_combined_route_crisis.py 3.0` then step through tick 44 |
| `event-shipment-delay.txt` | A `shipment_delay` with `delay_ticks=4` updates scheduled supply and depot risk. | `python scripts/capture_day4_event.py shipment_delay 8` |
| `stream-fallback.txt` | Backend restart during `stream_disconnect` shows stream `DISCONNECTED`, REST poll refreshes and fresh data; clearing fault reconnects. | Inject stream fault, restart backend, inspect system status and metrics |
| `running-refresh-volume.txt` | A RUNNING window measures tick, event, coalesced-refresh, poll and simulator-call deltas. | `python scripts/capture_running_refresh_volume.py` |
| `resilience-panel.png` / `resilience-panel.txt` | Operator console during unavailable fault with OPEN breaker and blocked decision. | `python scripts/inject_fault.py unavailable 60` and headless browser screenshot |
| `backend-restart.txt` | Readiness and current decision quality after backend restart. | `docker compose restart backend` |
| `clean-compose-startup.txt` | Clean build/start after stopping the stack without deleting volumes; all six services are up. | `docker compose down` then `docker compose up --build -d` |
| `incident-handoff.txt` / `incident-logs.txt` | Run/snapshot IDs, metrics and backend logs for incident review. | `python scripts/capture_incident_handoff.py` and `docker compose logs backend` |

## Completion note

The Day 4 evidence set now includes forecast fallback, backend restart,
demand/route/supply events, stream reconnect and poll fallback, RUNNING refresh
volume, a fault-time UI screenshot, a run-id/log handoff, and a clean Compose
startup. The independent reviewer and fresh-session resume described in the
guide were not performed; the local handoff is ready for that review. See the
completion audit in [`docs/daily/Day-04.md`](../../daily/Day-04.md).

The steady-state Prometheus capture shows the rules are loaded and evaluating
(`health=ok`), while `UnknownShipmentOutcomes` and `StaleResponsesFromSimulator`
are firing. Treat it as an observed operational state, not an all-clear
baseline.

## The three failure shapes, and what each one does

Measured against the running simulator — see `scripts/inspect_fault_shapes.py`:

| Fault | What the client sees | The simulator is… | The breaker | The view |
|---|---|---|---|---|
| `unavailable` | HTTP **503** `FAULT_INJECTED` | **down** | **opens** | `INVALID`, approvals refused |
| `stale_data` | HTTP **200** + `X-Simulator-Stale: true` | reachable but serving old data | stays closed | `STALE`, approvals refused |
| `latency` | a client-side **read timeout** | **slow, but alive** | stays closed | `INVALID`, approvals refused |

The middle row is the one that catches people out. The status code says
everything is fine; only the header tells the truth.

## The result worth reading twice

The simulator's `/v1/health` endpoint answered `HTTP 200 status=ok` during
**every** fault in this folder. That endpoint bypasses fault injection, so it
reports that the simulator *process* is alive while every station read fails.

```
before : simulator /v1/health HTTP 200 ok   platform: FRESH    eligible=True
during : simulator /v1/health HTTP 200 ok   platform: INVALID  eligible=False
after  : simulator /v1/health HTTP 200 ok   platform: FRESH    eligible=True
```

The platform reports the two facts separately, because they are two facts:

- the simulator is **reachable**;
- the station data is **not fresh**.

A platform that reported only the first would have shown a healthy green
screen through a total outage. **Reachability is not freshness.**

## Reproducing the whole thing

```bash
# 1. The stack must be up and healthy.
docker compose up --build -d
docker compose ps

# 2. Each fault takes about 60 s: 12 s to notice, 2 s to clear, 35 s to recover.
python scripts/inject_fault.py unavailable 30
python scripts/inject_fault.py stale 30

# 3. The breaker, second by second.
python scripts/trace_breaker_during_fault.py

# 4. A crisis event.
python scripts/inject_fault.py event demand_spike 8

# 5. The metrics and the alert rules.
python scripts/capture_metrics_evidence.py steady-state docs/evidence/day-04/metrics-steady-state.txt
```

> The fault scripts mutate the live simulator and database. Run them against
> the disposable local stack, never against a state that is meant to be
> preserved.

## The scripts

| Script | Job |
|---|---|
| `scripts/inject_fault.py` | Injects a fault or a crisis event, watches the platform react, clears it, and watches it recover. |
| `scripts/trace_breaker_during_fault.py` | Traces the circuit breaker second by second while a fault is active. |
| `scripts/capture_metrics_evidence.py` | Captures the Prometheus surface as text, including the alert-rule health. |
| `scripts/verify_frontend_day4_ui.py` | Checks the Day 4 UI against the **served bundle**, not the source. |
| `scripts/inspect_stream_contract.py` | Prints the simulator's SSE stream contract. |
| `scripts/inspect_stream_frames.py` | Captures the raw SSE frames the simulator sends while the world moves. |
| `scripts/inspect_day4_status.py` | Prints the status fields and the Day 4 metric series. |
| `scripts/inspect_breaker_metrics.py` | Prints the breaker counters and the simulator call outcomes. |
| `scripts/inspect_event_schema.py` | Prints the simulator's `EventCreate` and `FaultCreate` schemas. |
