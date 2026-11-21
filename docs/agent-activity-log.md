# Agent activity log

An append-only record of what was done, what was measured, and what was decided.
Written for the handover and for the demo narrative.

---

## 2026-11-20 — Day 1, contract verification and vertical slice

### Session 1 — contract probing

**Goal:** answer the simulator's unknowns by measurement instead of assumption.

**Actions:**
- Started the official image `asifmahmoud414/bup-fuel-supply-simulator:1.0.0` unmodified.
- Probed every `/v1/*` endpoint and recorded the raw responses.
- Measured the tick rate three times over 30 seconds each.
- Created allocations to observe depot inventory, dispatch capacity and idempotency
  behaviour.
- Injected each fault type and recorded the response shape.

**Findings:** the ten answers in `docs/day-01-update.md` §2. The two that changed
the design were the depot-inventory deduction point (ADR 008) and the measured tick
rate, which invalidated a tick-based approval rule (ADR 004).

**Evidence:** `docs/evidence/day-01/`, `docs/contract-notes.md`.

---

### Session 2 — decisions on paper

**Goal:** write down the decisions before implementing them, so the reasoning
survives the deadline.

**Actions:**
- Wrote ADR 001 through ADR 007.
- Wrote `docs/acceptance-and-invariants.md` with seven acceptance stories and
  thirty invariants, each mapped to the code or test that enforces it.
- Wrote `CLAUDE.md`, `.gitignore`, `.env.example` and `README.md`.

**Note:** ADR 008 was added later, in session 3, when the allocator work made the
depot semantics concrete enough to be worth a separate record.

---

### Session 3 — backend scaffold and the simulator adapter

**Goal:** a typed, tested adapter that cannot be fooled by the simulator's error
shapes.

**Actions:**
- Spring Boot 3.5.7 with the Maven Wrapper, pinned to Maven 3.9.11 and Java 21.
- Typed DTOs for every simulator payload.
- `SimulatorErrorParser` discriminating the three observed error envelopes.
- `SimulatorClient` carrying the stale header through to every caller.
- `SnapshotValidator` enforcing "a missing value is unknown, never zero".
- `SnapshotIngestionService` publishing atomically and degrading to the last
  valid snapshot on failure.
- `RefreshScheduler` with a coalesced, mode-aware refresh.
- `/api/dashboard` and `/api/system-status`.
- 30 unit tests: 9 for the error parser, 7 for the stale header, 14 for the
  validator.

**Decisions during implementation:**
- The adapter reads the body as text and parses it with an explicit `JavaType`.
  An anonymous `ParameterizedTypeReference<List<T>>` loses the element type and
  Jackson hands back `LinkedHashMap` instead of the records. The failure appeared
  at runtime as a `ClassCastException` in the ingestion loop.
- `SimulatorMetrics` lives in the `simulator` package rather than `observability`,
  so the client can record its own calls without a dependency cycle.
- The database probe is a field on the config class, not an injected bean, because
  a `@Scheduled` method may not take arguments.

---

### Session 4 — frontend console

**Goal:** an operator screen where nothing is invented.

**Actions:**
- React 18 + TypeScript + Vite, `strict` mode with `noUncheckedIndexedAccess`.
- A dark operations console: mode badge, freshness badge, refresh age, tick
  bounds, p95 latency, error rate, 4 station cards × 3 fuels with level bars,
  2 depot cards, active events, recent shipments.
- `format.ts` renders every unknown value as "Unknown", never as 0.
- Nginx image that proxies `/api` so the browser talks to one origin.

**Decision during implementation:** the whole API was moved to `snake_case`
because the simulator speaks `snake_case` and the responses are mostly
simulator-shaped. The mixed convention made several fields silently `null` and
the UI rendered them as "Unknown" — the exact symptom the unknown-never-zero rule
is meant to prevent. TypeScript's strict mode caught every occurrence of it.

---

### Session 5 — deployment and end-to-end verification

**Goal:** one command starts the whole system, and a script proves it works.

**Actions:**
- `compose.yaml` with six services, health checks and `service_healthy`
  dependencies, all ports bound to loopback.
- Multi-stage Dockerfiles for the backend and the frontend.
- Prometheus scrape config and Grafana datasource provisioning.
- GitHub Actions CI: backend verify, frontend build, image build, compose smoke test.
- `scripts/sim_control.py` to drive the simulator's admin API.
- `scripts/verify_day1.py` running 33 checks against the live stack.

**Verification:** 33 passed, 0 failed. The three fault states were demonstrated
live in the browser: FRESH → STALE with a warning banner and blocked decisions →
INVALID with the simulator DOWN → recovery to FRESH.

**Environment findings, recorded so they are not rediscovered:**
- PostgreSQL already ran on 5432 on this machine, so the compose database
  publishes on 5434.
- The simulator image has no `wget` or `curl`, so its health check uses Python's
  `urllib`.
- The simulator's admin API is documented in `/openapi.json`; `FaultCreate` takes
  `type` and `duration_seconds`, and `EventCreate` takes `type`, `start_tick`,
  `duration_ticks` and `parameters`.

---

## 2026-11-21 — Day 2, intelligence and risk

### Session 5 — measurement before code

**Goal:** get the denominator right before building a detector on top of it.

**Actions:**
- Re-measured the profile constants against the running simulator instead of
  transcribing them from the guide.
- Wrote `scripts/measure_hour_factor.py`, then `scripts/measure_hour_factor_clean.py`
  after the first measurement turned out to have been taken with an event active.
