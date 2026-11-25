# Phase 03 · Day 4 — Resilience & Observability

**Target:** Monday, 23 November 2026 · **Status:** Day 4 technical tasks complete and locally verified — 157 backend tests, 31 frontend tests, 8 Prometheus alert rules loaded, clean Compose startup and incident recovery evidence.

| Milestone | Focus areas | Intended result |
|:--|:--|:--|
| Resilience & observability | Fault handling, guarded writes, event refresh, crisis scenarios, dashboards | Demonstrate safe behavior during simulator and application failures |

## Executive summary

Day 4 is the day the platform is supposed to fail **safely**. The claims it
makes are narrow and checkable:

1. When the simulator goes away, the operator keeps looking at the last valid
   world, clearly labelled, and **cannot** approve anything against it.
2. When the simulator answers HTTP 200 with `X-Simulator-Stale: true`, that
   answer does **not** become an eligible decision snapshot — even though the
   status code says everything is fine.
3. The platform stops paying the simulator's timeout once it knows the
   simulator is down, and recovers on its own when it comes back.
4. A **slow** simulator is not a **dead** simulator. The platform must not
   mistake one for the other.
5. The event stream makes the view more prompt. It is never the source of
   truth, so losing it loses nothing.

All four were measured against the running stack. The simulator's own
`/v1/health` endpoint answered **HTTP 200 `status=ok` throughout every single
fault** — because that endpoint bypasses fault injection. A platform that
trusted it would have shown a healthy green screen throughout a total outage.
That contrast is the single most important result of the day.

The backend suite passed **157 tests**, the frontend suite passed
**31 tests**, the frontend production build completed cleanly, Prometheus
loaded all **8** alert rules with their health reported as `ok`, and the
previous day's end-to-end verifier still passes **17/17** against the Day 4
code.

## Delivered

### The circuit breaker

`CircuitBreaker` (`backend/.../observability/CircuitBreaker.java`) guards the
simulator. Three states, one rule each:

| State | What happens | Why |
|---|---|---|
| `CLOSED` | Calls pass through; failures are counted. | Normal operation. |
| `OPEN` | Calls are refused immediately, without touching the network. | The simulator is known to be down. Paying the timeout again buys nothing and delays the operator's view of the outage. |
| `HALF_OPEN` | A limited number of probes is allowed through. | Something may have recovered, but it has to be proven, not assumed. |

Three decisions inside it are worth stating, because each one is a place where
the obvious implementation is wrong:

- **Only availability failures count.** A domain error — the simulator saying
  "no such station" — is a correct answer from a reachable simulator, so it
  records a *success* against the breaker. An injected fault (HTTP 503) is an
  *availability* failure and does count. Getting this backwards means the
  breaker never opens during a real outage. This was a real bug found on Day 4;
  see the table below.
- **A timeout is not an outage — but a *connect* timeout is.** A read timeout
  proves the simulator is *slow*, not that it is *down*.
  `TransportFailureClassifier` tells the three cases apart: a refused
  connection opens the breaker, a read timeout does not, and a *connect*
  timeout — where the connection was never established at all — opens it
  again, because that one really is an unreachable host. Opening the breaker
  to avoid one slow read would take the whole integration offline for 30
  seconds and starve every dependent feature — the shipment tracker, the risk
  engine — of the reads it needs.
- **Reads and writes have separate breakers** (`SimulatorCircuitBreakers`). A
  write that times out may be one slow depot; it says nothing about whether
  reads still work. One shared breaker would let a single slow write stop the
  whole refresh loop.
- **The breaker is free of I/O**, so its whole `OPEN → HALF_OPEN → CLOSED`
  cycle is tested with a fake clock in microseconds, with no simulator running.

### Bounded retries

`ResilienceProperties` keeps timeout, retry and breaker apart, because they
answer different questions: *how long may one attempt take*, *may the same
request be attempted again*, and *should we try at all*.

The retry bound is derived from measurements rather than picked:

```
simulator baseline response   ~2.05 s
backend read timeout           2 s
freshness limit               10 s
```

Three attempts at a 2 s deadline already span ~6 s, which fits inside the
freshness window. Five would not — and a retry policy that outlives the
freshness window hands the operator a view that expired *while the retry was
still running*, which is worse than no retry at all. `ResilienceProperties`
caps `maxRetries` at 3 for that reason, and the cap is asserted in a test.

Retries apply to **reads only**. A write carries an idempotency key, so a
replay is safe in principle — but only the command worker knows whether the
body was persisted before the POST, so that decision belongs there.

