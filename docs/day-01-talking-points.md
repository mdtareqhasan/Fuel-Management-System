# Day 1 — what to say, and where the evidence is

**Date:** 2026-11-20 (Friday) · **Release target:** 2026-11-23 · **Deadline:** 2026-11-26

This is the sheet for the Day 1 update to Poridhi. Every claim below is backed by
a file in this repository or by a command whose output is recorded in
`docs/evidence/day-01/`. **Nothing here is asserted without a check.**

---

## 1. The 60-second opening

> "Day 1 was about establishing the contract, not about features. We ran the
> official simulator unmodified and **measured** its behaviour instead of trusting
> the guide, because the guide contradicted itself in five places. Two of those
> measurements changed the design outright.
>
> We now have a running vertical slice: one command starts six services, and 33
> automated checks prove the operator console shows real simulator data, stays
> readable when the simulator breaks, and blocks decisions when the data cannot
> be trusted.
>
> The most important finding is that depot inventory is **already net of
> pending shipments**. The guide told us to subtract them. If we had, we would
> have double-counted every reservation and starved the depot — silently. That is
> ADR 008, and it exists because we ran the experiment."

**Evidence:** `docs/contract-notes.md` §5.2, `docs/adr/ADR-008-depot-inventory-is-net.md`,
`docs/evidence/day-01/dispatch-capacity-test.txt`.

---

## 2. The ten contract questions we answered by measurement

This is the strongest thing in Day 1. Say the number, not the story: **ten
questions, all answered by running the image.**

| # | Question | Guide said | **We measured** |
|---|---|---|---|
| 1 | Tick rate | speed 8 | **5.46 ticks/sec** — speed is an upper bound |
| 2 | When does depot stock drop? | unknown | **at POST time**, not at departure |
| 3 | What does a replay return? | 200 or 201? | **201**, same id, no second deduction |
| 4 | Is dispatch allowance shared across fuels? | "shared" | **yes** — 7000 DIESEL then 7000 PETROL → 409 |
| 5 | Validation order | nine rules together | **dispatch capacity → destination capacity** first |
| 6 | How is stale data signalled? | a header | `X-Simulator-Stale: true` **with HTTP 200** |
| 7 | Does health imply fresh data? | no | **no** — `/v1/health` is 200 during an `unavailable` fault |
| 8 | Demand history order | unknown | **descending** (newest first) |
| 9 | Is `demand` the same as `served`? | use demand | **genuinely different** — stockout gives `served=0` |
| 10 | Departure / arrival convention | unknown | `departure = created_tick`, `arrival = created + transit` |

**Evidence:** `docs/contract-notes.md`, raw responses in `docs/evidence/day-01/`.

---

## 3. The two findings that changed the design

### 3.1 Depot inventory is net, not gross → ADR 008

The simulator deducts depot inventory **when the allocation is created**. So
`GET /v1/depots` already excludes pending and in-transit shipments. The guide
says to subtract them again. Doing that would double-count every reservation.

> "We did not take the guide's word for it. We read the inventory before the
> POST, immediately after it, and after a replay of the same key. 60000 → 57000 →
> 57000. The deduction happens at POST, and a replay does not deduct twice."

### 3.2 Tick staleness can never be the approval rule → ADR 004

At the measured rate, a **30 second operator review spans about 41 simulated
hours** — roughly 164 ticks. A "reject if more than one tick old" rule would
block every approval during a live demo.

> "So approval does not compare ticks. The proposal is an **envelope**, and the
> server revalidates it against a fresh read at the moment of approval. The
> quantity may come out equal or lower. It may never come out higher."

**Evidence:** `docs/adr/ADR-004-mode-aware-approval.md`,
`docs/acceptance-and-invariants.md` INV-13.

---

## 4. What the system does today

