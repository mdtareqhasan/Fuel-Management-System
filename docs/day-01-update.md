# Day 1 Update — Contract Verification and the Vertical Slice

**Date:** 2026-11-20 (Friday) · **Release target:** 2026-11-23 · **Deadline:** 2026-11-26
**Stack:** Spring Boot 3.5.7 (Java 21) · React 18 + TypeScript (Vite) · PostgreSQL 16 · Docker Compose · Prometheus + Grafana

---

## 1. What exists now

A running vertical slice. From a single `docker compose up --build -d`, the stack
starts six services and the operator console renders the simulated fuel world.

| Component | State |
|---|---|
| Backend (Spring Boot 3.5.7, Java 21, Maven Wrapper) | Running, 30 unit tests green |
| Typed simulator adapter with 3-shape error parsing | Complete |
| Snapshot validation + atomic publish + mode-aware refresh | Complete |
| `/api/dashboard`, `/api/system-status`, Actuator + Prometheus | Complete |
| React + TypeScript console: 4 stations × 3 fuels, mode badge, refresh age | Complete |
| Docker Compose, multi-stage Dockerfiles, Prometheus, Grafana, CI | Complete |
| Risk engine, allocation, approval, command worker | **Not started — days 2 and 3** |

---

## 2. The contract questions we answered by measurement

The implementation guide was explicit that the simulator's behaviour had to be
measured rather than assumed. Every question below was answered by running the
official image and reading the actual HTTP responses. The raw responses are in
`docs/evidence/day-01/`.

| # | Question | The guide's assumption | **What we measured** |
|---|---|---|---|
| 1 | Tick rate | "take speed 8" | **5.46 ticks/sec** sustained; `SIMULATION_SPEED` is an upper bound, not a promise |
| 2 | When does depot inventory drop? | unknown | **At POST time**, not at departure |
| 3 | What does a replay return? | 200 or 201? | **201**, same allocation id, no second deduction |
| 4 | Is dispatch allowance shared across fuels? | "shared" | **Yes** — DIESEL 7000 then PETROL 7000 gives 409 |
| 5 | Validation order | nine rules together | **Dispatch capacity → destination capacity** first |
| 6 | How is stale data signalled? | a header | `X-Simulator-Stale: true` **with HTTP 200** |
| 7 | Does health imply fresh data? | no | **No** — `/v1/health` returns 200 during an `unavailable` fault |
| 8 | Demand history order | unknown | **Descending** (newest first) |
| 9 | Is `demand` the same as `served`? | use demand | **Genuinely different** — stockout gives `served=0`, service level 3.4% |
| 10 | Departure/arrival convention | unknown | `departure = created_tick`, `expected_arrival = created + transit_ticks` |

### Why these findings changed the design

**Depot inventory is net, not gross.** The guide said to subtract pending and
in-transit reservations from depot inventory. Measurement showed the simulator
already does that at POST time. Implementing the guide's wording would have
double-counted every reservation and starved the depot. Recorded as
**ADR 008**; it is the single most likely place to introduce a silent bug, so it
has its own decision record.

**Tick staleness can never be the approval rule.** At the measured rate, a 30
second operator review spans **~41 simulated hours** — roughly 164 ticks. A
"reject if more than one tick old" rule would block every approval during a live
demo. Approval therefore revalidates a **reviewed envelope** against a fresh
read instead. Recorded as **ADR 004**.

**Three error envelopes, one field name, two JSON types.** `detail` is an object
in a domain error and an array in a Pydantic 422, and injected faults use a
top-level `error` key. A Jackson record bound to `detail` deserialises one shape
and throws on the other. The adapter keeps `detail` as a raw node and
discriminates explicitly. Nine unit tests pin all three shapes.

---

## 3. Architecture decisions recorded today

Eight ADRs in `docs/adr/`, each with the context, the decision, the rejected
alternatives and the consequences.

| ADR | Decision |
|---|---|
| 001 | Modular monolith with nine explicit package boundaries |
| 002 | The simulator owns the truth; the application stores observations |
| 003 | Operator approval is mandatory and server-enforced; no auto-approve threshold |
| 004 | Mode-aware approval: the proposal is an envelope, revalidated against a fresh read |
| 005 | Detect with rules before models; the detector is advisory |
| 006 | Metrics first — the Prometheus endpoint ships on day one |
| 007 | Local Docker Compose, no cloud |
| 008 | Depot inventory is already net of reservations |

