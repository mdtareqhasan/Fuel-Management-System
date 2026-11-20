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

## Handover notes

**Where things stand:** a running vertical slice with 34 automated end-to-end
checks and 52 unit tests. The risk engine, allocation, approval and command
layers are not started.

**Read first:**
1. `docs/contract-notes.md` — measured behaviour, not assumptions.
2. `docs/acceptance-and-invariants.md` — the thirty invariants.
3. `docs/adr/ADR-008-depot-inventory-is-net.md` — the easiest thing to get wrong.
4. `docs/architecture-diagram.md` — the one picture of the whole system.

**The three traps to avoid:**
1. Subtracting pending and in-transit allocations from depot inventory. The
   simulator already did it. See ADR 008.
2. Trusting HTTP 200. Check `X-Simulator-Stale` on every `/v1/*` GET.
3. Computing an EWMA over demand rows in the order the API returned them. The API
   returns newest first; the forecast needs oldest first. See INV-20.

**Local commands:**
```bash
docker compose up --build -d      # start everything
python scripts/verify_day1.py     # 34 checks
python scripts/verify_filters.py  # 13 filter checks
python scripts/sim_control.py --help
cd backend && ./mvnw verify       # 30 unit tests
cd frontend && npm run test       # 22 unit tests
cd frontend && npm run build      # type check + production build
```
