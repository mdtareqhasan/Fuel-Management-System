# Fuel Supply Intelligence & Resilience Platform

An operator decision-support system over the official BUP Fuel Supply Simulator. It
reads the simulated fuel world, shows the operator which stations are at risk and
why, and requires a human approval before anything is dispatched.

**Backend:** Spring Boot 3.5.7 (Java 21) · **Frontend:** React 18 + TypeScript (Vite) ·
**Database:** PostgreSQL 16 · **Deployment:** Docker Compose · **Monitoring:** Prometheus + Grafana

**Status:** Day 1 complete (2026-11-20) · Release target 2026-11-23 · Deadline 2026-11-26

---

## Table of contents

1. [Quick start](#quick-start)
2. [Daily progress log](#daily-progress-log) — what was done, day by day
3. [The five-day plan](#the-five-day-plan-at-a-glance)
4. [How it works](#how-it-works)
5. [The rules that matter](#the-rules-that-matter)
6. [Repository layout](#repository-layout)
7. [Running the tests](#running-the-tests)
8. [Driving the simulator](#driving-the-simulator)
9. [Documentation index](#documentation-index)

---

## Quick start

```bash
cp .env.example .env
docker compose up --build -d
```

Then open:

| What | Where |
|---|---|
| Operator console | http://localhost:5173 |
| Backend API | http://localhost:8080/api/dashboard |
| System status | http://localhost:8080/api/system-status |
| Prometheus metrics | http://localhost:8080/actuator/prometheus |
| Grafana | http://localhost:3000 (admin / admin) |
| Simulator admin | http://localhost:8000/admin |

Check that everything is alive:

```bash
docker compose ps
python scripts/verify_day1.py
```

> **Port note.** This machine already runs PostgreSQL on 5432, so the compose
> database publishes on **5434**. The backend talks to it over the compose network
> by service name, so the published port only matters for host-side access. Change
> `DB_PORT` in `.env` if 5434 is taken too.

---

## Daily progress log

Every claim below is backed by a file in this repository or by a command whose
actual output is recorded in `docs/evidence/`. **Nothing here is asserted without
a check.**

---

### Day 1 — Thursday 2026-11-19 / Friday 2026-11-20 · *Contract verification and the vertical slice*

**Goal:** run the official simulator, show validated fuel data in React, and have a
live Prometheus endpoint by the end of the day. The simulator stays **paused** while
its semantics are being learned.

**Completed**

| # | Task | Proof |
|---|---|---|
| 1 | Probed every `/v1/*` endpoint against the unmodified official image | `docs/contract-notes.md`, raw responses in `docs/evidence/day-01/` |
| 2 | Measured the real tick rate — three 30-second windows | `docs/evidence/day-01/simulator-speed.txt` |
| 3 | Tested create, replay, mismatch, cancel and a stepped shipment on a disposable run | `docs/evidence/day-01/dispatch-capacity-test.txt`, `demand-and-cancel-test.txt` |
| 4 | Probed all five fault types and recorded the exact response shapes | `docs/evidence/day-01/error-catalogue.txt` |
| 5 | Wrote seven acceptance stories and thirty invariants, each mapped to the code that enforces it | `docs/acceptance-and-invariants.md` |
| 6 | Wrote eight architecture decision records | `docs/adr/ADR-001…008` |
| 7 | Drew the system diagram and explained truth ownership, the two clocks and why one backend is enough | `docs/architecture-diagram.md` |
| 8 | Built the typed simulator adapter with all three error envelopes | 9 parser tests + 7 stale-header tests |
| 9 | Built snapshot validation — "a missing value is unknown, never zero" | 14 validator tests |
| 10 | Built atomic publish, degraded mode and the coalesced mode-aware refresh | `SnapshotIngestionService`, `RefreshScheduler` |
| 11 | Built the read API — `/api/dashboard`, `/api/system-status` | `curl` returns 200 with real data |
| 12 | Built the React operator console — 4 stations × 3 fuels, mode badge, refresh age, level bars, events, shipments | Browser screenshots against the live stack |
| 13 | Added fuel and region filters, drawn from the snapshot and deep-linkable through the URL | 22 unit tests, `scripts/verify_filters.py` |
| 14 | Compose with six services, health checks and `service_healthy` ordering | `docker compose up --build -d` → 6 healthy services |
| 15 | Prometheus scrape config, Grafana datasource provisioning, Actuator endpoint live from day one | `/actuator/prometheus` returns all six `fuel_*` metrics |
| 16 | GitHub Actions CI — backend verify, frontend test + build, image build, compose smoke test | `.github/workflows/ci.yml`, 4 jobs |
| 17 | Created the issue board and one fully worked issue specification | `docs/issue-board.md`, `docs/issues/FUEL-009-filters.md` |
| 18 | Wrote the Day 1 update sheet | `docs/day-01-talking-points.md` |

**Checks actually run**

```
docker compose up --build -d     →  6 healthy services
python scripts/verify_day1.py    →  34 passed, 0 failed
python scripts/verify_filters.py →  13 passed, 0 failed
cd backend && ./mvnw verify      →  30 unit tests, all green
cd frontend && npm run test      →  22 unit tests, all green
cd frontend && npm run build     →  exit 0
```

**The ten contract questions, answered by measurement — not assumption**

| # | Question | The guide's assumption | **What we measured** |
|---|---|---|---|
| 1 | Tick rate | "take speed 8" | **5.46 ticks/sec** sustained — `SIMULATION_SPEED` is an upper bound, not a promise |
| 2 | When does depot inventory drop? | unknown | **At POST time**, not at departure |
| 3 | What does a replay return? | 200 or 201? | **201**, same allocation id, no second deduction |
| 4 | Is dispatch allowance shared across fuels? | "shared" | **Yes** — DIESEL 7000 then PETROL 7000 gives 409 |
| 5 | Validation order | nine rules together | **Dispatch capacity → destination capacity** first |
| 6 | How is stale data signalled? | a header | `X-Simulator-Stale: true` **with HTTP 200** |
| 7 | Does health imply fresh data? | no | **No** — `/v1/health` returns 200 during an `unavailable` fault |
| 8 | Demand history order | unknown | **Descending** (newest first) |
| 9 | Is `demand` the same as `served`? | use demand | **Genuinely different** — a stockout gives `served=0`, service level 3.4% |
| 10 | Departure / arrival convention | unknown | `departure = created_tick`, `expected_arrival = created + transit_ticks` |

**The two findings that changed the design**

- **Depot inventory is net, not gross** → **ADR 008.** The guide says to subtract
  pending and in-transit reservations. Measurement showed the simulator already does
  that at POST time. Following the guide's wording would have double-counted every
  reservation and starved the depot — silently. This is the single most likely place
  to introduce a silent bug, so it has its own decision record.
- **Tick staleness can never be the approval rule** → **ADR 004.** At the measured
  rate a 30-second operator review spans **~41 simulated hours** — roughly 164
  ticks. A "reject if more than one tick old" rule would block every approval during
  a live demo. Approval therefore revalidates a **reviewed envelope** against a
  fresh read instead.

**The three states, demonstrated live**

| Condition | Simulator | Data quality | Decision eligible | What the operator sees |
|---|---|---|---|---|
| Healthy | UP | **FRESH** | **yes** | live data |
| `stale_data` fault | **UP** | **STALE** | **no** | last known data, amber warning banner |
| `unavailable` fault | **DOWN** | **INVALID** | **no** | last known data, ageing |

> The middle row is the point: a simulator answering `/v1/health` perfectly is still
> serving data the application must refuse to act on. **Reachability is not
> freshness**, and the two are reported separately.

**Known gaps, stated plainly**

| Gap | Planned for |
|---|---|
| No risk engine, forecast or anomaly detection | Day 2 |
| No allocation, recommendation or approval path | Day 3 |
| No SSE listener — the 2-second REST poll carries the refresh loop | Day 3 |
| No database persistence of snapshots, commands or audit records | Day 3 |
| Grafana has the datasource but no dashboard JSON | Day 4 |
| No k6 load test | Day 4 |
| The deployment diagram (compose topology drawn out) | Day 4 |
| Detector thresholds (1.35 / 1.15 / 3 ticks) are **proposed, not tuned** | Day 5 measures them |

**Risks carried into Day 2**

| Risk | Mitigation |
|---|---|
| Getting the depot-reservation semantics wrong again in the allocator | ADR 008 plus a unit test asserting the allocator does not subtract twice |
| A forecast that looks good on paper and wrong on replay | Day 5 measures forecast error against the simulator's own ground truth, not our own baseline |
| Detecting the injection instead of the phenomenon | The detector reads observed demand, never the simulator's `demand_multiplier` |
| Scope creep into microservices or cloud | ADR 001 and ADR 007 — the brief warns against infrastructure complexity |

---

### Day 2 — Friday 2026-11-20 / Saturday 2026-11-21 · *Intelligence: understanding the world*

**Goal:** turn one valid snapshot into explainable station and depot risk, anomaly
flags and a feasible proposal with alternatives — **without changing simulator state**.

**Status:** not started. The board and the design are ready; the work begins next.

**Planned tasks**

| # | Task | Acceptance check | Issue |
|---|---|---|---|
| 1 | Domain records, `SimulationMode`, freshness configuration | Unit test output for the tick-to-hours conversion | FUEL 010 |
| 2 | Demand ingestion — bounded history, deduplicated, **sorted ascending by tick** | Forecast examples with errors | FUEL 010 |
| 3 | Naive forecast + EWMA, with a cold-start fallback and its counter | The naive baseline and the EWMA side by side | FUEL 010 |
| 4 | Station projection, inbound timing, risk labels | The worked shortage example solved **by hand** first | FUEL 011 |
| 5 | Depot projection + supply change detection from snapshot differences | The worked depot example solved by hand first | FUEL 012 |
| 6 | Deterministic ranking, route selection, quantity constraints, working reservations across fuels | A test that **catches the 4,700 litre mistake** | FUEL 013 |
| 7 | Alternatives, or `NO_ALTERNATIVE_ROUTE` where genuinely none exists | A sample recommendation with alternatives | FUEL 013 |
| 8 | Rule-based anomaly detector (R1), advisory only | Quiet series → 0 false flags; spike series → flagged, with the delay measured | FUEL 014 |

**Understanding gate before the code:** demand versus served, why the multiplier is
not applied twice, and how two individually valid proposals can violate a shared
dispatch limit.

**Evidence to produce:** one manually checked station calculation, one manually
checked depot calculation, a real simulator snapshot, passing invariant checks and a
printed recommendation with alternatives. Algorithm quality stays **unmeasured**
until the Day 5 controlled comparison.

---

### Day 3 — Saturday 2026-11-21 / Sunday 2026-11-22 · *The operator decision loop*

**Goal:** persist, show, review, approve in either mode, submit once and follow the
official shipment lifecycle.

**Status:** not started.

**Planned tasks**

| # | Task | Acceptance check | Issue |
|---|---|---|---|
| 1 | Persist snapshots, recommendations and anomaly flags with migrations | Migration list, Flyway runs clean | FUEL 015 |
| 2 | React risk table, anomaly badges, recommendation panel with alternatives | Screenshot | FUEL 015 |
| 3 | Approval transaction, stable command keys, request persistence, unique line constraint | Duplicate approval test output | FUEL 016 |
| 4 | Single command worker with UNKNOWN reconciliation | The "accepted but response lost" case, via a controlled adapter fixture | FUEL 016 |
| 5 | Mode-aware freshness policy and revalidation envelope | Same-tick, after-step and after-route-disruption approval tests | FUEL 017 |
| 6 | Review dialog, shipment ledger, partial completion, server-enforced authorization | A recording from risk → approval → arrival | FUEL 017 |

**Understanding gate before the code:** the crash window after a POST, why the key
is stored **before** the POST, and why a quantity may fall but never rise.

---

### Day 4 — Sunday 2026-11-22 / Monday 2026-11-23 · *Resilience and observability*

**Goal:** retain a useful operator view during failures, guard writes, recover
visibly, and show health with measured p95 latency and error rate.

**Status:** not started.

**Planned tasks**

| # | Task | Acceptance check | Issue |
|---|---|---|---|
| 1 | Stale handling, deadlines, bounded retries, circuit breaker | The 30-second unavailable fault timeline | FUEL 018 |
| 2 | Server-side stale approval guard | The guard proven on the server, not only in the UI | FUEL 018 |
| 3 | SSE reader, coalesced refresh, reconnect, REST polling fallback | A log excerpt of reconnect and full refresh | FUEL 019 |
| 4 | Fault injection: unavailable, stale, latency, forecast failure, backend restart | The exact inputs and results saved | FUEL 020 |
| 5 | Crisis injections: demand spike, route disruption, shipment delay at known ticks | Per-scenario evidence with the anomaly flag and depot window | FUEL 020 |
| 6 | Structured logs, the intelligence and approval metrics, Prometheus scrape config, essential Grafana panels | The status panel with p95 and error rate during a fault | FUEL 021 |
| 7 | The deployment diagram, clean Compose startup, a recorded incident | The recording with its run id | FUEL 021 |

**Understanding gate before the code:** timeout versus retry versus circuit breaker,
why a healthy simulator does not prove fresh data, and why REST polling is still
needed with SSE connected.

---

### Day 5 — Monday 2026-11-23 · *Validate and rehearse the release*

**Goal:** a frozen, demonstrable release with measured evidence and a rehearsed demo.

**Status:** not started. November 24–26 remains a repair and rehearsal buffer.

**Planned tasks**

| # | Task | Acceptance check | Issue |
|---|---|---|---|
| 1 | Baseline vs candidate under the same deterministic normal, spike, route and combined scenarios, PAUSED and stepped | `policy-comparison.json` and `anomaly-replay.txt` | FUEL 022 |
| 2 | Dashboard load stages in PAUSED, and the 25-user stage again in RUNNING | `load-summary.json` with the limiting component identified | FUEL 023 |
| 3 | CI, clean clone startup, smoke checks, packaged images, migration behaviour, command reconciliation | A green CI run | FUEL 024 |
| 4 | Finish the README, ADRs, limitations, evidence index, runbook and daily reports | A reviewer can reproduce the run | FUEL 024 |
| 5 | Rehearse the eight-minute demo three times, including the RUNNING approval segment | Rehearsal notes and the tagged commit | FUEL 025 |

**Understanding gate before the demo:** one complete decision and one failure, each
explained in under two minutes, with the measured numbers behind them.

**Cut order if the schedule slips:**
LLM features → custom MCP → Grafana polish → alternative pinning → map polish →
agent lab exercises → detector rule R2.

**Never cut:** official integration, the usable dashboard, deterministic
intelligence, mode-aware reviewed writes, depot and station risk, the metrics
endpoint with the status panel, cached failure mode, repeatable startup, measured
evidence, demo rehearsal.

---

## The five-day plan at a glance

| Day | Date | Theme | Delivers | Status |
|---|---|---|---|---|
| 1 | 2026-11-19/20 | Contract and vertical slice | Measured contract notes, 8 ADRs, 30 invariants, working console, Prometheus, CI | **done** |
| 2 | 2026-11-20/21 | Intelligence | Forecast, station and depot risk, anomaly detector, feasible allocation with alternatives | next |
| 3 | 2026-11-21/22 | Decision loop | Persistence, risk table, approval transaction, command worker, ledger | planned |
| 4 | 2026-11-22/23 | Resilience | Fault handling, circuit breaker, SSE, crisis injections, Grafana panels, status panel | planned |
| 5 | 2026-11-23 | Release | Baseline comparison, load test, CI green, README and runbook, three demo rehearsals | planned |

---

## How it works

```
Browser ──poll──▶ Backend ──REST──▶ Simulator
                    │  │
                    │  └── validated snapshot (atomic swap)
                    └── metrics ──▶ Prometheus ──▶ Grafana
```

- The **backend** owns one refresh loop against the simulator. The browser reads the
  cached, validated view, so a hundred dashboards cause one polling loop, not a
  hundred.
- A snapshot is **published atomically** and only after full validation. A reader
  sees the whole previous view or the whole new one.
- On a failed read the last valid snapshot **stays visible** with a warning, and
  approval is blocked. Losing the view would be worse than showing it honestly.
- The simulator owns the truth. The application stores *observations* of it, never
  an alternative world.

### The two states that must never be confused

| | Meaning |
|---|---|
| **Simulator UP / DOWN** | Can we reach the simulator at all |
| **Data FRESH / STALE / INVALID** | Is the view we hold fresh enough to act on |

`/v1/health` bypasses fault injection, so a simulator can be perfectly healthy
while every station read fails. Both are reported separately, and the status panel
shows them as separate rows.

### The two clocks

| | **Simulation tick** | **Wall clock** |
|---|---|---|
| Measures | the fuel world | the software |
| Used for | transit time, supply arrival, stockout horizon | timeouts, cache age, circuit breakers, proposal expiry |
| Rate | 1 tick = 15 simulated minutes; **measured ~5.46 ticks/sec** | 1 second |

A paused simulator can sit at tick 12 for ten minutes while its API answers
perfectly. **That is not stale.** Staleness is measured against the last successful
*validated REST refresh*, tracked separately from the tick.

---

## The rules that matter

These are enforced in code, not just documented. See `docs/acceptance-and-invariants.md`
for the full list of 30 invariants and `docs/adr/` for the reasoning.

1. **REST is the truth; SSE is only a hint.** Re-read after any stream event.
2. **Never allocate from stale or invalid data.** The server-side guard is the
   control — a disabled button is not.
3. **Depot inventory is already net of reservations.** The simulator deducts at
   dispatch time, so subtracting pending and in-transit again double-counts. See
   ADR 008.
4. **Dispatch allowance is per depot, shared across every fuel and destination.**
5. **Forecast on `demand_liters`, never `served_liters`.** Sort demand ascending by
   tick before any EWMA — the API returns newest first.
6. **Operator approval is server-enforced.** No auto-approval threshold exists.
7. **A missing value is unknown, never zero.** The UI shows "Unknown".
8. **Unique ids never become metric labels.**

---

## Repository layout

```
backend/          Spring Boot backend, 9 packages with explicit boundaries
  src/main/java/com/fuelplatform/
    simulator/    Typed DTOs, REST client, error parsing, metrics
    ingestion/    Validation, atomic snapshot store, refresh scheduling
    domain/       WorldSnapshot, SimulationMode, DataQuality
    risk/         Demand rate, stockout trajectory, anomaly detection
    allocation/   Feasibility, policy, deterministic ranking
    decision/     Recommendation lifecycle and approval
    command/      Durable intent, worker lease, retry, reconciliation
    audit/        Decision and operator activity trail
    web/          Controllers, response shaping
    observability/ Component status, metric helpers
frontend/         React + TypeScript operator console
  src/filters.ts  View filters (fuel, region) — React-free and unit tested
infra/            Prometheus and Grafana provisioning
scripts/          Verification and simulator control
docs/             ADRs, contract notes, acceptance criteria, evidence
  evidence/day-01/ Recorded raw responses, fault catalogue, speed measurement
tests/            Integration tests and k6 workloads
```

---

## Running the tests

```bash
# Backend: 30 unit tests, no Docker and no simulator needed
cd backend && ./mvnw verify

# Frontend: 22 unit tests, then the strict type check and production build
cd frontend && npm ci && npm run test && npm run build

# End-to-end against the running stack: 34 checks
python scripts/verify_day1.py

# The filter behaviour on its own: 13 checks
python scripts/verify_filters.py
```

---

## Driving the simulator

```bash
python scripts/sim_control.py instance                  # current clock and mode
python scripts/sim_control.py step 5                    # advance 5 ticks
python scripts/sim_control.py run                       # switch to RUNNING
python scripts/sim_control.py pause                     # switch to PAUSED
python scripts/sim_control.py inject-fault stale_data 15
python scripts/sim_control.py inject-fault unavailable 30
python scripts/sim_control.py clear-faults
python scripts/sim_control.py event demand_spike 0 4 '{"multiplier": 1.8}'
```

---

## Documentation index

| Document | What it holds |
|---|---|
| `docs/architecture-diagram.md` | The one system diagram, truth ownership, the two clocks, why one backend |
| `docs/acceptance-and-invariants.md` | Seven acceptance stories and thirty invariants |
| `docs/contract-notes.md` | **Measured** simulator behaviour, not assumptions |
| `docs/adr/` | Eight architecture decision records with the rejected alternatives |
| `docs/issue-board.md` | The bounded issue board, the issue cycle and the cut order |
| `docs/issues/` | Worked issue specifications |
| `docs/day-01-update.md` | The Day 1 update document |
| `docs/day-01-talking-points.md` | The Day 1 update sheet — what to say, with the evidence |
| `docs/agent-activity-log.md` | Append-only record of what was done, measured and decided |
| `docs/current-state.md` | What runs right now, and the next steps |
| `docs/evidence/day-01/` | Day 1 verification report, filter report and recorded raw responses |
| `CLAUDE.md` | Working agreements for anyone (or any agent) editing this repo |

---

## Licence and attribution

The fuel supply simulator is the official
`asifmahmoud414/bup-fuel-supply-simulator:1.0.0` image, used **unmodified**.
This repository contains only the application built against it.

The organizers' brief and integration guide are kept in the repository as markdown
so they stay searchable and diffable. The original PDF and DOCX binaries were
removed once their content was verified to be fully covered by those copies.