### The event stream

`SimulatorEventStreamReader` reads the simulator's SSE feed. The measured
frame format, captured from the running simulator:

```
: connected
event: simulation.tick
data: {"tick": 13, "sim_time": "2026-01-01T03:15:00"}
```

**The stream is a hint, never the truth.** Every event only asks for a
re-read; nothing is ever applied from the event payload. That is what makes a
dropped event harmless: the next REST poll brings the same truth, so the worst
a lost event can do is delay the view by one poll interval. Comment lines
(`:`) are keepalives — they are counted but never trigger a refresh, or the
simulator's keepalive cadence would drive the refresh loop.

Reconnection uses a bounded exponential backoff, so a simulator that stays
down is not hammered. The REST poll **always** runs, in both modes.

### The metrics

`DecisionMetrics` adds the series Day 4 needs. Every label comes from a
**fixed, small set** — no proposal id, station id or correlation id ever
becomes a label (INV-27), because one label per proposal would create an
unbounded number of time series in Prometheus.

| Metric | Answers |
|---|---|
| `fuel_approvals_refused_total{reason}` | Were approvals blocked, and why? |
| `fuel_approvals_granted_total{outcome}` | Did they go through? |
| `fuel_approval_seconds{outcome}` | How long does an approval take? |
| `fuel_refresh_failures_total{reason}` | Is the refresh loop coping? |
| `fuel_refresh_triggers_total{trigger}` | Is the stream or the poll doing the work? |
| `fuel_refresh_events_coalesced_total` | How many SSE notifications fell inside the minimum refresh interval? |
| `fuel_circuit_breaker_*` | Is the platform refusing to act, and why? |
| `fuel_stream_*` | Is the stream connected, and is it healthy? |

### The dashboards and the alert rules

`infra/grafana/provisioning/dashboards/fuel-platform-resilience.json` — a
second dashboard, separate from the Day 3 overview, because an operator during
an incident wants one screen of "is the integration healthy" rather than
scrolling through a mixed board.

`infra/prometheus/alert_rules.yml` — the alerting rules as code. **Eight**
rules, each naming the condition it fires on, why that condition matters, and
the runbook to follow. A rule without a runbook is just a notification.

`infra/prometheus/prometheus.yml` loads them, and `compose.yaml` mounts the
file, so `docker compose up` brings up the rules with no manual step.

### The operator console

`ResiliencePanel` in `App.tsx` shows the state the operator has to be able to
tell apart at a glance:

- **The circuit breaker is OPEN** → the platform has stopped calling the
  simulator on purpose. The cached view is on screen and approvals are
  blocked.
- **The event stream is DISCONNECTED** → the REST poll is still running, so
  the view is still correct — only less prompt. This is *not* an outage and
  must not look like one.
- **Neither** → the panel does not render at all.

That last point is deliberate. A banner that is always on screen teaches the
operator to ignore it, so the panel returns `null` on a healthy system.

## Bugs found and fixed during Day 4

Each of these was found by running the thing, not by reading it.

| Bug | Symptom | Fix |
|---|---|---|
| The breaker never opened | An `unavailable` fault produced `INVALID` snapshots and blocked approvals, but the breaker stayed `CLOSED` and `fuel_circuit_breaker_opened_total` stayed at 0. | Every HTTP error was treated as a domain error. The client now distinguishes **availability** from **agreement**: an injected fault (503) counts as a failure, a domain error (409/422) does not. `isAvailabilityFailure` in `SimulatorClient`. |
| One breaker for reads and writes | The javadoc promised separate breakers, but one `AtomicReference` served both. | Split into `CircuitBreaker` instances held by `SimulatorCircuitBreakers`, one per channel, each with its own metrics. |
| A gauge and a counter shared a name | `fuel_stream_reconnects` was registered as both a `Gauge` and a `Counter`. Prometheus refuses that, and the second registration was silently dropped with a warning. | The gauge was renamed `fuel_stream_reconnect_attempts`. |
| The retry count was unbounded | `maxRetries` was read straight from configuration, so a misconfiguration could outlive the freshness window. | Capped at 3 in `ResilienceProperties`, with a test asserting the cap. |
| The event script could not detect anything | The crisis event was injected while the simulator was `PAUSED`, so the tick never advanced past the event's start tick and no anomaly could appear. | `inject_fault.py` now starts the simulation before injecting and pauses it afterwards. The result went from *0 anomaly flags* to *4 unmet needs*. |
| A timeout opened the breaker | The Day 3 end-to-end verifier injects a 120-second `latency` fault. The resulting read timeouts were counted as availability failures, so the breaker opened for 30 seconds and starved the shipment tracker of its reads. **Two Day 3 checks failed as a result** — 15/17 instead of 17/17. | `TransportFailureClassifier` separates a timeout from a connection failure. A timeout no longer counts; the Day 3 verifier is back to **17/17**. |
| The timeout was classified by **reading the message text** | The classifier fell back to matching `"request cancelled"` in the exception message, because the exception type was assumed rather than measured. A wording change in the JDK or Spring would have silently reopened the regression. | The exception chains are now **measured** by `SimulatorTransportTimeoutTest`, which drives a real read timeout and a real connection refusal through the real `RestClient` + `JdkClientHttpRequestFactory` and asserts the classification. The measurement is a test, not a comment. |
| A **connect** timeout was classified as a slow simulator | `HttpConnectTimeoutException` extends `HttpTimeoutException`, so the timeout branch caught it first. A blackholed simulator — the one failure that really is an outage — would have been treated as "slow" and the breaker would never have opened for it. | The connect-timeout case is now checked **before** the read-timeout case, with a test for both the bare and the wrapped form. A connect timeout opens the breaker; a read timeout does not. |

