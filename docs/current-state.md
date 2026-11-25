# Current state

**Updated:** 23 November 2026 · **Release target:** 24 November 2026

## Implemented

- **Phase 00 · Bootstrap and simulator integration:** typed simulator client, validated snapshots, dashboard and status APIs, Compose deployment, CI, Prometheus scrape, Grafana provisioning. Completed 20 November.
- **Phase 01 · Intelligence and risk:** demand forecasts, station/depot projections, rule-based anomaly flags, constrained shipment proposals, and risk UI. Completed 21 November.
- **Phase 02 · Persistence and operator decisions:** PostgreSQL snapshots/proposals/commands/audit, review and approval API/UI, idempotent shipment commands, server-side revalidation, reconciliation, route inventory UI. Completed and verified 22 November — 17/17 live end-to-end checks.
- **Phase 03 · Resilience and observability:** separate read/write circuit breakers, bounded read retries, stale-data approval guard, SSE with a 500 ms coalescing interval and REST polling fallback, route-event-aware planning, resilience metrics/dashboard, and alert rules as code. **Implemented and verified 23 November** with fault, forecast fallback, event crisis, restart and clean startup evidence; see [Day 4 completion audit](daily/Day-04.md#day-4-guide-completion-audit).

The official simulator remains the source of truth for current fuel inventory, demand, events, and shipment state. The backend stores validated observations and application decision records. Shipment writes happen only after operator approval passes backend checks.

## Verification

| Check | Result |
|---|---|
| [Day 4 fault timeline](evidence/day-04/fault-unavailable.txt) | `FRESH` → `INVALID` → `FRESH`, breaker `CLOSED` → `OPEN` → `CLOSED`, approval refused with `STALE_OR_INVALID_SNAPSHOT` |
| [Day 4 stale guard](evidence/day-04/fault-stale.txt) | HTTP 200 + stale header → `STALE`, ineligible, approval refused, breaker stays `CLOSED` |
| [Day 4 demand spike](evidence/day-04/event-demand-spike.txt) | Region multiplier 1.8 → **6 `KNOWN_EVENT` flags** after four stepped ticks |
| [Day 4 route crisis](evidence/day-04/event-combined-crisis.txt) | Six flags; Mirpur uses the Patiya alternative; **54.1 L** remains unmet at Tongi; events resolve at tick 44 |
| [Day 4 shipment delay](evidence/day-04/event-shipment-delay.txt) | `delay_ticks=4` shifts planned supply arrivals; depot risk records the changes |
| [Day 4 forecast fallback](evidence/day-04/forecast-fallback.txt) | 12 profile fallbacks during demand-history failure, then 12 EWMA forecasts after recovery |
| [Day 4 stream fallback](evidence/day-04/stream-fallback.txt) | Stream disconnect shows poll fallback and recovers to `CONNECTED` |
| [Day 4 RUNNING refresh volume](evidence/day-04/running-refresh-volume.txt) | 50 notifications coalesced; simulator calls 765→333 across comparable 16 s windows |
| [Day 4 clean startup](evidence/day-04/clean-compose-startup.txt) | Six services rebuilt; all healthy after Compose startup |
| [Day 4 clean-start metrics](evidence/day-04/metrics-after-clean-start.txt) | Fresh, eligible snapshot; eight rules `ok`; no active alerts |
| [Day 4 slow-vs-dead](evidence/day-04/fault-latency.txt) | Under a `latency` fault the view goes `INVALID` and the approval is refused, but the breaker stays `CLOSED` |
| [Day 3 regression against the Day 4 code](evidence/day-04/day3-regression.txt) | **17/17 checks passed** — the previous day's behaviour is intact |
| Prometheus alert rules | **8 loaded**, health `ok` |
| Backend `mvn clean test` | **154 tests passed**, 0 failures, 0 errors, 0 skipped · `BUILD SUCCESS` ([latest evidence](evidence/day-04/backend-tests-latest.txt)) |
| Frontend `npm test` | **31 tests passed** |
| Frontend `npm run build` | passed — clean production bundle |
| Day 4 UI checks in the served bundle | 7/7 strings present, `.banner-danger` present, 5/5 safety checks passed |
| [Day 3 end-to-end evidence](evidence/day-03/end-to-end-verification.txt) | **17/17 checks passed** against the running stack |
| Backend `mvn test` (Day 3) | **126 tests passed**, 0 failures, 0 errors, 0 skipped · `BUILD SUCCESS` |
| Day 3 UI strings in the served bundle | **12/12 present** |
| Flyway migration | `V1__day3_persistence_and_decisions.sql` applied successfully |
| Java runtime target | 25 |
| Local JDK | Oracle OpenJDK 25.0.2+10-LTS-69 |
| Backend container build | passed with `eclipse-temurin:25-jdk-alpine` / `eclipse-temurin:25-jre-alpine`; runtime reports `openjdk version "25.0.5.1"` |
| `docker compose config -q` | passed |

The simulator's `/v1/health` endpoint answered **HTTP 200 `status=ok` during every Day 4 fault**, because that endpoint bypasses fault injection. The platform reports reachability and data freshness as two separate facts, and the approval path refuses to act when the second one fails.

Full evidence index: [docs/evidence/day-04/README.md](evidence/day-04/README.md).

## Day 4 surface

| Endpoint | Purpose |
|---|---|
| `GET /api/system-status` | Now also reports `circuit_breaker`, `event_stream`, `stream_events` and `stream_reconnects` |

| Component | Purpose |
|---|---|
| `CircuitBreaker` / `SimulatorCircuitBreakers` | Separate `CLOSED`/`OPEN`/`HALF_OPEN` breakers for simulator reads and writes |
| `SimulatorEventStreamReader` | Reads the simulator's SSE feed. A hint, never the truth — every event only asks for a re-read. |
| `RefreshScheduler` | Coalesces stream notifications inside the configured 500 ms minimum interval; the 2 s poll remains active in both modes. |
| `RouteEventOverlay` | Marks an actively disrupted route unavailable to planning, derived from the simulator event payload. |
| `ResilienceProperties` | Timeout, retry and breaker policy, kept apart and bounded by the freshness window |
| `TransportFailureClassifier` | Tells a refused connection (an outage) from a read timeout (a slow simulator) from a connect timeout (an outage). The chains are measured by a test, not assumed. |
| `DecisionMetrics` | Approval refusals, approval outcomes, refresh failures and refresh triggers |
| `fuel-platform-resilience.json` | The Grafana dashboard for the integration's health |
| `alert_rules.yml` | Eight Prometheus alert rules, each with a runbook reference |

## Day 3 surface

| Endpoint | Purpose |
|---|---|
| `GET /api/proposals` | The proposals of the current run, newest first |
| `POST /api/proposals` | Creates a proposal from the current plan. **No simulator write.** |
| `GET /api/proposals/{id}` | One proposal with its lines and reasons |
| `POST /api/proposals/{id}/approve` | Approves with a per-line upper bound. May only lower the quantity. |
| `GET /api/shipments` | The shipment ledger — application command status and simulator shipment status, separately |
| `POST /api/shipments/reconcile` | Resolves unknown commands by matching the idempotency key |

| Table | Holds |
|---|---|
| `snapshots` | One validated observation of the world, with the full payload |
| `proposals` | The reviewed envelope and its status |
| `proposal_lines` | Per-line reviewed vs final quantity, reasons, constraints, alternatives |
| `commands` | The idempotency key, the frozen request body, and the command outcome |
| `audit_events` | Every decision and every write attempt, including the refused ones |

## Known limitations

- The end-to-end verifier mutates the live simulator and database. Run it against the disposable local stack, not a preserved demo state.
- The frontend container serves a built bundle. After any frontend change, run `docker compose build frontend` before `docker compose up -d frontend`, or the old bundle keeps being served.
- The anomaly detector's thresholds are proposed, not tuned. A spike shorter than three ticks is not detected.
- The command worker sends on the request thread. High approval volume would want a queue behind it.
- `AuditRepository.record` uses `REQUIRES_NEW`, so each decision costs an extra commit. Paid deliberately — an audit that only records successes is not an audit.

## Next work

- **Phase 04 · Evaluation and release rehearsal** (planned for 24 November): policy comparison, load evaluation, clean startup, and demo rehearsal.

## Simulator contract facts

1. Depot inventory is already net of pending and in-transit shipments; do not subtract them again.
2. Dispatch allowance is shared per depot across fuels and destinations.
3. `X-Simulator-Stale: true` can accompany HTTP 200; inspect the header and freshness data.
4. Demand history is newest-first; sort oldest-first before forecasting.
5. Forecast `demand_liters`, not `served_liters`.
6. A shared history limit applies across fuels; split the returned rows by fuel after fetching.
7. `/v1/health` bypasses fault injection; reachability is not data freshness.
8. Simulator errors have multiple response shapes; normalize them in the adapter.
9. Configured simulator speed is not a guaranteed tick rate.
10. Simulation ticks measure fuel-world time; wall-clock age measures application data freshness.
11. A transport error message contains a double-quoted URL. Any JSON built by string concatenation from a simulator message will be invalid — serialise a map instead.

## Related documents

- [System architecture](system-architecture.md)
- [Acceptance criteria and invariants](acceptance-and-invariants.md) — INV-37 to INV-46 are the Day 3 rules
- [Day 4 implementation and evidence](daily/Day-04.md)
- [Day 3 implementation and evidence](daily/Day-03.md)
- [ADR 009 — the decision record and the shipment ledger](adr/ADR-009-decision-record-and-shipment-ledger.md)
- [Architecture decisions](adr/)
