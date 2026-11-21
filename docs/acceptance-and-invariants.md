# Acceptance Stories and Invariants

**Project:** Fuel Supply Intelligence & Resilience Platform
**Source:** `BUP_CSE_FEST_2026_Participant_Facing_Hackathon_Draft.md` sections 6, 9, 10, 12, 16, 18, 26
**Date:** 2026-11-20

---

## 1. Acceptance stories

Each story is written so that it can be **observed**, not merely asserted. The "Done" column is the check that closes it.

| # | Story | Done when |
|---|---|---|
| AS-1 | An operator filters by fuel and region, finds a station at risk, sees *why* it is at risk, and sees the data timestamp. | The risk table renders from a validated snapshot and shows the tick, sim_time and refresh age. |
| AS-2 | An operator inspects a proposed depot, route, fuel and quantity, approves it once, and sees the shipment status change in the simulator. | One proposal line → exactly one simulator allocation, verifiable in `/v1/allocations` by idempotency key. |
| AS-3 | During an API fault, the last valid data stays readable with a stale warning, approval is blocked, and recovery refreshes the world before approval re-enables. | Screenshot + log + metric showing cached read mode, blocked approval, then recovery. |
| AS-4 | A new engineer launches the stack from the README, reproduces a crisis test and inspects measured results. | A fresh clone starts with `docker compose up --build -d` and the runbook's smoke test passes. |
| AS-5 | While the simulator clock is RUNNING, an operator can review and approve a proposal; the server revalidates it against a fresh read and never sends more than the reviewed quantity. | Three approval tests pass: same tick, after stepping, and after a route disruption (which must send nothing). |
| AS-6 | When a depot supply is delayed or reduced, the operator sees a depot shortfall window and the stations it would affect first. | Depot risk shows the window, the cause, and the affected stations. |
| AS-7 | A demand spike is flagged as an anomaly within a measured number of ticks, and a quiet run produces no false flag. | Replay reports both numbers as measured: detection delay and false-flag count. |

---

## 2. Domain invariants

These are properties that must hold **always**. Each is enforced by code, a database constraint or a test — never by documentation alone.

### The world

| ID | Invariant | Enforced by |
|---|---|---|
| INV-1 | The simulator owns inventory, demand and shipment status. The application never presents a stored snapshot as current truth. | ADR 002 · snapshot model |
| INV-2 | A stored snapshot is evidence of a past observation, never an alternative world. | ADR 002 |
| INV-3 | Every stored record carries an application run UUID. Simulator instance id (always `1`) is never used for isolation. | ADR 002 |
| INV-4 | On reset or tick rollback: suspend writes, start a new run, retire old recommendations, reconcile commands. | run lifecycle |

### Data quality

| ID | Invariant | Enforced by |
|---|---|---|
| INV-5 | `X-Simulator-Stale: true` on any `/v1/*` GET makes that response **ineligible for decisions**. It may still be displayed with a warning. | Adapter · quality guard |
| INV-6 | A missing or invalid required field is **unknown**, never zero. A payload with a broken reference is rejected and the previous valid snapshot is kept. | Validator |
| INV-7 | `/v1/health` is a liveness probe only. It never counts as proof that station data is fresh. | Contract probe §6 |
| INV-8 | A snapshot is published **atomically** and only after full validation. There is no partially-valid view. | Ingestion service |

### Writes

| ID | Invariant | Enforced by |
|---|---|---|
| INV-9 | No allocation reaches the simulator without a server-enforced operator approval. | ADR 003 |
| INV-10 | The idempotency key and the exact request body are persisted **before** any POST. | Command transaction |
| INV-11 | A retry reuses **the same key and the same body**. The quantity is never changed while a key is reused. | Command worker |
| INV-12 | One proposal line produces at most one command. | DB unique constraint `(recommendation_id, line_id)` |
| INV-13 | The approved quantity may be **equal to or lower than** the reviewed quantity. Never higher. | ADR 004 |
| INV-14 | A timeout after a POST means **UNKNOWN**, not rejected. It is reconciled by matching the idempotency key. | Command worker |
| INV-15 | A write is never attempted from stale, invalid or circuit-open data. | Server-side guard |
| INV-16 | Depot inventory is used **as returned** (already net of reservations). It is never subtracted again. | ADR 008 |
| INV-17 | Dispatch allowance is reserved **per depot, shared across all fuels and destinations**, before planning the next line. | ADR 008 · allocation working copy |
| INV-18 | Station headroom **does** reserve pending and in-transit inbound fuel, because that fuel will land. | ADR 008 · allocator |

### Decision quality

