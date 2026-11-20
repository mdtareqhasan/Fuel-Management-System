# System architecture — the one diagram

**Date:** 2026-11-20 (Day 1) · **Status:** accepted, the deployment diagram is drawn on Day 4

This is the diagram I draw first and explain aloud without notes. It answers three
questions in one picture: **who owns the truth**, **which clock is running**, and
**why one backend process is enough**.

---

## 1. The one diagram

```
                      ┌───────────────────────────────────────────────┐
                      │  BUP Fuel Supply Simulator  (official image)   │
                      │  asifmahmoud414/bup-fuel-supply-simulator:1.0.0│
                      │  ───────────────────────────────────────────    │
                      │  TRUTH: inventory · demand · shipment status    │
                      │  CLOCK: simulation tick (tick_minutes = 15)    │
                      └───────────────┬───────────────────────▲───────┘
                                      │ REST GET (the truth) │
                                      │ POST /v1/allocations  │
                                      │  (writes only, after  │
                                      │   human approval)     │
                                      ▼                       │
      ┌───────────────────────────────────────────────────────────────┐
      │  Backend — one Spring Boot process (modular monolith)          │
      │                                                               │
      │   simulator ──▶ ingestion ──▶ domain ──▶ web ──▶ metrics      │
      │   (typed adapter) (validate,   (immutable (JSON     (Prometheus)
      │                  publish once)  snapshot)  API)                │
      │        │             │            │                          │
      │        │             ▼            ▼                          │
      │        │        risk / allocation / decision / command         │
      │        │        (pure functions — no server, no I/O)           │
      │        │             │                                         │
      │        └─────────────┴─────▶ PostgreSQL (durable records)     │
      └───────────────────────────────────┬───────────────────────────┘
                                          │ REST /api
                                          ▼
      ┌───────────────────────────────────────────────────────────────┐
      │  Operator console (React + TypeScript)                        │
      │  reads the cached validated view · never talks to the         │
      │  simulator · a disabled button is a convenience, not a control │
      └───────────────────────────────────────────────────────────────┘

      ┌───────────────┐   ┌────────────────┐
      │  Prometheus   │──▶│    Grafana     │
      └───────────────┘   └────────────────┘
```

### The four arrows that matter

| Arrow | What it is | What it is **not** |
|---|---|---|
| Browser → Backend | A read of the **cached, validated** view | The browser never talks to the simulator |
| Backend → Simulator (GET) | The **only** way the application learns the world | Not a cache of our own invention |
| Backend → Simulator (POST) | A write, and only after a **server-enforced** human approval | Never automatic, never from stale data |
| Backend → Prometheus | Metrics, with a **fixed small label set** | Unique ids never become labels |

---

## 2. Who owns the truth

**The simulator owns the truth.** The application owns *observations* of it.

This is not a stylistic choice. The simulator can move while we are reading it, it
can serve stale payloads with an HTTP 200, and it can refuse writes for reasons
only it knows. If we stored our own copy and treated it as the world, we would
quietly be running a second, disagreeing universe.

So a stored snapshot is **evidence of a past observation**, never an alternative
world (ADR 002). Three consequences follow, and all three are enforced in code:

1. **We never present a stored snapshot as current truth.** The API always carries
   the snapshot id, the tick bounds, the observed-at wall time and the data
   quality. The operator can always see how old the picture is.
2. **A read failure degrades, it does not erase.** The last valid snapshot stays on
   screen with a stale warning. Losing the view would be worse than showing it
   honestly, because the operator would have nothing to reason about.
3. **A write is refused unless the current view is decision-eligible.** The
   server-side guard is the control. A greyed-out button in the browser is a
   convenience for the operator, not a guarantee.

### The rule that trips people up

The simulator returns depot inventory **already net of pending and in-transit
shipments** — it deducts at POST time. So the application must *not* subtract
those reservations again. Doing so double-counts every reservation and starves
the depot. This is ADR 008, it was settled by an experiment rather than by
reading the guide, and it is the single most likely place to introduce a silent
bug.

---

## 3. The two clocks

The system has two clocks and they are **not** interchangeable. Mixing them up is
the most common source of wrong decisions.