That last one is the most valuable bug of the day, and it was found only by
running the *previous* day's verifier against the new code. A green test suite
proves the unit tests still pass; it does not prove the system still works.

The two rows after it have the same root cause: **the exception was assumed
rather than measured.** The fix was not a better guess at the wording — it was
a test that drives the real transport and prints the chain it produced.

## Verification evidence

All of it is in [`docs/evidence/day-04/`](../evidence/day-04/), and every
number in it was measured against the running stack.

| Evidence | What it shows |
|---|---|
| [`fault-unavailable.txt`](../evidence/day-04/fault-unavailable.txt) | **The full failure boundary.** `FRESH` → `INVALID` → `FRESH`, with the breaker `CLOSED` → `OPEN` → `CLOSED`, the approval refused with `STALE_OR_INVALID_SNAPSHOT`, and the simulator's `/v1/health` answering `HTTP 200 status=ok` at every single step. |
| [`fault-stale.txt`](../evidence/day-04/fault-stale.txt) | **The stale guard.** HTTP 200 + `X-Simulator-Stale: true` → quality `STALE`, eligibility `false`, approval refused — while the breaker stays `CLOSED`, because the simulator *is* reachable. |
| [`fault-latency.txt`](../evidence/day-04/fault-latency.txt) | **A slow simulator is not a dead one.** 40 samples, one per second, under a `latency` fault: the quality goes `INVALID` and the approval is refused, but the breaker stays `CLOSED` in **every single sample** — the simulator is slow, not gone. |
| [`breaker-trace.txt`](../evidence/day-04/breaker-trace.txt) | **The breaker, second by second.** `breaker=OPEN` from t+4s to t+16s, `fuel_circuit_breaker_opened_total=1`, `rejections=14`, and a closed breaker by t+18s. |
| [`event-demand-spike.txt`](../evidence/day-04/event-demand-spike.txt) | **The crisis event.** A region demand multiplier of 1.8 produced **6 `KNOWN_EVENT` anomaly flags** after four stepped ticks. |
| [`metrics-steady-state.txt`](../evidence/day-04/metrics-steady-state.txt) | The Prometheus surface: the status, the resilience series, the **8 alert rules and their health**, and the alerts that are firing. |
| [`metrics-after-clean-start.txt`](../evidence/day-04/metrics-after-clean-start.txt) | Fresh eligible snapshot after final startup, all **8 rules healthy**, and no active alerts. |
| [`backend-tests-latest.txt`](../evidence/day-04/backend-tests-latest.txt) | Earlier suite after route-event handling and SSE coalescing — **154 tests passed**. |
| [`backend-tests.txt`](../evidence/day-04/backend-tests.txt) | `mvn clean test` — **157 tests passed**, 0 failures, 0 errors. |
| [`day3-regression.txt`](../evidence/day-04/day3-regression.txt) | **17/17 Day 3 checks passed** against the Day 4 code. The regression run that caught the timeout bug is what proves the fix landed. |
| [`frontend-tests.txt`](../evidence/day-04/frontend-tests.txt) | `npm test` — **31 tests passed**; `npm run build` — clean. |
| [`frontend-ui-checks.txt`](../evidence/day-04/frontend-ui-checks.txt) | The Day 4 UI strings present in the **served bundle**, the `.banner-danger` rule present, and the safety checks: the panel is hidden on a healthy system, gated on the breaker state, and the browser never talks to the simulator. |
| [`deployment-topology.txt`](../evidence/day-04/deployment-topology.txt) | The running topology: six services, their status and their published ports. |
| [`forecast-fallback.txt`](../evidence/day-04/forecast-fallback.txt) | Demand-history failure selects 12 `PROFILE_ESTIMATE` forecasts; the fallback counter rises 40→56; recovery restores 12 `EWMA` forecasts and `FRESH`. |
| [`event-route-disruption.txt`](../evidence/day-04/event-route-disruption.txt) | Combined crisis at tick 31: six event-attributed anomaly flags and a Mirpur plan using the Patiya route while Gazipur→Mirpur is disrupted. |
| [`event-combined-crisis.txt`](../evidence/day-04/event-combined-crisis.txt) | At multiplier 3.0, the route alternative serves Mirpur; six flags are `KNOWN_EVENT` and 54.1 L remains unmet at Tongi. Both events resolve at tick 44. |
| [`event-shipment-delay.txt`](../evidence/day-04/event-shipment-delay.txt) | `shipment_delay` shifts scheduled supply by 4 ticks and exposes those changes in depot risk. |
| [`stream-fallback.txt`](../evidence/day-04/stream-fallback.txt) | Backend reconnect fails during `stream_disconnect`; REST polls keep data `FRESH`; clearing the fault reconnects the stream. |
| [`running-refresh-volume.txt`](../evidence/day-04/running-refresh-volume.txt) | 16.14 s RUNNING sample: 76 ticks, 50 coalesced events, simulator calls reduced from 765 to 333 after the 500 ms event throttle. |
| [`resilience-panel.png`](../evidence/day-04/resilience-panel.png) | UI screenshot during outage: breaker open, cached view labelled, approval warning visible. |
| [`backend-restart.txt`](../evidence/day-04/backend-restart.txt) | Backend restart returns readiness and a fresh eligible snapshot. |
| [`clean-compose-startup.txt`](../evidence/day-04/clean-compose-startup.txt) | Full stack rebuilt after `docker compose down` without deleting volumes. |
| [`incident-handoff.txt`](../evidence/day-04/incident-handoff.txt) | Run ID, snapshot ID, service status and resilience metrics. |
| [`incident-logs.txt`](../evidence/day-04/incident-logs.txt) | Backend log excerpt paired with the incident handoff. |
| [`README.md`](../evidence/day-04/README.md) | What each evidence file is and how to reproduce it. |

