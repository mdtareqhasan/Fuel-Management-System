# Current state

**Updated:** 22 November 2026 · **Release target:** 24 November 2026

## Implemented

- **Phase 00 · Bootstrap and simulator integration:** typed simulator client, validated snapshots, dashboard and status APIs, Compose deployment, CI, Prometheus scrape, Grafana provisioning. Completed 20 November.
- **Phase 01 · Intelligence and risk:** demand forecasts, station/depot projections, rule-based anomaly flags, constrained shipment proposals, and risk UI. Completed 21 November.
- **Phase 02 · Persistence and operator decisions:** PostgreSQL snapshots/proposals/commands/audit, review and approval API/UI, idempotent shipment commands, server-side revalidation, reconciliation, route inventory UI. **Completed and verified 22 November** — 17/17 live end-to-end checks.

The official simulator remains the source of truth for current fuel inventory, demand, events, and shipment state. The backend stores validated observations and application decision records. Shipment writes happen only after operator approval passes backend checks.

## Verification

| Check | Result |
|---|---|
| [Day 3 end-to-end evidence](evidence/day-03/end-to-end-verification.txt) | **17/17 checks passed** against the running stack |
| Backend `mvn test` | **126 tests passed**, 0 failures, 0 errors, 0 skipped · `BUILD SUCCESS` |
| Frontend `npm test -- --run` | **31 tests passed** |
| Frontend `npm run build` | passed — clean production bundle |
| Day 3 UI strings in the served bundle | **12/12 present** |
| Day 3 CSS rules in the served bundle | 5/5 present, `.grid` card layout intact |
| Flyway migration | `V1__day3_persistence_and_decisions.sql` applied successfully |
| Java runtime target | 25 |
| Local JDK | Oracle OpenJDK 25.0.2+10-LTS-69 |
| Backend container build | passed with `eclipse-temurin:25-jdk-alpine` / `eclipse-temurin:25-jre-alpine`; runtime reports `openjdk version "25.0.5.1"` |
| `docker compose config -q` | passed |

The Day 3 end-to-end run includes the lost-response case: a simulator write whose response never arrives is recorded as `UNKNOWN` with the quoted URL preserved in `last_error`, and reconciling it by idempotency key produces no second shipment.

Full evidence index: [docs/evidence/day-03/README.md](evidence/day-03/README.md).

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

- **Phase 03 · Resilience and observability** (planned for 23 November): fault recovery, guarded writes, SSE refresh, crisis scenarios, and operational evidence.
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
- [Day 3 implementation and evidence](daily/Day-03.md)
- [ADR 009 — the decision record and the shipment ledger](adr/ADR-009-decision-record-and-shipment-ledger.md)
- [Architecture decisions](adr/)