**ADR 006 deserves a note.** Observability is 10% of the rubric, and the natural
order — build features, add metrics at the end — fails here for a specific
reason: at the end there is no time, so the features ship unmeasured and the day-5
policy comparison has no numbers to compare. Each metric therefore ships in the
same change as the feature it measures.

---

## 4. Verification evidence

`python scripts/verify_day1.py` runs 33 checks against the live stack and writes
`docs/evidence/day-01/verification-report.md`. **33 passed, 0 failed.**

| Group | What it proves |
|---|---|
| Stack reachability | all six services answer |
| Dashboard content | 4 stations, 2 depots, 6 routes, 2 regions, all 3 fuels, non-zero inventory, dispatch capacity present |
| Stale handling | the simulator returns **HTTP 200 with the stale header**; the backend marks the view STALE, blocks decisions, and **keeps the last known data visible** |
| Recovery | after the fault clears, the view returns to FRESH and decisions re-enable |
| Unavailable fault | simulator DOWN, view INVALID, decisions blocked, cached view still served |
| Metrics | all six custom `fuel_*` metrics present in `/actuator/prometheus` |

### The three states, demonstrated live

| Condition | Simulator | Data quality | Decision eligible | View |
|---|---|---|---|---|
| Healthy | UP | FRESH | yes | live |
| `stale_data` fault | **UP** | **STALE** | **no** | last known, with a warning banner |
| `unavailable` fault | **DOWN** | **INVALID** | **no** | last known, ageing |

The middle row is the important one: a simulator that answers `/v1/health`
perfectly is still serving data the application must refuse to act on.

---

## 5. Engineering decisions worth explaining in the demo

**The browser never talks to the simulator.** It reads the backend's cached,
validated view. One refresh loop serves every dashboard.

**Snapshots publish atomically.** A single reference swap. A reader sees the whole
previous world or the whole new one — never a half-updated mix.

**One naming rule for the whole API.** `snake_case` everywhere, because the
simulator speaks `snake_case` and the API is mostly simulator-shaped. Mixing the
two in one payload made the frontend render silent `null` fields as "Unknown" —
the exact failure the "unknown is never zero" rule exists to prevent. Caught
during integration, fixed at the source.

**A missing value is unknown, never zero.** Enforced in the validator (rejects the
snapshot), in the API (nullable fields), and in the UI ("Unknown"). Treating an
absent fuel level as zero would tell the operator a station is empty when the
truth is that we were not told.

**Bounded work.** The API is snake_case, the refresh loop is coalesced so a burst
of tick events cannot build a backlog, and the metric label set is fixed and
small, because a label per station would create unbounded time series.

---

## 6. Known gaps, stated plainly

| Gap | When |
|---|---|
| No risk engine, forecast or anomaly detection yet | Day 2 |
| No allocation, recommendation or approval path yet | Day 3 |
| No SSE listener — the 2 second REST poll carries the refresh loop | Day 3 |
| No database persistence of snapshots, commands or audit records | Day 3 |
| Grafana has the datasource but no dashboard JSON yet | Day 4 |
| No k6 load test | Day 4 |
| Detector thresholds (1.35 / 1.15 / 3 ticks) are **proposed, not tuned** | Day 5 measures them |

The thresholds are deliberately called out rather than hidden: they will be
reported as measured false-flag counts and detection delays on day 5.

---

## 7. Next — Day 2, "Intelligence: understanding the world"

1. **Demand rate** from `demand_liters`, sorted ascending by tick first (INV-20).
2. **Forecast** with an EWMA baseline and a seasonal component, plus a fallback
   path and a `fuel_forecast_fallback_total` counter.
3. **Risk**: stockout trajectory per station and fuel, with the horizon in
   simulated hours.
4. **Anomaly detection** by ratio rule (ADR 005), advisory only, with unit tests
   for both a quiet series and a spiked series.
5. **Demand fetching**: one `limit` is shared across all three fuels, so rows are
   split by fuel after the fetch (INV-21).

### Risks we are carrying

| Risk | Mitigation |
|---|---|
| Getting the depot-reservation semantics wrong again | ADR 008 plus a unit test that asserts the allocator does not subtract twice |
| Forecast looking good on paper and wrong on replay | Day 5 measures forecast error against the simulator's own ground truth, not against our own baseline |
| Detecting the injection instead of the phenomenon | The detector reads observed demand, never the simulator's `demand_multiplier` |
| Scope creep into microservices or cloud | ADR 001 and ADR 007; the brief warns against infrastructure complexity |