### The failure boundary, in full

This is the case the whole day was built around, so it is worth showing end
to end. The simulator's own health endpoint is on the right — it stayed
healthy the entire time, because it bypasses fault injection:

```
--- before the fault ---
  simulator /v1/health   : HTTP 200 status=ok
  platform simulator      : UP
  data quality            : FRESH
  decision eligible       : True
  circuit breaker         : CLOSED
  event stream            : CONNECTED
  warnings                : []

--- during the unavailable fault ---
  simulator /v1/health   : HTTP 200 status=ok      <-- still healthy
  platform simulator      : DOWN
  data quality            : INVALID
  decision eligible       : False
  circuit breaker         : OPEN
  warnings                : ['Fault[CIRCUIT_OPEN http=0] the simulator circuit
                             breaker is open; the call was not attempted']

approval attempt during the fault -> HTTP 409 code=STALE_OR_INVALID_SNAPSHOT

--- after the fault ---
  simulator /v1/health   : HTTP 200 status=ok
  platform simulator      : UP
  data quality            : FRESH
  decision eligible       : True
  circuit breaker         : CLOSED
  event stream            : CONNECTED
  warnings                : []
```

Two independent facts, and the platform reports both:

- The simulator is **reachable** (`/v1/health` says `ok`).
- The station data is **not fresh** (`INVALID`, the breaker is `OPEN`).

A platform that reported only the first would have shown a healthy green
screen through a total outage. **Reachability is not freshness.**

### The breaker, second by second

```
  t+ 2s  quality=INVALID breaker=CLOSED simulator=DOWN
  t+ 4s  quality=INVALID breaker=OPEN   simulator=DOWN    <-- opened
  t+ 6s  quality=INVALID breaker=OPEN   simulator=DOWN
  ...
  t+16s  quality=INVALID breaker=OPEN   simulator=DOWN
  t+18s  quality=FRESH   breaker=CLOSED simulator=UP      <-- recovered on its own
```