```
docker compose up --build -d     →  6 healthy services
python scripts/verify_day1.py    →  33 passed, 0 failed
cd backend && ./mvnw verify      →  30 unit tests, all green
cd frontend && npm run test      →  14 unit tests, all green
cd frontend && npm run build     →  exit 0
```

| Component | State |
|---|---|
| Backend — Spring Boot 3.5.7, Java 21 | running, 30 unit tests |
| Typed simulator adapter, 3 error envelopes | complete |
| Snapshot validation, atomic publish, mode-aware refresh | complete |
| `/api/dashboard`, `/api/system-status`, Actuator + Prometheus | complete |
| React + TypeScript console: 4 stations × 3 fuels, fuel + region filters | complete, 22 unit tests |
| Docker Compose, Prometheus, Grafana, CI | complete |
| Risk engine, allocation, approval, command worker | **Day 2 and Day 3** |

---

## 5. The three states, demonstrated live

This is the demo segment that earns the resilience marks. Show it, do not
describe it.

| Condition | Simulator | Data quality | Decision eligible | What the operator sees |
|---|---|---|---|---|
| Healthy | UP | **FRESH** | **yes** | live data |
| `stale_data` fault | **UP** | **STALE** | **no** | last known data, amber warning banner |
| `unavailable` fault | **DOWN** | **INVALID** | **no** | last known data, ageing |

> "The middle row is the point. The simulator is answering `/v1/health`
> perfectly, and every one of its data responses carries HTTP 200 — with a
> header that says the data is stale. A system that trusted the status code
> would let the operator act on a world that no longer exists. **Reachability is
> not freshness**, and we report them separately."

**Evidence:** `docs/evidence/day-01/verification-report.md` §4–§6.

---

## 6. The engineering decisions worth defending

Each one is a decision we had to *make*, with a reason. That is what separates a
project from a tutorial.

| Decision | Why | Where |
|---|---|---|
| **The browser never talks to the simulator** | It reads the backend's cached, validated view, so 100 dashboards cause 1 polling loop | `docs/architecture-diagram.md` |
| **Snapshots publish atomically** | One reference swap — a reader sees the whole old world or the whole new one, never a mix | `SnapshotIngestionService` |
| **One refresh loop, coalesced** | A burst of tick events collapses into one pending flag, so no backlog builds | `RefreshScheduler` |
| **`snake_case` everywhere** | The simulator speaks `snake_case`; mixing conventions made fields silently `null` and the UI render them as "Unknown" | `docs/agent-activity-log.md` session 4 |
| **A missing value is unknown, never zero** | Enforced in the validator, in the API and in the UI. A zero is a claim about the world; "Unknown" is a claim about our knowledge | INV-6, INV-24 |
| **Three error envelopes, one field name** | `detail` is an object in a domain error and an array in a Pydantic 422, and faults use a top-level `error`. A Jackson record bound to `detail` deserialises one shape and throws on the other | `SimulatorErrorParser` + 9 tests |
| **Metrics ship with the feature they measure** | Observability is 10% of the rubric. "Add metrics at the end" means there is no time, so the Day 5 comparison has no numbers | ADR 006 |
| **Depot inventory used as returned** | Already net of reservations. Subtracting again double-counts | ADR 008 |

---

## 7. Architecture decisions recorded today

Eight ADRs in `docs/adr/`, each with context, decision, rejected alternatives
and consequences.

| ADR | Decision |
|---|---|
| 001 | Modular monolith with nine explicit package boundaries |
| 002 | The simulator owns the truth; we store observations |
| 003 | Operator approval is mandatory and **server-enforced**; no auto-approve threshold |
| 004 | Mode-aware approval: the proposal is an envelope, revalidated against a fresh read |
| 005 | Detect with rules before models; the detector is advisory |
| 006 | Metrics first — the Prometheus endpoint ships on day one |
| 007 | Local Docker Compose, no cloud |
| 008 | Depot inventory is already net of reservations |

---

## 8. Known gaps — stated plainly, not hidden

Saying what is missing is worth more than hiding it.

