# Issue board

**Date:** 2026-11-20 (Day 1) · **Owner:** one writer per module at a time

A small board on purpose. The guide asks for "one small issue at a time", and a
board of forty open issues would be a planning fiction. Each issue below is
**bounded**: named files, a stated input, a stated expected output, and a check
that proves it.

**Status legend:** `done` · `open` · `next` · `later` · `cut`

---

## The board

| ID | Issue | Module | Status | Proof it is done |
|---|---|---|---|---|
| FUEL 001 | Probe the simulator contract and write the notes | `docs` | **done** | `docs/contract-notes.md` — 10 measured questions answered |
| FUEL 002 | Typed simulator adapter with 3 error envelopes | `simulator` | **done** | 9 parser tests + 7 stale-header tests |
| FUEL 003 | Snapshot validation: unknown is never zero | `ingestion` | **done** | 14 validator tests |
| FUEL 004 | Atomic publish, degraded mode, coalesced refresh | `ingestion` | **done** | `scripts/verify_day1.py` groups 4–6 |
| FUEL 005 | Read API: `/api/dashboard`, `/api/system-status` | `web` | **done** | `curl /api/dashboard` → 200 with real data |
| FUEL 006 | Operator console: stations, depots, events, shipments | `frontend` | **done** | Browser screenshot, 4 stations × 3 fuels |
| FUEL 007 | Reject flagged stale simulator responses | `simulator` + `ingestion` | **done** | verify_day1 group 4: STALE, decisions blocked, last data kept |
| FUEL 008 | Compose, Prometheus, Grafana, CI | `infra` | **done** | `docker compose up --build -d` → 6 healthy services |
| FUEL 009 | Fuel and region filters on the operator console | `frontend` | **done** | `npm run test` → 22 filter tests |
| FUEL 010 | Demand ingestion, naive forecast and EWMA | `risk` | **next** | forecast examples with errors, unit tests |
| FUEL 011 | Station projection, inbound timing, risk labels | `risk` | **open** | the worked shortage example solved by hand |
| FUEL 012 | Depot projection and supply change detection | `risk` | **open** | the worked depot example solved by hand |
| FUEL 013 | Allocation with reservations and alternatives | `allocation` | **open** | test catching the 4,700 litre mistake |
| FUEL 014 | Rule-based anomaly detector (R1) | `risk` | **open** | quiet series → 0 flags; spike series → flagged |
| FUEL 015 | Persistence: snapshots, recommendations, flags | `domain` + `db` | **open** | migration list, Flyway runs clean |
| FUEL 016 | Approval transaction, command worker, reconciliation | `decision` + `command` | **open** | duplicate approval test output |
| FUEL 017 | Mode-aware revalidation of the reviewed envelope | `decision` | **open** | same-tick / after-step / after-disruption tests |
| FUEL 018 | Stale handling, retries, timeouts, circuit breaker | `simulator` | **open** | fault timeline for the 30 second unavailable fault |
| FUEL 019 | SSE reader with coalescing and polling fallback | `simulator` | **open** | log excerpt of reconnect and full refresh |
| FUEL 020 | Crisis injections: spike, route disruption, delay | `scripts` | **open** | per-scenario evidence |
| FUEL 021 | Metrics, logs, Grafana panels, status panel | `observability` | **open** | status panel with p95 and error rate during a fault |
| FUEL 022 | Baseline vs candidate comparison, detection replay | `scripts` + `allocation` | **done with scope noted** | `policy-comparison.json` (deterministic production-planner sensitivity fixtures), `anomaly-replay.txt` (live replay; per-fuel verifier) |
| FUEL 023 | Load test in PAUSED and RUNNING | `tests` | **done; capacity limit recorded** | `load-summary.json` (bounded bursts, 0 HTTP errors, slow RUNNING risk path); `simulator-overload-logs.txt` (pool exhaustion under repeated stress) |
| FUEL 024 | README, ADRs, evidence index, runbook | `docs` | **done** | `docs/evidence/day-05/README.md`, `docs/demo-runbook.md`, updated Day 5/current-state docs |
| FUEL 025 | Demo rehearsals and the surprise drill | — | **open** | rehearsal notes and the final report |

**Cut order, if the schedule slips** (from the guide's scope rules):
LLM features → custom MCP → Grafana polish → alternative pinning → map polish →
agent lab exercises → detector rule R2.

**Never cut:** official integration, the usable dashboard, deterministic
intelligence, mode-aware reviewed writes, depot and station risk, the metrics
endpoint with the status panel, cached failure mode, repeatable startup, measured
evidence, demo rehearsal.

---

## The rule for every issue

1. **Understand it yourself first.** Write the input, the expected output, the
   failure case and the invariant in your own words. An agent cannot substitute
   for this step — it can only check it.
2. **One writer per module.** Never two agents editing the same package.
3. **The agent states its plan and assumptions before it edits.** You read the
   plan. If you cannot explain the plan yourself, do not accept the patch.
4. **Run the check yourself and read the actual output.** "The agent said the
   tests pass" is not evidence. The output is.
5. **Challenge one failure case per change.** A passing suite that never asked a
   hard question proves nothing.
6. **Record it.** Commit, update `docs/current-state.md`, append to
   `docs/agent-activity-log.md`.

---

## The issue cycle

```
  Understand  ──▶  Specify  ──▶  Implement  ──▶  Verify  ──▶  Review  ──▶  Retain
     │              │              │             │            │           │
  you solve the   one bounded   focused patch  you run the  a separate   commit +
  example by      issue with     after you      check and    reviewer     logs +
  hand            named files   understand     read the     if it       docs
                                   the plan     output       helps
```

**Why the separate reviewer matters.** The reviewer is scoped to *correctness and
resilience*, not to style, and it may only read. It cannot see this
conversation, so it must be given the contract facts it needs. Its findings are
recorded whether they were accepted or rejected — a rejected finding with a
reason is stronger evidence than a silent fix.
