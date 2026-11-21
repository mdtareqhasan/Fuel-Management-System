# Current state

**As of:** 2026-11-20, end of Day 1 · **Release target:** 2026-11-23 · **Deadline:** 2026-11-26

---

## What runs right now

```bash
docker compose up --build -d
```

Six services, all healthy. The operator console at http://localhost:5173 renders
the simulated fuel world: 4 stations × 3 fuels, live depot stock, the mode badge,
the freshness badge, the refresh age, p95 latency and the error rate, with fuel
and region filters drawn from the snapshot itself.

```bash
python scripts/verify_day1.py     # 34 checks, all green
```

---

## Module status

| Module | Package | State |
|---|---|---|
| Typed simulator adapter | `simulator` | Complete — 3 error shapes, stale header, metrics |
| Snapshot validation and refresh | `ingestion` | Complete — atomic publish, degraded mode, coalesced polling |
| Domain model | `domain` | Complete — `WorldSnapshot`, `SimulationMode`, `DataQuality` |
| API layer | `web` | Complete — `/api/dashboard`, `/api/system-status` |
| Observability | `observability`, `simulator` | Complete — `HealthRegistry`, `SimulatorMetrics` |
| Frontend console | `frontend/src` | Complete — read-only operator view |
| View filters | `frontend/src/filters.ts` | Complete — fuel + region filters, 22 unit tests |
| Deployment | `compose.yaml`, `infra/` | Complete — 6 services, Prometheus, Grafana |
| CI | `.github/workflows/ci.yml` | Complete — 4 jobs including a compose smoke test |
| **Risk engine** | `risk` | **Not started — Day 2** |
| **Allocation** | `allocation` | **Not started — Day 3** |
| **Decision and approval** | `decision` | **Not started — Day 3** |
| **Command worker** | `command` | **Not started — Day 3** |
| **Audit trail** | `audit` | **Not started — Day 3** |
| **SSE listener** | `simulator` | **Not started — Day 3** |
| **Database schema** | — | **Not started — Day 3** (Flyway is wired, no migrations yet) |

---

## Test counts

| Suite | Tests | Command |
|---|---|---|
| Backend unit | 83 | `cd backend && ./mvnw verify` |
| Frontend unit | 22 | `cd frontend && npm run test` |
| Frontend type check | — (build fails on any type error) | `cd frontend && npm run build` |
| End-to-end | 34 checks | `python scripts/verify_day1.py` |
| Anomaly measurement | 2 numbers | `python scripts/verify_anomaly_detection.py` |
| Allocation invariant | 1 number | `python scripts/verify_dispatch_limit.py` |

---

## Contract facts that must not be forgotten

1. **Depot inventory is already net of reservations** (ADR 008). Do not subtract
   pending and in-transit again.
2. **Dispatch allowance is per depot, shared across every fuel and destination.**
3. **`X-Simulator-Stale: true` arrives with HTTP 200.** Check the header, not the
   status code.
4. **Demand history is newest-first.** Sort ascending before any EWMA.
5. **Forecast on `demand_liters`, not `served_liters`.**
6. **One `limit` is shared across all three fuels.** Split by fuel after fetching.
7. **`/v1/health` bypasses fault injection.** Reachability is not freshness.
8. **Three error envelopes:** `detail` as object, `detail` as array, top-level
   `error`.
9. **The measured tick rate is ~5.46/sec**, not the configured 8. A 30-second
   review spans ~41 simulated hours.
10. **The profile constants were measured, not transcribed.** The guide's highway
    busy window is 06–09, not 07–09. See ADR 005 and
    `docs/evidence/day-02/hour-factor-measurement.txt`.

---

## Immediate next steps — Day 3

1. Create the `decision` package: the proposal record, the approval command and
   the server-side guard that refuses a write from stale, invalid or
   circuit-open data (INV-15).
2. Persist the idempotency key and the exact request body **before** any POST
   (INV-10).
3. Allow the approved quantity to be **equal to or lower than** the reviewed
   quantity, never higher (INV-13).
4. Create the `command` worker: at most one command per proposal line (INV-12),
   and a timeout treated as `UNKNOWN` and reconciled by key (INV-14).
5. Add the Flyway migrations for the approval and audit tables.
6. Update this file and `docs/agent-activity-log.md`.

## Where the write-ups live

| Document | What it holds |
|---|---|
| `docs/daily/Day-02.md` | The Day 2 write-up — what was built, what was measured, what the bugs were |
| `docs/day-01-talking-points.md` | The Day 1 update sheet — what to say, with the evidence for each claim |
| `docs/architecture-diagram.md` | The one system diagram, truth ownership, the two clocks, why one backend |
| `docs/issue-board.md` | The 25-issue board, the issue cycle, the cut order |
| `docs/issues/FUEL-009-filters.md` | A worked issue specification, end to end |
| `docs/evidence/day-01/dashboard-sample.json` | A real snapshot payload, taken from the running stack |
| `docs/evidence/day-02/` | The Day 2 measurements: hour factors, false flags, detection delay, dispatch limit |