| Gap | When |
|---|---|
| No risk engine, forecast or anomaly detection | Day 2 |
| No allocation, recommendation or approval path | Day 3 |
| No SSE listener — the 2 second REST poll carries the refresh loop | Day 3 |
| No database persistence of snapshots, commands or audit records | Day 3 |
| Grafana has the datasource but no dashboard JSON | Day 4 |
| No k6 load test | Day 4 |
| Detector thresholds (1.35 / 1.15 / 3 ticks) are **proposed, not tuned** | Day 5 measures them |

> "The detector thresholds are stated, not hidden. On Day 5 we report the
> measured false-flag count and the measured detection delay against the
> simulator's own ground truth — not against our own baseline."

---

## 9. Risks we are carrying

| Risk | Mitigation |
|---|---|
| Getting the depot-reservation semantics wrong again | ADR 008 + a unit test asserting the allocator does not subtract twice |
| A forecast that looks good on paper and wrong on replay | Day 5 measures forecast error against the simulator's ground truth, not our own baseline |
| Detecting the injection instead of the phenomenon | The detector reads observed demand, never the simulator's `demand_multiplier` |
| Scope creep into microservices or cloud | ADR 001 and ADR 007 — the brief explicitly warns against infrastructure complexity |

---

## 10. What Day 2 will produce

1. **Demand rate** from `demand_liters`, sorted ascending by tick first (INV-20).
2. **Forecast** — EWMA baseline plus a seasonal component, with a fallback path
   and a `fuel_forecast_fallback_total` counter.
3. **Risk** — stockout trajectory per station and fuel, horizon in simulated hours.
4. **Anomaly detection** by ratio rule (ADR 005), advisory only, with tests for
   both a quiet series and a spiked series.
5. **Demand fetching** — one `limit` is shared across all three fuels, so rows are
   split by fuel after the fetch (INV-21).

**Evidence for Day 2:** one manually checked station calculation, one manually
checked depot calculation, a real simulator snapshot, passing invariant checks
and a printed recommendation with alternatives.

---

## 11. The questions to expect, and the answers

| Likely question | Answer |
|---|---|
| "Why not microservices?" | The failure modes we must demonstrate *handle* are the ones microservices would create. One transaction boundary, one refresh loop, one log stream. ADR 001. |
| "How do you know the data is fresh?" | Two separate facts: the simulator's reachability, and the age of our last **validated** refresh. `/v1/health` bypasses fault injection, so it is a liveness probe only. |
| "What if the operator approves something stale?" | The server refuses. The stale header, an invalid payload, an open circuit, an unknown write outcome and a reset all block the write path — regardless of what the button looks like. |
| "How do you avoid double dispatch?" | The idempotency key and the exact request body are persisted **before** the POST, and a retry reuses that same key and body. |
| "Is the forecast any good?" | Not yet — it does not exist. On Day 5 we report the measured forecast error against the simulator's own ground truth. We do not publish an unmeasured quality claim. |
| "Why is your tick rate lower than configured?" | `SIMULATION_SPEED` is an upper bound the machine does not sustain. We measure the real rate at runtime and derive the refresh limits from the measurement, not from the configuration. |
| "What is the biggest risk right now?" | Getting depot-reservation semantics wrong for a second time, in the allocator. It is the easiest thing to get wrong, so it has its own ADR and its own test. |

---

## 12. Bonus segment — the filters, in 30 seconds

> "The operator console filters by fuel and by region. The options are drawn
> from the snapshot, not hardcoded — our verification script asserts that no
> region or station id appears in the frontend bundle, because a hardcoded list
> would silently filter on something the world does not have.
>
> The filter lives in the URL, so **this exact view is a link** I can send you.
> And it does not touch the backend: the API still returns the whole world.
> A filter is a view concern, never a data concern."

Open `http://localhost:5173/?fuel=PETROL&region=region-chattogram` to show it.