| ID | Invariant | Enforced by |
|---|---|---|
| INV-19 | Forecasts use `demand_liters`, never `served_liters`. | Contract probe §4 |
| INV-20 | Demand observations are sorted **ascending by tick** before any EWMA is computed. | Contract probe §4 (API returns newest-first) |
| INV-21 | Rows are split by fuel after fetching, because one `limit` is shared across all three fuels. | Contract probe §4 |
| INV-22 | A proposal lists up to two alternatives, or `NO_ALTERNATIVE_ROUTE` where genuinely none exists. Never a fabricated alternative. | Allocator |
| INV-23 | Before/after values are labelled **estimated impact**, never a probability or a confidence percentage. | Risk + recommendation DTO |
| INV-24 | Unknown is displayed as **Unknown**, never as zero. | UI + API |
| INV-25 | The anomaly detector is advisory. It never changes a quantity and never blocks risk output or approval. | ADR 005 |
| INV-30 | One unmet need produces **exactly one** reason code. A need is never listed twice, and never with two conflicting explanations. | Allocator · `AllocationPlannerTest.oneNeedProducesOneExplanation` |
| INV-31 | A sub-litre rounding residue is **not** reported as an unmet need. Quantities are rounded to whole litres, and the leftover is not a shortfall. | Allocator · `AllocationPlannerTest.roundingResidueIsNotAnUnmetNeed` |
| INV-32 | A station at **exactly zero** is empty. The stockout is reported on that tick, not the next one. | Projector · `StationRiskProjectorTest.stockoutTickIsTheFirstEmptyTick` |
| INV-33 | Fuel arriving **after** the tank empties does not prevent the stockout. Only fuel that lands before it does. | Projector · `StationRiskProjectorTest.lateArrivalDoesNotPreventTheEarlierStockout` |
| INV-34 | The profile constants are **measured against the running simulator**, versioned in `application.yml`, and a measurement refuses to report while an event is active. | `measure_hour_factor_clean.py` · ADR 005 |
| INV-35 | A flag carries the tick on which R1 **tripped**, not the last tick above the threshold. A spike that started fifteen ticks ago must not look like it was detected fifteen ticks late. | Detector · `DemandAnomalyDetectorTest.flagReportsTheFiringTickNotTheLastBreach` |
| INV-36 | R3 clears a flag only after **three consecutive calm ticks**, as the guide specifies. One quiet tick in the middle of a spike is noise. | Detector · `DemandAnomalyDetectorTest.threeCalmTicksClearTheFlag` |

### Observability and hygiene

| ID | Invariant | Enforced by |
|---|---|---|
| INV-26 | The Prometheus endpoint is live from day one. | ADR 006 |
| INV-27 | Unique ids (station, allocation, correlation) never appear as metric labels. Fixed small label sets only. | ADR 006 |
| INV-28 | The official simulator image and source are never modified. | ADR 007 |
| INV-29 | No secret is hardcoded. Config comes from environment variables; `.env` is gitignored. | Config |
| INV-30 | Operator approval is enforced on the server. A disabled button is a convenience, not a control. | ADR 003 |

---

## 3. The two operating modes

| | PAUSED | RUNNING |
|---|---|---|
| Clock | Frozen; driven by `POST /admin/step` | Advances continuously |
| Used for | Deterministic demos, tests, evaluation | Continuous operation, surprise events |
| Refresh trigger | 2-second poll + refresh after each step | Coalesced `simulation.tick` events with a 500 ms minimum interval; 2-second poll as fallback |
| Tick lag rule | Proposal must belong to the current tick, else regenerate | Not blocking — lag is displayed, approval revalidates against a fresh read |
| Wall age rule | Block approval if the last valid refresh is over 10 wall seconds old | Same rule, same 10 seconds |
| Always blocking | Stale header, invalid payload, unknown write, reset, open circuit | Same |

**Measured context:** at the configured `SIMULATION_SPEED=8`, `TICK_MINUTES=15`, this machine sustains **5.46 ticks/sec** — roughly **82 simulated minutes per wall second**. A 30-second review spans about **41 simulated hours**. This is why the tick-lag rule cannot be the only control, and why ADR 004 makes the proposal an envelope rather than a frozen order.

---

## 4. Two clocks

| Clock | Controls | Examples |
|---|---|---|
| **Simulation tick** | The fuel world | Tick conversion, route transit, supply arrivals, stockout horizons |
| **Wall clock** | The software | Request timeouts, cache age, circuit breakers, fault expiry, proposal expiry |

A paused simulator can sit at tick 12 for ten minutes while its API is perfectly reachable. **That is not stale.** Staleness is measured against the last successful *validated* REST refresh, tracked separately from the tick. Conversely, a running simulator can advance 164 ticks during one 30-second review, and every one of those ticks changes the world.

---

## 5. Reading checkpoints

Before coding, explain aloud without notes:

1. **Day 1** — Why REST is the truth and SSE is only a hint. What PAUSED and RUNNING mean and why they need different approval rules.
2. **Day 2** — Why `demand_liters` differs from `served_liters`. Why the multiplier is not applied twice. How two individually valid proposals can violate a shared dispatch limit.
3. **Day 3** — Why sending a POST and *then* saving its key is unsafe. What happens if three of four proposal lines are accepted. Why a quantity may fall but never rise.
4. **Day 4** — The difference between a timeout, a retry and a circuit breaker. Why a healthy simulator does not prove fresh data. Why REST polling is still needed with SSE connected.
5. **Day 5** — One complete decision and one failure, each explained in under two minutes, with the measured numbers behind them.
