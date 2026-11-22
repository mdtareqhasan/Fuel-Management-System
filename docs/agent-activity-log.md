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

## 2026-11-22 — Day 3, persistence and operator decisions

### Session 8 — the durable record

**Goal:** make a decision survive the process, and make it impossible to write the same shipment twice.

**Actions:**
- Wrote `V1__day3_persistence_and_decisions.sql`: `snapshots`, `proposals`, `proposal_lines`, `commands`, `audit_events`.
- Built the `decision` package: `Proposal`, `ProposalLine`, `ProposalFactory`, `ProposalService`, `ProposalRepository`, `SnapshotRepository`, `Revalidator`, `FreshnessPolicy`, `ApprovalService`, `ApprovalResult`, `DecisionScheduler`.
- Built the `command` package: `Command`, `CommandRepository`, `CommandWorker`, `ShipmentTracker`.
- Built the `audit` package: `AuditRepository`.
- Exposed `/api/proposals`, `/api/proposals/{id}`, `/api/proposals/{id}/approve`, `/api/shipments`, `/api/shipments/reconcile`.
- Built the frontend decision surface: `DecisionPanel.tsx`, `useDecisions.ts`, `decisionTypes.ts`.
- 121 backend tests, up from 83. 31 frontend tests, up from 22.

**Decisions during implementation:**
- The repositories are plain JDBC, not JPA entities. The records are immutable and the SQL is explicit, so the constraints are visible in the code rather than hidden in an ORM mapping. A half-built JPA entity approach was abandoned mid-day.
- `run_id` is `TEXT`, not `UUID`. The domain carries it as an opaque string, and storing it as a UUID put a conversion at every repository boundary for no benefit. The schema and the repositories were changed together after a `ClassCastException`.
- The dispatch allowance is re-reserved at approval time by `Revalidator.DispatchBudget`, shared across every line and every fuel/destination, including fuel already dispatched at the current tick. Without this, a six-line proposal produced six shipments the simulator refused with `DISPATCH_CAPACITY_EXCEEDED`.

**Evidence:** `docs/evidence/day-03/`.

---

### Session 9 — the UI was not showing, and it was not the code

**Goal:** answer "the UI shows nothing" with a cause, not a guess.

**Findings:**
- The `fuel-frontend` image was 23 hours old and had never been rebuilt with the Day 3 UI. The backend had been rebuilt; the frontend had not. `docker compose build frontend` followed by `docker compose up -d frontend` fixed it.
- The route inventory table was missing from the dashboard for the same reason — it had been written on Day 3 but was never in the built bundle.
- The routes were live the whole time. `/api/proposals`, `/api/shipments` and `/api/risk` returned 200 through the frontend proxy; `/api/dashboard` and `/api/system-status` did too. The 404s in the first route probe were my own wrong guesses at route names, not missing endpoints.

**A real layout bug, found by looking at the rendered page:**
- The Day 3 tables used the CSS class `grid`, which already existed as a **CSS grid container** for the card layouts in `App.tsx` and `RiskPanel.tsx`. The Day 3 rules redefined it as a table, so the `<table>` rendered as a grid: headers bunched left, cells pushed far right. The class was renamed to `.decision-table`, and `scripts/verify_frontend_day3_css.py` now guards both halves — the table rules are present *and* `.grid` is still `display: grid`.

---

### Session 10 — the audit bug that hid the truth

**Goal:** get the Day 3 verifier from 16/17 to 17/17.

**The failure:** the lost-response check reported `status=APPROVED attempts=0 last_error=None`. The command should have been `UNKNOWN`. The backend log showed why:

```
ERROR: invalid input syntax for type json
  Where: JSON data, line 1: ...SEL","error":"I/O error on POST
  request for "http...
  at com.fuelplatform.audit.AuditRepository.record(AuditRepository.java:54)
  at com.fuelplatform.command.CommandWorker.send(CommandWorker.java:158)
```