and the counters behind it:

```
fuel_circuit_breaker_opened_total{channel="read"}    = 1.0
fuel_circuit_breaker_rejections_total{channel="read"} = 14.0
fuel_simulator_call_seconds_count{endpoint="/v1/instance",outcome="circuit_open"} = 14
```

Once the breaker is open, the failing calls cost **no network time at all** —
they are refused before the request is made. That is the point of the whole
mechanism: without it, every refresh would keep paying the full 2 s timeout
against a simulator that is not answering.

## Known limitations

- The circuit breaker counts **consecutive** failures. A simulator that fails
  once every ten refreshes never opens it, because every success in between
  resets the counter. A failure *ratio* would be the next step.
- A **read** timeout never opens the breaker, by design. That is correct for a
  simulator that is slow, but it means a simulator that is slow *and*
  permanently so is never detected as unavailable. The freshness limit
  catches that case instead — the view ages out and the approval is refused.
  A **connect** timeout does open the breaker, because the connection was
  never established, so a blackholed host is still detected.
- The retry budget is derived from the simulator's measured baseline. A slower
  simulator would need the cap lowered, and the cap is currently hard-coded at
  3 rather than derived from the freshness limit at runtime.
- The stream reader reconnects with a bounded backoff but has no jitter. Two
  backends started at the same moment would reconnect in lockstep.
- The half-open state allows exactly one probe. With several refreshes in
  flight, the one that gets the permit decides the outcome — which is the
  intent, but it means the recovery is decided by a race.
- The alert rules are loaded and evaluated, but no Alertmanager is configured.
  The rules fire and appear in Prometheus; nothing is notified yet.
- The frontend must be rebuilt (`docker compose build frontend`) after any
  frontend change, or the container keeps serving the previous bundle. This
  bit during Day 3 and again during Day 4.
- The fault injection scripts mutate the live simulator and database. They must
  be run against the disposable local stack.

## Day 4 guide completion audit

The Day 4 technical checklist in chapter 33 is complete for the local project
scope. The measurements below include the event and refresh exercises; the
route-event overlay and refresh throttle are covered by the latest backend
test run. The guide's independent reviewer and fresh-session exercise remains
prepared as a handoff, but was not performed.

| Guide item | Status | Evidence / remaining work |
|---|---|---|
| Stale handling, bounded retries, timeout and circuit breaker; server-side approval guard | **Done** | Unit suite plus `fault-unavailable.txt`, `fault-stale.txt`, `fault-latency.txt`, and `breaker-trace.txt`. |
| SSE refresh, reconnect, coalescing and polling fallback | **Done** | `stream-fallback.txt` records disconnect/recovery with poll refreshes; `running-refresh-volume.txt` measures the 500 ms cap and coalesced notifications. |
| Unavailable, stale and latency faults | **Done** | Three live timelines; approval refusal and recovery are recorded. |
| Forecast failure fixture and backend restart | **Done** | `forecast-fallback.txt` captures fallback and recovery; `backend-restart.txt` records post-restart readiness and fresh eligibility. |
| Demand spike, route disruption and shipment delay scenarios | **Done** | Demand spike produces six anomaly flags; route disruption uses the Patiya alternative; supply delay is visible in depot `supply_changes`. |
| Metrics, logs, Prometheus, Grafana and status panel | **Done** | Metrics captures, eight loaded rules, `resilience-panel.png`, backend logs and run/snapshot IDs are saved. |
| Clean Compose startup and recorded incident | **Done** | `clean-compose-startup.txt` and the fault, logs and metrics evidence are saved. |
| Reviewer/context handoff | **Prepared; independent review pending** | `incident-handoff.txt` pairs run ID, logs and metrics for review. No external reviewer was contacted and no fresh-session resume was performed. |

The original steady-state capture has two alerts firing (`UnknownShipmentOutcomes`
and `StaleResponsesFromSimulator`). The rules report healthy evaluation, but
that capture is not an all-clear baseline; its alert conditions are residual
state from prior exercises. Use the fresh post-startup metrics capture when
checking the demo baseline.

## Related documents

- [Acceptance and invariants](../acceptance-and-invariants.md)
- [Contract notes](../contract-notes.md) — the three simulator error shapes
- [Current state](../current-state.md)
- [System architecture](../system-architecture.md)

Detailed schedule: [Implementation guide, Day 4](../../Fuel%20Management%20System%20md%20file/Fuel_Management_Implementation_Guide_v2.md).
