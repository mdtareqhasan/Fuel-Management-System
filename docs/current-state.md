# Current state

**As of:** 2026-11-20, end of Day 1 · **Release target:** 2026-11-23 · **Deadline:** 2026-11-26

---

## What runs right now

```bash
docker compose up --build -d
```

Six services, all healthy. The operator console at http://localhost:5173 renders
the simulated fuel world: 4 stations × 3 fuels, live depot stock, the mode badge,
the freshness badge, the refresh age, p95 latency and the error rate.

```bash
python scripts/verify_day1.py     # 33 checks, all green
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
| Backend unit | 30 | `cd backend && ./mvnw verify` |
| Frontend type check | — (build fails on any type error) | `cd frontend && npm run build` |
| End-to-end | 33 checks | `python scripts/verify_day1.py` |

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

---

## Immediate next steps — Day 2

1. Create the `risk` package: demand rate, forecast, stockout trajectory.
2. Fetch demand history per station, split by fuel, sort ascending.
3. Implement the ratio-based anomaly detector (ADR 005) with the two required tests.
4. Add the forecast fallback path and its counter.
5. Add `fuel_forecast_fallback_total` and `fuel_anomaly_flags_total` to the metric
   set.
6. Update this file and `docs/agent-activity-log.md`.