**Root cause:** the simulator's transport error message contains a double-quoted URL. The audit details were built by string concatenation, so that message produced invalid JSON. The insert failed — and because the audit insert shared a transaction with the command's status update, the failure rolled the status update back too. The command stayed `APPROVED` when the truth was `UNKNOWN`.

That is the worst possible failure for this system. `UNKNOWN` means *the shipment may exist*; `APPROVED` means *not sent yet*. An operator reading `APPROVED` would approve again, and the second approval would create a second shipment — exactly the duplicate the idempotency key exists to prevent.

**Two fixes:**
1. The audit details are now a `Map` serialised by Jackson. Jackson cannot produce invalid JSON, so the class of bug is closed rather than patched. `AuditRepositoryTest` reproduces the exact message.
2. `AuditRepository.record` now runs with `Propagation.REQUIRES_NEW`. The audit write commits independently of the decision it describes. This also fixed a quieter bug: every **refused** approval had been rolling its own `APPROVAL_REJECTED` record back with the exception, so the trail showed only the successes. An audit that records only successes is not an audit.

**Result:** the verifier now records `status=UNKNOWN attempts=1` with the quoted URL preserved intact, and reconciling produces no second shipment. **17/17.**

**Evidence:** `docs/evidence/day-03/backend-log-during-verifier.txt`,
`docs/evidence/day-03/end-to-end-verification.txt`.

---

## Handover notes

**Where things stand:** a running vertical slice with 34 automated Day 1 end-to-end checks, the full Day 2 intelligence layer, and the complete Day 3 decision loop — **17/17 Day 3 end-to-end checks, 126 backend tests, 31 frontend tests**, a clean production build, and a documented schema.

**Read first:**
1. `docs/contract-notes.md` — measured behaviour, not assumptions.
2. `docs/acceptance-and-invariants.md` — the invariants, INV-37 to INV-46 are the Day 3 rules.
3. `docs/adr/ADR-008-depot-inventory-is-net.md` — the easiest thing to get wrong.
4. `docs/adr/ADR-009-decision-record-and-shipment-ledger.md` — why the audit write is its own transaction.
5. `docs/architecture-diagram.md` — the one picture of the whole system.
6. `docs/daily/Day-03.md` — what Day 3 built and what it broke.

**The five traps to avoid:**
1. Subtracting pending and in-transit allocations from depot inventory. The simulator already did it. See ADR 008.
2. Trusting HTTP 200. Check `X-Simulator-Stale` on every `/v1/*` GET.
3. Computing an EWMA over demand rows in the order the API returned them. The API returns newest first; the forecast needs oldest first. See INV-20.
4. Trusting a transcribed constant. The profile constants were measured, and the measurement caught a transcription error in the guide. See ADR 005.
5. Building JSON by string concatenation from a simulator message. It contains a double-quoted URL, and the resulting invalid JSON will roll back whatever it shares a transaction with. See ADR 009.

**Local commands:**
```bash
docker compose up --build -d      # start everything
docker compose build frontend     # REQUIRED after a frontend change
docker compose up -d frontend     # ...or the old bundle keeps being served
python scripts/verify_day1.py     # 34 checks
python scripts/verify_filters.py  # 13 filter checks
python scripts/verify_anomaly_detection.py   # the Day 2 detector measurement
python scripts/verify_dispatch_limit.py      # the Day 2 allocation invariant
python scripts/verify_day3_end_to_end.py     # 17 Day 3 checks (mutates the stack)
python scripts/verify_frontend_day3_ui.py    # 12 UI strings in the served bundle
python scripts/verify_frontend_day3_css.py   # the table rules and the card grid
python scripts/inspect_day3_schema.py        # the schema as the database holds it
python scripts/sim_control.py --help
cd backend && ./mvnw verify       # 126 unit tests
cd frontend && npm run test       # 31 unit tests
cd frontend && npm run build      # type check + production build
```