- Versioned the measured constants into `application.yml` under
  `app.simulator-profile.profiles`.

**Findings:**
- The guide's highway busy window is **06–09**, six hours wide, not 07–09. With
  the narrow window, hour 6 produced a ratio of exactly 2.0 and a permanent false
  alarm on `station-karnaphuli`.
- 97 of 98 hours match the documented table. Highway hour 5 is the exception
  (implied 0.817 against a documented 0.75), giving a ratio of **1.09** against a
  threshold of **1.35** — a description inaccuracy, not a detection fault.
- The first measurement was contaminated: a `demand_spike` was still `ACTIVE` in
  region-dhaka, which inflated exactly the two region-dhaka stations by the
  spike multiplier. The script now refuses to report while an event is active.

**Evidence:** `docs/evidence/day-02/hour-factor-measurement.txt`,
`docs/evidence/day-02/profile-measurement.txt`.

---

### Session 6 — the intelligence layer

**Goal:** turn a validated snapshot into an explainable answer.

**Actions:**
- Built `risk`: `DemandHistoryReader`, `DemandForecaster`, `DemandAnomalyDetector`,
  `StationRiskProjector`, `DepotRiskProjector`, `RiskEngine`.
- Built `allocation`: `AllocationPlanner`, `AllocationPlan`, `AllocationProposal`.
- Added `ForecastProperties`, `RiskProperties` and `SimulatorProfileProperties`
  so the policy is configuration, not code.
- Exposed `/api/risk` and built the operator panel in `frontend/src/RiskPanel.tsx`.
- 80 backend tests, up from 30.

**Bugs the tests caught:**
- The projection used `stock < 0` to detect an empty tank, so a tank at exactly
  zero reported its stockout **one tick late**. Fixed to `stock <= 0`.
- The alternative branch in the allocator was **unreachable**: the loop broke as
  soon as the need was met. Alternatives are now computed after the primary line
  is settled.
- The alternatives were computed but **never attached** to the proposal, because
  the record is immutable and the `withAlternatives(...)` rebuild was missing. The
  code compiled, the tests passed, and the panel was silently empty.
- The detector cleared a flag on **one** calm tick, but the guide's R3 says
  three. One quiet tick in the middle of a spike is ordinary noise, so a real
  spike could be dropped entirely.
- The flag reported the **last breach** rather than the tick R1 tripped on. A
  spike that started fifteen ticks ago looked like it was detected fifteen ticks
  late. It now carries the tick the rule actually fired, and the reason says so.

**Evidence:** `docs/evidence/day-02/`.

---

### Session 7 — the live run found what the unit tests could not

**Goal:** check the plan against the real world, not against a fixture.

**Actions:**
- Ran `scripts/verify_dispatch_limit.py` against the live backend.
- Ran `scripts/verify_anomaly_detection.py`, which calculates the ratios
  **independently** of the backend and then cross-checks the backend's flags
  against its own result.
- Wrote `scripts/diagnose_unmet_needs.py` to make the unmet-need list readable.

**Findings:**
- The shared dispatch limit holds on the live plan: every depot is within its
  allowance, and every proposal uses a real route with matching endpoints.
- **0 false flags** in a quiet run across 4 stations and 72 observations.
- **R1 and R2 both fire within 2 ticks** of an injected spike.
- The unmet-need list had **18 entries, 6 of them duplicated, 5 of them phantom**.
  One need could produce two conflicting explanations, and a sub-litre rounding
  residue was being reported as a shortfall. After the fixes: **7 entries, none
  duplicated, none phantom**, and all seven are genuine `DISPATCH_LIMIT` cases.

**The lesson worth keeping:** the unit tests passed the whole time. The bugs that
mattered most were only visible when the plan ran against the live world and
someone read the output. A test that asserts the code does what the code does is
not the same as a check that reads the system's actual output.

---

## Handover notes

**Where things stand:** a running vertical slice with 34 automated end-to-end
checks and 52 unit tests, plus the full Day 2 intelligence layer with 83 backend
tests. The approval and command layers are not started.

**Read first:**
1. `docs/contract-notes.md` — measured behaviour, not assumptions.
2. `docs/acceptance-and-invariants.md` — the thirty-four invariants.
3. `docs/adr/ADR-008-depot-inventory-is-net.md` — the easiest thing to get wrong.
4. `docs/architecture-diagram.md` — the one picture of the whole system.
5. `docs/daily/Day-02.md` — what Day 2 built and why.

**The four traps to avoid:**
1. Subtracting pending and in-transit allocations from depot inventory. The
   simulator already did it. See ADR 008.
2. Trusting HTTP 200. Check `X-Simulator-Stale` on every `/v1/*` GET.
3. Computing an EWMA over demand rows in the order the API returned them. The API
   returns newest first; the forecast needs oldest first. See INV-20.
4. Trusting a transcribed constant. The profile constants were measured, and the
   measurement caught a transcription error in the guide. See ADR 005.

**Local commands:**
```bash
docker compose up --build -d      # start everything
python scripts/verify_day1.py     # 34 checks
python scripts/verify_filters.py  # 13 filter checks
python scripts/verify_anomaly_detection.py   # the Day 2 detector measurement
python scripts/verify_dispatch_limit.py      # the Day 2 allocation invariant
python scripts/sim_control.py --help
cd backend && ./mvnw verify       # 80 unit tests
cd frontend && npm run test       # 22 unit tests
cd frontend && npm run build      # type check + production build
```