| | **Simulation tick** | **Wall clock** |
|---|---|---|
| Measures | the fuel world | the software |
| Owned by | the simulator | this machine |
| Advances | when the simulator runs or is stepped | always |
| Used for | transit time, supply arrival, stockout horizon | request timeouts, cache age, circuit breakers, proposal expiry |
| Rate | 1 tick = 15 simulated minutes; **measured ~5.46 ticks/sec** while running | 1 second |

### Why the distinction decides the approval rule

At the measured rate, **30 seconds of operator review spans about 41 simulated
hours** — roughly 164 ticks. A rule like *"reject if the proposal is more than
one tick old"* would therefore block **every** approval during a live demo.

So the approval path does not compare ticks. It treats the proposal as an
**envelope** and **revalidates it against a fresh read** at the moment of
approval (ADR 004):

- the quantity may come out **equal or lower** than the operator reviewed;
- it may **never come out higher**;
- if the world moved in a way that invalidates the proposal, the operator is
  asked to re-review instead of the system guessing.

### And staleness is measured against the wall clock, not the tick

A **paused** simulator can sit at tick 12 for ten minutes while its API answers
perfectly. That is not stale. Staleness is measured against the last successful
*validated REST refresh*, tracked separately from the tick. This is why the
status panel reports the refresh age in seconds and the tick separately.

---

## 4. Why one backend is enough

The brief warns against introducing infrastructure complexity. Beyond that, the
reason is specific to this problem:

1. **The distributed failure modes here are the thing we must demonstrate
   handling of.** A partial outage, a network partition and a lost message are
   exactly the scenarios in the rubric. Microservices would mean debugging the
   platform while also demonstrating the platform.
2. **One transaction boundary.** The approval flow must persist the command body
   and its idempotency key **before** the POST. That is one transaction, and it
   is cleanest inside one process and one database.
3. **One refresh loop.** The browser reads the backend's cached view. A hundred
   dashboards cause one polling loop against the simulator, not a hundred.
4. **One build, one log stream, one correlation id.** The incident drill on Day 4
   requires following one run id through logs and metrics.

**Modular monolith, nine explicit package boundaries** (ADR 001). The boundaries
are real: the pure logic in `risk` and `allocation` runs as plain JUnit tests
with no server and no simulator. What we lose — one process means one crash
affects everything — is mitigated by making the write path durable in PostgreSQL
so a restart **reconciles** rather than loses commands.

---

## 5. The refresh loop, precisely

```
  every 2 seconds          ──▶  RefreshScheduler.requestRefresh("poll")
  SSE simulation.tick      ──▶  RefreshScheduler.requestRefresh("event")
                                        │
                                        ▼
                          only one refresh in flight;
                          a burst collapses into ONE pending flag
                                        │
                                        ▼
                    ┌──────────────────────────────────┐
                    │ SimulatorClient (typed adapter)  │
                    │  - parses 3 error envelopes      │
                    │  - carries X-Simulator-Stale     │
                    └──────────────┬───────────────────┘
                                   ▼
                    ┌──────────────────────────────────┐
                    │ SnapshotValidator                │
                    │  "a missing value is unknown,    │
                    │   never zero"                    │
                    └──────────────┬───────────────────┘
                                   │ all valid?
                        ┌──────────┴──────────┐
                       yes                    no
                        │                     │
                        ▼                     ▼
        AtomicReference.swap(new snapshot)   keep the LAST VALID snapshot,
        (one reference swap: a reader sees    downgrade its quality, and
         the whole old world or the whole     record the reason
         new one — never a mix)
```

**REST is the source of truth. SSE is only a hint.** When the stream says the
world moved, we re-GET the affected resources. We never treat a stream payload
as a world state.

---

## 6. How to explain this in the demo

Thirty seconds, no notes:

> "The simulator owns the truth; we only store observations of it, and every
> view tells you how old it is. There are two clocks — the simulation tick drives
> the fuel world, the wall clock drives the software — and at the measured 5.46
> ticks per second a 30 second review spans about 41 simulated hours, which is
> why approval revalidates against a fresh read instead of checking tick age.
> The browser never talks to the simulator; it reads our one cached, validated
> view, so a hundred dashboards cause one polling loop."

Then point at the diagram and say: *one backend, because the failure modes we
must demonstrate are the ones a single process makes easy to reason about.*
