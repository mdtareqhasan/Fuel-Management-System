# Fuel Management Application Implementation Guide

A five day engineering and learning plan for the Poridhi internship assignment

Stack: Spring Boot, React, PostgreSQL and Docker Compose

Build window: Thursday November 19 to Monday November 23 2026  
Deadline: Thursday November 26 2026  
Timezone for project updates: Asia Dhaka

Revision 2, November 19 2026: mode aware approval, depot level projection, rule based anomaly detection, alternative actions, metrics from day one and a step by step order of work (chapter 35).

### The recommended approach

Build one reliable vertical slice first: read the official simulator, display a valid snapshot, detect shortage risk, recommend a feasible allocation, let an operator approve it, submit it once, and track its result. Then prove that the same workflow handles disruptions, stale data and dependency failures.

Use explainable forecasting, rule based demand anomaly detection and a constrained allocation heuristic for the first release. They meet the brief's intelligence options and can be implemented and evaluated in Java. Keep AWS, reinforcement learning, Kubernetes and an LLM dependency outside the five day critical path.

The same workflow must work in two simulator modes: PAUSED, where you step the clock for deterministic demos and tests, and RUNNING, where the clock advances while the operator is still reviewing. Revision 2 closes six gaps found when the plan was checked against the participant brief and the integration guide.

### What changed in revision 2

| **Change**                                | **What it fixes**                                                                          | **Chapters**       |
|-------------------------------------------|--------------------------------------------------------------------------------------------|--------------------|
| Mode aware approval                       | A fixed one tick staleness rule would block every approval while the simulator clock runs. | 05, 11, 12, 17, 18 |
| Depot level projection                    | Shipment delay and supply shortfall events showed up only through late station effects.    | 14, 15, 19, 20     |
| Prometheus metrics and status panel as P0 | Observability carries 10 percent of the brief and was ranked P1.                           | 04, 21, 24, 33     |
| Rule based anomaly detection              | The brief loop includes Detect; the plan only predicted.                                   | 13, 16, 20         |
| Alternative actions per recommendation    | Brief section 9 asks for alternatives and uncertainty next to each decision.               | 15, 17, 18         |
| Rebalanced schedule and step order        | New work needed time; demo rehearsal is protected on day five and the buffer days.         | 30 to 35           |

### How to use this guide

Follow the design and contract chapters before the day by day work plan. Draw the architecture yourself in Excalidraw, implement one small issue at a time, and explain each change before asking an agent to produce code. Every day ends with a runnable demonstration, evidence and an honest progress update.

Chapter 35 gives the exact order of steps, the understanding gate to pass before each next step and the evidence to put in each Poridhi update.

The plan assumes working familiarity with Java, Spring REST, React and Git, plus about eight focused hours per day. If these are new, preserve the core workflow and use the reduced scope rules in the daily plan.

This package is an implementation blueprint. Its examples and targets are proposed designs, not claims that the application has already been built or tested.

## 02 Guide navigation

Read in this order. Each numbered chapter begins on a new page. The five day plan translates the design into small deliverables with checks and evidence.

| **Chapters** | **Topic**                                                                         |
|--------------|-----------------------------------------------------------------------------------|
| 3 to 4       | Requirements, evaluation and scope                                                |
| 5 to 7       | Domain model, simulation modes and official simulator contract                    |
| 8 to 10      | Architecture, backend boundaries and stored data                                  |
| 11 to 12     | State ingestion, mode aware refresh and simulator writes                          |
| 13 to 16     | Forecasting, anomaly detection, station and depot risk, allocation and evaluation |
| 17 to 18     | React workflow and application API                                                |
| 19 to 20     | Failure handling and crisis tests                                                 |
| 21 to 24     | Observability, deployment, CI and verification                                    |
| 25 to 29     | Agentic engineering workflow and lab evidence                                     |
| 30 to 34     | Five day implementation schedule                                                  |
| 35           | Step by step order, understanding gates and update points                         |
| 36 to 37     | Daily updates and evidence tracking                                               |
| 38 to 39     | Demo, release checklist and engineering defense                                   |
| 40 to 41     | Sources and reusable reference templates                                          |

### Included companion files

The implementation kit contains five editable Excalidraw diagrams, their PNG previews, daily update and decision templates, an issue specification template, an agent activity log and a demo evidence checklist. Import each .excalidraw file through Excalidraw's Open menu and redraw it as a learning exercise.

### Reading checkpoints

Before coding, explain the two clocks, the source of truth and idempotency. Before building recommendations, explain the fuel and capacity constraints. Before recording a demo, explain stale mode, uncertain writes, recovery and your baseline comparison.

## 03 Requirements and evaluation evidence

The supplied participant brief defines an end to end platform with an operator application, backend, meaningful intelligence, reproducible deployment, observability, resilience and load testing. The integration guide defines the simulator contract. Your requested five day learning plan and Spring Boot plus React stack determine the implementation approach. \[B pages 2 to 9; G pages 2 to 4\]

| **Criterion**                     | **Brief weight** | **Evidence to produce**                                                                                                |
|-----------------------------------|------------------|------------------------------------------------------------------------------------------------------------------------|
| Working product and UX            | 20%              | Dashboard, review and approval, shipment tracking                                                                      |
| Intelligence and decision quality | 20%              | Forecast, station and depot projection, anomaly detection, feasible allocation with alternatives, baseline comparison  |
| Architecture and integration      | 15%              | Diagram, typed adapter, REST and SSE behavior                                                                          |
| DevOps and engineering quality    | 15%              | Compose startup, CI, tests, clear repository                                                                           |
| Resilience and response           | 15%              | Fault recording with recovery and guarded writes                                                                       |
| Observability and performance     | 10%              | Prometheus metrics from day one, status panel with p95 latency and error rate, logs, latency and resource measurements |
| Demo and understanding            | 5%               | Explain one complete decision and one failure                                                                          |

These weights come from the BUP draft, not a confirmed Poridhi selection rubric. Your screenshots show lab topics, but they do not establish the internship's exact scoring, required cloud platform or accepted daily update channel. Use the mapping as a preparation aid and keep organizer clarifications in a decision log. \[B page 11; L1 and L2\]

### Acceptance stories

- An operator can filter fuel and region, identify a station at risk, inspect why it is at risk and see the data timestamp.

- An operator can inspect a proposed depot, route, fuel and quantity, approve it once, and see the shipment status change in the simulator.

- During an API fault, the last valid data remains readable with a stale warning, approval is blocked, and recovery refreshes the world before approval is enabled.

- A new engineer can launch the stack from the README, reproduce a crisis test and inspect measured results.

- While the simulator clock is RUNNING, an operator can review and approve a proposal; the server revalidates it against a fresh read and never sends more than the reviewed quantity.

- When a depot supply is delayed or reduced, the operator sees a depot shortfall window and the stations it would affect first.

- A demand spike is flagged as an anomaly within a measured number of ticks, and a quiet run produces no false flag.

### Brief coverage check

Use this table to confirm that every requirement of the participant brief has a place in the plan. Section numbers refer to the brief.

| **Brief requirement**            | **Plan coverage**                                                                      | **Chapters** |
|----------------------------------|----------------------------------------------------------------------------------------|--------------|
| Operator application (section 6) | Dashboard, risk table, recommendations, ledger, health and decision history            | 17, 18       |
| Intelligence (section 7)         | EWMA forecast, station and depot projection, anomaly detection, constrained allocation | 13 to 16     |
| Decision support (section 9)     | Reasons, constraints, estimated effect, uncertainty metadata and alternatives          | 14, 15, 17   |
| Crisis handling (section 10)     | Demand spike, delay, depot constraint, route disruption and combined crisis            | 16, 20       |
| Surprise events (section 11)     | Official faults, malformed payload fixture, forecast failure switch, load increase     | 19, 20, 24   |
| Resilience (section 12)          | Fallback policy, reject and alert, human review, cached degraded mode                  | 19           |
| DevOps (sections 13 and 14)      | Compose, CI, tests and rollback                                                        | 22, 23       |
| Observability (section 15)       | Application, system and intelligence metrics, logs, status panel                       | 21           |
| Health and status (section 16)   | System status endpoint and panel with p95 latency and error rate                       | 18, 21       |
| Load testing (section 18)        | k6 on dashboard and recommendation paths in both modes                                 | 24           |
| Security hygiene (section 19)    | Configuration, input validation and server side authorization                          | 23           |
| Guardrails (section 26)          | Simulated banner, operator approval, no real systems                                   | 04, 12, 17   |
| Final demo (sections 20 and 23)  | Eight minute script with crisis, fault and recovery                                    | 38           |

## 04 Scope and production decisions

### What belongs in the first release

| **Priority** | **Deliverable**                                         | **Done when**                                                                                |
|--------------|---------------------------------------------------------|----------------------------------------------------------------------------------------------|
| P0           | Read and display simulator state                        | Four stations and three fuels render from real REST data; mode is shown                      |
| P0           | Station and depot risk with constrained recommendations | Reasoned output, alternatives shown, zero known constraint violations                        |
| P0           | Mode aware human approval and safe writes               | Duplicate approval and timeout do not duplicate shipment; works PAUSED and RUNNING           |
| P0           | Failure and stale handling                              | Cached read mode, write guard and recovery demonstrated                                      |
| P0           | Core observability                                      | Prometheus endpoint live from day one; status panel shows health, p95 latency and error rate |
| P0           | Rule based demand anomaly detection                     | Injected spike flagged within a measured number of ticks; no flag in a quiet run             |
| P0           | Repeatable delivery and evidence                        | Compose, CI, tests, load report and demo runbook                                             |
| P1           | Readable event and decision history                     | Snapshot, reason, actor and outcome are inspectable                                          |
| P1           | Grafana summary dashboard                               | Operational and application metrics visible                                                  |
| P2           | LLM explanations or custom MCP                          | Only after all P0 checks pass                                                                |

### Architecture decisions to record

ADR 001: Use a modular monolith. A single Spring Boot process keeps transactions, debugging and deployment manageable in five days. Modules remain separated by interfaces so the simulator adapter or forecasting engine can be changed without rewriting controllers.

ADR 002: The simulator owns inventory, demand and shipment status. PostgreSQL stores observations, snapshots, recommendations, command intent and audit records. An application snapshot is evidence, not an alternative operational world.

ADR 003: Require operator approval. Deterministic validation controls quantities; a generative model, if added, can explain or investigate but cannot dispatch fuel.

ADR 004: Prefer a local Compose deployment that reviewers can reproduce. AWS is excluded from this learning plan as requested. A public URL can be added later if Poridhi specifically requires one.

ADR 005: Approval is mode aware. A proposal is a reviewed envelope, not a frozen order. At approval the backend re-reads the world and may send less than the reviewed quantity but never more and never to a different destination. This keeps approval usable when the simulator clock runs while a human reviews.

ADR 006: Detect with rules before models. A rule on demand relative to the documented hour of day profile is explainable, testable against injected spikes and needs no training data. A learned detector waits until its benefit over the rule is measured.

ADR 007: Metrics first. The Prometheus endpoint and a status panel ship on day one so each later feature adds its measurements when it is built, not at the end.

Keep decisions short: context, choice, rejected alternative, consequence and verification. A production engineering mindset means making tradeoffs explicit and checking them, rather than adding infrastructure without a demonstrated need.

## 05 Understand the fuel domain and its clocks

The official world contains two regions, two depots, four stations, six routes and the fuel types DIESEL, PETROL and OCTANE. It is deterministic for the same scenario, seed, action sequence and event injections. The published image starts with a baseline scenario without preloaded crises. \[G pages 2, 4 and 13 to 15\]

| **Concept**    | **Meaning and design implication**                                                   |
|----------------|--------------------------------------------------------------------------------------|
| Depot          | Fuel inventory by type, tank capacities and dispatch capacity shared across fuels    |
| Station        | Inventory and capacity by fuel, demand profile, multiplier and OPEN or OUTAGE status |
| Route          | Specific depot to station connection with transit_ticks and max_shipment             |
| Allocation     | One shipment request that becomes PENDING, IN_TRANSIT, ARRIVED, FAILED or CANCELLED  |
| Supply arrival | Incoming depot stock; scheduled quantity is not available inventory yet              |
| Domain event   | Changes the simulated world for a duration measured in ticks                         |
| Fault          | Changes API behavior for a duration measured in wall clock seconds                   |

### Two clocks must remain separate

Simulation tick t and sim_time describe the fuel world. Read tick_minutes from GET /v1/instance. If it reports 15, then four ticks equal one simulated hour and 96 equal one simulated day. Route transit time in hours is transit_ticks multiplied by tick_minutes divided by 60.

Wall clock time controls request timeouts, cache age, circuit breakers and fault expiration. A paused simulator can remain at tick 12 for ten minutes while its API is fully reachable. Do not label it stale just because the tick is unchanged. Track the time of the last successful validated REST refresh separately.

Start paused and use /admin/step during tests. Before every scenario, record seed, tick_minutes, image version and the injected events. Use sim_time as returned, including its timezone. Your daily reports use Dhaka time; do not replace the simulator's time with your laptop clock.

### Simulation speed and the two operating modes

SIMULATION_SPEED is the number of ticks per wall second while the simulator is RUNNING, and TICK_MINUTES is the simulated minutes per tick. \[G page 4\] Multiply them to see how fast the fuel world moves compared with a human.

| **Setting**                                 | **Simulated time per wall second** | **Wall time per simulated day** | **Ticks between two second refreshes** |
|---------------------------------------------|------------------------------------|---------------------------------|----------------------------------------|
| Guide page 4 compose: speed 15, tick 8      | 120 minutes                        | 12 seconds                      | 30                                     |
| Guide page 5 table: speed 8, tick 15        | 120 minutes                        | 12 seconds                      | 16                                     |
| Proposed human paced demo: speed 1, tick 15 | 15 minutes                         | 96 seconds                      | 2                                      |

These figures are arithmetic from the documented settings. Measure the real tick rate in the day one experiments; the simulator may not sustain the configured speed.

Two consequences follow. A thirty second review spans 60 simulated hours at either documented default and 7.5 simulated hours at speed 1, so a proposal is old by the time it is approved. And a rule that rejects a proposal when it is one tick behind can never be satisfied while the clock runs.

The application therefore supports two modes, read from the status field of /v1/instance on every refresh.

| **Mode** | **Used for**                                                            | **Refresh trigger**                                                        | **Approval rule**                                                          |
|----------|-------------------------------------------------------------------------|----------------------------------------------------------------------------|----------------------------------------------------------------------------|
| PAUSED   | Deterministic demos, tests and evaluation runs stepped with /admin/step | Two second poll plus a refresh after each step                             | Proposal must belong to the current tick; otherwise regenerate             |
| RUNNING  | Continuous operation and surprise events while the clock advances       | Coalesced simulation.tick events with a minimum interval; poll as fallback | Server revalidates the reviewed envelope against a fresh read (chapter 12) |

Treat PAUSED as the default for evidence collection. Run the main demo story paused, then show one approval in RUNNING at a low speed to prove the mode aware path.

### Explain it without an agent

If the simulator is paused, can a 30 second API fault expire? Yes. If its tick size changes, should a forecast of 12 ticks mean the same hours? No. Show how the application converts each quantity. Then compute how many simulated hours pass during a 30 second review at your chosen speed and explain why a one tick staleness rule fails while the clock runs.

## 06 Resolve simulator contract ambiguities first

Use the integration guide for concrete endpoints. The brief's /inventory, /allocation, /simulate and /advance-time examples are conceptual. They are not the integration API. There is no documented public dry run or advance-time endpoint. \[B page 3; G pages 4 to 11\]

| **Issue in supplied guide**      | **Implementation decision and verification**                                                                                                                                                                                 |
|----------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Tick and speed defaults conflict | Page 4 compose uses speed 15 and tick size 8; page 5 lists speed 8 and tick size 15. Set both explicitly and assert /v1/instance.tick_minutes.                                                                               |
| Replay success status conflicts  | Section 5.4 says replay returns 201; page 15 lists 200. Accept both with a valid allocation body and test the published image.                                                                                               |
| Only one write wording           | Creation is POST /v1/allocations. The guide also documents POST /v1/allocations/{id}/cancel for PENDING only. Treat cancellation as an explicit exception.                                                                   |
| CONSTRAINED depot is allowed     | Creation accepts OPEN and CONSTRAINED. Do not assume CONSTRAINED means closed or invent a numerical capacity reduction. Use returned fields and test actual behavior.                                                        |
| Dispatch and reservation details | Confirm which current tick allocations count, when inventory is deducted and when a PENDING shipment departs. Do not infer these from dashboard animation.                                                                   |
| Real tick rate while RUNNING     | Speed and tick size are documented but not the sustained rate. Run the simulator for 30 seconds at the chosen setting, record ticks per wall second in docs/contract-notes and derive refresh limits from the measured rate. |

### Day one contract notebook

Record the request, response status, body, relevant headers and tick before and after each experiment. Save the local Swagger or OpenAPI contract if exposed through /docs. Verify the document's examples against the published image rather than silently correcting a PDF in your code.

Also verify demand history ordering and filtering, reset behavior, unknown JSON fields, nullable arrival ticks and whether a stream fault interrupts an already open connection. Store unresolved questions in docs/contract-notes, with the implementation's conservative fallback.

The simulator source must stay unmodified. A test stub may represent malformed or unavailable responses in isolated tests; it does not replace the official simulator in the end to end demonstration. \[G page 4\]

## 07 Official simulator API reference

Local host base URL: [http://localhost:8000.](http://localhost:8000.) Inside the application Compose network: [http://simulator-api:8000.](http://simulator-api:8000.) All paths in this chapter are simulator paths, not your Spring Boot endpoints. \[G pages 4 to 10\]

| **Method and path**                             | **Use**                                                        |
|-------------------------------------------------|----------------------------------------------------------------|
| GET /v1/health                                  | Liveness only; bypasses faults                                 |
| GET /v1/instance                                | Tick, sim_time, seed, scenario and tick size                   |
| GET /v1/regions                                 | Region labels and factors                                      |
| GET /v1/depots and /v1/depots/{id}              | Inventory, dispatch limits and status                          |
| GET /v1/stations and /v1/stations/{id}          | Tank capacity, inventory, demand profile and status            |
| GET /v1/routes                                  | Availability, endpoints, shipment limit and transit ticks      |
| GET /v1/supply-arrivals                         | Planned, delayed and arrived depot replenishment               |
| GET /v1/events                                  | Domain event state and parameters                              |
| GET /v1/allocations                             | Shipment ledger including idempotency keys                     |
| GET /v1/demand-history?station_id=...&limit=200 | Demand observations; limit is clamped to 1 through 2000        |
| GET /v1/metrics                                 | Cumulative service level, unmet liters and allocation failures |
| GET /v1/stream                                  | SSE notifications; REST remains authoritative                  |
| POST /v1/allocations                            | Create one allocation                                          |
| POST /v1/allocations/{id}/cancel                | Cancel PENDING only; key remains occupied                      |

### Creation body

```
{
  "idempotency_key": "run-uuid-command-uuid",
  "source_depot_id": "depot-gazipur",
  "destination_station_id": "station-mirpur",
  "route_id": "route-gazipur-mirpur",
  "fuel_type": "DIESEL",
  "quantity": 3000
}
```

The idempotency key belongs in the JSON body, has length 1 to 150 and is reused only for the exact same request. Do not send it only as an Idempotency-Key HTTP header. Check X-Simulator-Stale on every public GET. \[G pages 5, 8 to 9\]

## 08 Design the system before implementing it

![System architecture diagram (chapter 08)](images/image1.png)

### Draw and explain this in Excalidraw

Start with the operator and simulator, then draw the backend boundary. Add the read model, pure decision logic, approval command and monitoring. Label each arrow with data and ownership. Import 01_system_architecture.excalidraw for an editable reference.

Use one backend refresh loop for the small simulator world. Browser traffic reads that cached, validated view. A hundred dashboard clients should not create a hundred simulator polling loops. Keep external I/O out of the pure forecasting and allocation functions.

The simulator integration adapter translates HTTP and response headers into domain results. Controllers translate application requests into use cases. A command worker owns external mutations. This separation makes failure handling and tests easier to explain.

Admin endpoints belong in a local test driver and demo operator console. They must never become a hidden fallback source of operational data because they bypass injected faults. \[G pages 4 and 10\]

## 09 Backend modules and repository structure

Use Java 21 and a stable Spring Boot release compatible with the libraries you choose. Generate the project with Spring Initializr, pin the selected version and commit Maven Wrapper. The checked Spring requirements support Java 21; avoid snapshot releases during this project. \[S1\]

```
fuel-platform/
  backend/              # Spring Boot and Maven Wrapper
  frontend/             # React TypeScript and lockfile
  infra/                # Nginx Prometheus Grafana config
  scripts/              # smoke demo reset and scenario drivers
  tests/                # integration and load workloads
  docs/                 # ADRs contracts runbooks evidence
  .github/workflows/    # CI build and test
  compose.yaml
  .env.example
  README.md
  CLAUDE.md
```

| **Java package** | **Responsibility**                                                                            |
|------------------|-----------------------------------------------------------------------------------------------|
| simulator        | Typed DTOs, REST client, headers, SSE and error parsing                                       |
| ingestion        | Mode aware refresh scheduling, validation, quality and atomic snapshot publication            |
| risk             | Demand rates, station and depot shortage trajectories, anomaly flags and uncertainty metadata |
| allocation       | Feasibility checks, policy, deterministic ranking and alternatives                            |
| decision         | Recommendation lifecycle, mode aware approval revalidation and stale checks                   |
| command          | Durable intent, worker lease, retry and reconciliation                                        |
| audit            | Decision and operator activity records                                                        |
| web              | Controllers, request validation and exception mapping                                         |
| observability    | Metrics, structured logging, component status and recent p95 latency and error rate           |

Start with Spring Web, Bean Validation, Data JPA, Actuator, PostgreSQL driver and migration support. Use one configured HTTP client with finite connect and response timeouts. For Spring MVC, an isolated SSE reader thread can avoid learning an entire reactive stack. If using WebClient, keep Reactor details inside the adapter.

Controllers remain thin. Services implement use cases. Pure functions accept immutable snapshots and return results. Inject a Clock for wall time and pass simulator tick values explicitly. Avoid a generic framework or interfaces with only one trivial CRUD call unless a real boundary requires them.

## 10 Store evidence and command intent

![Stored evidence and command intent diagram (chapter 10)](images/image2.png)

Use an application run UUID because the simulator instance id remains 1 and reset can reuse ticks and entity ids. On an observed tick rollback or reset notice, suspend writes, create a new run, retire old recommendations and reconcile. The local reset driver should explicitly start a new app run, which also covers resets at the same tick. \[G pages 5 and 11\]

Publish a complete validated snapshot atomically. Store tick bounds, observed_at wall time and quality separately from the JSON payload. Keep a bounded number of snapshots and retain the snapshot used for each decision. Deduplicate demand rows with a unique run, station, fuel and tick key.

recommendation records include policy_version, snapshot_id, inputs, proposed lines, reasons, expected effects and review status. allocation_command stores the immutable request and idempotency key before any POST. A unique recommendation line prevents duplicate browser approval.

Use database migrations and transactions. An approval transaction saves the actor, audit record and command rows together. The worker sends after commit and updates results in a separate transaction. A local database transaction cannot include the simulator's transaction; reconciliation closes that gap.

## 11 Read and refresh the world safely

### Ingestion sequence

- Read /v1/instance at the beginning. Fetch depots, stations, routes, arrivals, allocations, events, metrics and bounded demand history through the adapter. Read /v1/instance again.

- Validate required fields, known fuel types, nonnegative finite quantities, capacities, ids and references. Missing inventory is unknown, not zero. Reject impossible negative values or broken links and keep the previous valid snapshot.

- Inspect every response header. If any required response says X-Simulator-Stale: true, invalidate its eligibility for decisions. Retain the last known good display with an explicit stale label; do not replace fresh data with the flagged payload.

- If the tick changed during the refresh, label the view with tick bounds. Retry at most once when reasonable. Do not claim an atomic simulator snapshot because the API offers no snapshot transaction. For approval, demand a recent consistent read or request that the local operator pause the simulator.

- Publish the new view only after validation. Recompute risks once per distinct tick or material world change. Keep one refresh in flight and coalesce repeated notifications into a bounded dirty flag. In RUNNING mode at high speed ticks can outpace refreshes; the minimum interval keeps simulator request volume bounded and the view is labeled with its tick bounds.

### SSE supplements polling

Listen to simulation.tick, allocation.status_changed, inventory.updated and simulator.notice. After a relevant event, re-GET the affected REST resources, coalescing events already covered by an in-flight refresh. Event payloads do not directly update authoritative inventory. Reconnect with backoff and perform a full REST refresh after every reconnect. \[G pages 9 to 10\]

The stream has no Last-Event-ID replay and a 200 event subscriber queue. A connected comment and 15 second keepalive are normal. Use a watchdog comfortably above 15 seconds, such as 35 seconds, with read timeouts chosen for streaming. In PAUSED mode poll REST every two wall seconds and refresh after each step. In RUNNING mode the primary trigger is the coalesced simulation.tick event with a minimum refresh interval, starting at 500 ms, and the two second poll is the fallback; tune both after measuring load. Keep the reader thread free of heavy work, because at high speed a slow reader can fall more than 200 events behind and be dropped. Polling continues while stream reconnects. \[G pages 10 and 16\]

### Mode aware freshness policy proposed for this application

Read the mode from /v1/instance on every refresh and show it in the status strip. Show wall age and simulated tick lag separately. The values below are configurable application policies.

| **Rule**        | **PAUSED**                                                                   | **RUNNING**                                                                                                                                              |
|-----------------|------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------|
| Data age limit  | Block approval when the last valid refresh is over 10 wall seconds old       | Same limit, measured from the last valid refresh                                                                                                         |
| Tick lag        | Proposal must belong to the current tick; if the tick changed, regenerate it | Not a blocking rule; lag is displayed and approval revalidates against a fresh read                                                                      |
| Refresh trigger | Two second poll and a refresh after each admin step                          | Coalesced tick events with a 500 ms minimum interval; two second poll as fallback                                                                        |
| Always blocking | Stale header, invalid payload, unknown write, reset, open circuit            | Same                                                                                                                                                     |
| Proposal expiry | Not needed while the tick does not move                                      | Configurable wall time, start at 120 seconds; the review dialog warns when the clock outpaces the review and offers Pause for review in the demo console |

Clear guards only after a complete valid refresh and reconciliation. A reviewed proposal is an envelope, not a frozen order; chapter 12 defines how approval revalidates it.

## 12 Approve and submit allocations exactly once in effect

![Approval and submission sequence diagram (chapter 12)](images/image3.png)

The approval endpoint validates the recommendation status and snapshot age, refreshes relevant simulator resources, checks the complete proposal, then commits one command per approved line. A unique database constraint on recommendation_id plus line_id makes repeated approval return the existing command.

Persist the stable idempotency key and exact request JSON first. One worker sends each command with bounded retries using that same key and body. A timeout after a POST means the result is unknown. It does not mean the simulator rejected the request.

Reconcile unknown outcomes by reading /v1/allocations and matching idempotency_key. If it exists, store the simulator id and current shipment state. Otherwise retry the exact persisted request within the retry budget. Never change quantity while reusing its key. \[G pages 8 to 9\]

A multi-line recommendation is not an atomic simulator batch. Submit serially, reserve locally while unresolved and refresh after each acceptance. If a later line conflicts, show partial completion and recompute the remainder. Do not hide already accepted shipments behind a generic failed toast.

### Revalidate the reviewed envelope at approval

A proposal line stores the source, destination, route, fuel and reviewed quantity. When the operator approves, the backend neither sends the stored line blindly nor rejects it merely because ticks have passed. It re-reads the relevant simulator resources, runs the validator for that line against the fresh state and applies these rules.

| **Fresh check result**                                                                                             | **Action**                                                                 | **Operator sees**                       |
|--------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------|-----------------------------------------|
| Line still feasible and the need is still positive                                                                 | Send the reviewed quantity                                                 | Approved as reviewed                    |
| Feasible only for a smaller quantity because headroom, source inventory, dispatch allowance or remaining need fell | Send the smaller quantity, never more than reviewed; store both quantities | Approved with reduction and the reason  |
| Route disrupted, station in OUTAGE, depot closed, or the best source or route would change                         | Send nothing; mark the line CHANGED and regenerate a proposal              | Re-review required with the difference  |
| Fresh read stale, invalid or circuit open                                                                          | Send nothing; keep the proposal                                            | Approval blocked with a specific reason |

The rule that quantity may fall but never rise keeps the human review meaningful, because the operator reviewed an upper bound. The idempotency key and exact request body are fixed in the approval transaction after revalidation, so every retry reuses that body. Persist the reviewed line, the fresh read tick and the final request in the audit record.

Test both modes: approve while PAUSED at the same tick, approve after stepping eight ticks, and approve while RUNNING after a disruption removes the route. The third case must send nothing.

The demo console may offer Pause for review. It is an explicit operator action that calls the simulator admin pause and is written to the audit history. It is a convenience, not a requirement for approval, and it stays outside the public application API.

## 13 Forecast demand with an explainable baseline

### Use demand rather than observed sales

For each station and fuel, use demand_liters from /v1/demand-history, not served_liters. Sales are capped during stockouts and would underestimate actual need. Sort by tick, deduplicate and use a recent bounded window. With limit 200 shared across fuels, split rows by fuel before counting observations. \[G pages 7 to 8\]

Start with a naive baseline equal to the last complete observed demand rate. The candidate is an exponentially weighted moving average of the last eight observations. As a proposed starting value, use alpha 0.3: new_rate = 0.3 times observed_rate plus 0.7 times previous_rate. Convert observed liters per tick to liters per simulated hour using tick_minutes.

### Handle cold start honestly

With fewer than four observations, use a versioned profile estimate from the guide: daily profile liters divided by 24, multiplied by the documented hour factor, region factor and current station multiplier. Store these profile constants as documented simulator-specific configuration. Return LOW_DATA metadata. Validate the estimate against observations before relying on it. \[G pages 14 to 15\]

If using observed demand in the EWMA, its current multiplier is already represented in the observations. Do not multiply the EWMA by demand_multiplier again. For an active newly started spike, an optional adjustment may scale the previous estimate by the ratio of new multiplier to old multiplier until enough post-event samples exist. Record the adjustment and test against double scaling.

### Use uncertainty that you can defend

Return sample_count, method, horizon, recent absolute error and data quality. A conservative stress forecast can use 1.25 times the central rate as a clearly labeled policy scenario. It is not a calibrated confidence interval or stockout probability. Do not invent a percentage confidence from the profile noise field.

### Evaluate without leaking future data

Predict tick t plus 1 using only data through t, then compare with observed demand when it arrives. Record MAE in liters per tick and WAPE as total absolute error divided by total actual demand when that denominator is positive. Compare candidate and naive forecasts on identical held-out tick ranges, including crisis periods.

### Detect demand anomalies with a transparent rule

The brief loop runs Observe, Detect, Predict. Prediction says what demand is expected; detection says that observed demand has left what the documented profile explains. Start with a rule you can state in one sentence.

For each station and fuel, compute the expected demand per tick from the documented profile: daily liters divided by the number of ticks in a day, multiplied by the hour of day factor and the region factor, with the multiplier taken as 1.0. Use the hour of sim_time as returned. Divide observed demand_liters by this expected value to get a ratio. Using the ratio keeps normal morning and evening swings from being reported as anomalies. \[G pages 14 to 15\]

| **Rule**            | **Condition**                                                             | **Output**                                      |
|---------------------|---------------------------------------------------------------------------|-------------------------------------------------|
| R1 Station anomaly  | Ratio at or above 1.35 for 3 consecutive ticks (proposed; tune by replay) | DEMAND_SPIKE_SUSPECTED for the station and fuel |
| R2 Regional anomaly | R1 true for at least two stations of the same region                      | REGIONAL_DEMAND_SPIKE                           |
| R3 Recovery         | Ratio below 1.15 for 3 consecutive ticks                                  | Flag cleared                                    |
| R4 Cause label      | An ACTIVE demand_spike event in /v1/events covers the station             | KNOWN_EVENT, otherwise UNEXPLAINED              |

Per tick noise is 8 to 12 percent in the documented profiles. If it is independent, the mean of three ticks varies by roughly 5 to 7 percent, so a ratio of 1.35 sits far outside normal variation. Confirm this with a quiet replay instead of assuming it. \[G page 14\]

The detector is advisory. It adds a flag, a reason and a timestamp to the risk output and raises an alert. It does not change quantities; the optional spike adjustment in the forecast section is the only place a flag may influence a number, and that use must be recorded.

Evaluate by replay. Run a quiet 192 tick scenario and count false flags. Then inject demand_spike with multiplier 1.8 on region-dhaka at a known tick and record how many ticks pass before R1 and R2 fire. Report both numbers as measured. A spike shorter than three ticks will not be flagged; state that limit.

If the detector throws or lacks data, the risk table continues without flags and shows detector status UNAVAILABLE. It never blocks risk output or approval.

## 14 Turn forecasts into shortage risk

For each station and fuel, project inventory over a short configurable horizon, such as the next eight simulated hours. Evaluate central and stress demand trajectories separately. This is a deterministic forecast scenario, not a probability of stockout.

### Projection rules

- Start with current station inventory. For each future tick, add eligible incoming allocation arrivals, subtract forecast demand, and record projected unmet liters when available stock is insufficient.

- Use official expected_arrival_tick for IN_TRANSIT shipments. PENDING arrivals are uncertain until departure is observed. Show a best case estimate for them separately, with the departure convention established in contract tests.

- A planned depot SupplyArrival is not a station delivery. It can affect future depot availability after arrival, but must not be added directly to station inventory.

- Report first projected stockout tick, equivalent simulated hours, demand horizon, outstanding shipments and the constraints that prevent replenishment. Consider arrival timing; fuel arriving after depletion does not prevent the earlier stockout.

### Worked example with hypothetical values

At tick 40 with tick_minutes 15, a station has 900 liters of DIESEL and a forecast of 200 liters per hour. With no inbound fuel, cover is 4.5 simulated hours or 18 ticks. A route with transit_ticks 2 takes 30 simulated minutes after departure. If the verified departure model adds one tick, the estimated arrival is 45 minutes after creation.

Suppose the target is six hours of cover after arrival, with a 200 liter safety reserve. Expected stock at a 0.75 hour arrival is 900 minus 150 = 750 liters. Desired stock is 6 times 200 plus 200 = 1400 liters. The candidate allocation is 650 liters before applying capacity and depot constraints.

### Project depot supply and detect supply changes

Station risk answers whether a pump will run dry. Depot risk answers whether the fuel needed to prevent it will exist and arrive in time. A shipment_delay or supply_shortfall event hurts the depot first and the station later, so the depot needs its own projection. \[G page 12\]

For each depot and fuel build two cumulative curves over the horizon. Available supply at tick t is current depot inventory plus the quantity of every SupplyArrival with status SCHEDULED or DELAYED and planned_tick at or before t; an ARRIVED supply is already in inventory and is not added again. Required replenishment at tick t is the sum, over stations in the same region, of the central forecast demand up to t minus current station inventory and eligible inbound allocations, floored at zero per station. The first tick where required replenishment exceeds available supply is the projected depot shortfall tick.

Do not subtract PENDING or IN_TRANSIT allocations from depot inventory a second time if the simulator already deducted them at creation; the day one experiment decides this. The cross region routes (gazipur to karnaphuli, patiya to mirpur) are recorded as a possible relief path and are not counted as supply of the other depot. \[G page 14\]

Hypothetical example. A depot holds 6,000 liters of DIESEL. Stations in its region need 1,500 liters per simulated hour of replenishment, so inventory covers four hours. A scheduled arrival of 18,000 liters is planned in 20 ticks, which is five hours at 15 minutes per tick, so the shortfall window runs from hour 4 to hour 5. A shipment_delay of 4 ticks moves the arrival to hour 6 and the window grows to two hours. The risk table shows the depot, the window, the delay and the stations that feel it first.

Detect supply changes from snapshots, because the simulator overwrites planned_tick and quantity. Keep the first observed planned_tick and quantity for each supply id in the stored snapshots. A later planned_tick means a delay of that many ticks; a lower quantity means a shortfall of that many liters. Show both in the reason text. These events are one shot and do not undo themselves, so do not wait for a resolution. \[G page 12\]

Depot labels use the same thresholds as station labels, with the depot shortfall tick as the trigger. The risk response carries entityType STATION or DEPOT.

### Risk labels proposed for this application

CRITICAL means the central projection reaches zero before the earliest feasible replenishment or within two hours. WARNING means a projected central shortage within the eight hour horizon. WATCH means only the stress scenario reaches shortage. UNKNOWN means inadequate or stale data. Keep labels and thresholds configurable and show the reason beside them.

With zero forecast demand, do not divide by zero or show a fake infinite precision stockout time. Return no estimated stockout within the horizon. Unknown demand must remain unknown.

## 15 Build a constrained allocation heuristic

Treat the policy as a pure Java function: validated world snapshot plus forecast plus policy configuration produces feasible proposed shipment lines and explanations. It writes nothing. A separate validator runs again at approval.

### Allocation sequence

- Create station and fuel needs from projected stock at arrival, target cover and safety reserve. Subtract eligible inbound allocations once. Do not recommend to an OUTAGE station.

- Rank the most urgent needs by projected stockout time. Break ties by lowest projected coverage, then station id and fuel type for reproducibility. Optional regional service weights must be explicit and evaluated for fairness.

- Find only routes whose source and destination match and whose status is AVAILABLE. Rank feasible routes by earliest arrival, then by the depot projected cover after the shipment (higher is better), then by stable route id. A cross region route is chosen only when it is feasible and the source depot own region stays out of CRITICAL after the shipment. The simulator has no transport cost field; do not claim cost optimization.

- Set quantity to the minimum of need, current depot fuel inventory, route.max_shipment, remaining depot dispatch allowance and safe destination headroom. Round down to the chosen precision and discard nonpositive values.

- Reserve proposed quantities in the working copy before planning the next line. Depot dispatch allowance is shared across all fuel types. Account for commands already reserved by this app and simulator allocations counted by the verified current tick rule.

- Recompute projected cover after each proposal. Continue until needs or feasible supply are exhausted. Return unmet needs and precise reasons such as NO_AVAILABLE_ROUTE, DISPATCH_LIMIT or SOURCE_SHORTFALL.

### Record alternatives and why they lost

For each proposed line keep up to two alternatives: the next ranked feasible route, and a smaller quantity that leaves shared dispatch for a more urgent need. Each alternative stores route, quantity, estimated arrival, projected cover and a reason code: LATER_ARRIVAL, LOWER_SOURCE_COVER, DISPATCH_RESERVED_FOR_MORE_URGENT or NO_ALTERNATIVE_ROUTE.

Report impact as before and after values for the same station and fuel: projected stockout time or covered hours without the shipment and with it, labeled estimated impact. Tongi and Coxsbazar have one route each, so show NO_ALTERNATIVE_ROUTE for them instead of inventing one. Do not present before and after values as probabilities.

Alternatives inform the operator; approval applies to the primary line. Displaying alternatives is required for the first release. Letting the operator pin an alternative, which regenerates a proposal that passes the same validator, is optional.

### Constraints to defend

The simulator validates current station inventory plus the new quantity against capacity. Your conservative validator should also reserve headroom for PENDING and IN_TRANSIT fuel so several valid submissions do not collectively overfill a station. Avoid subtracting accepted allocations from depot inventory twice if the simulator has already deducted them; establish its reservation behavior in the day one experiments. \[G pages 8 to 9\]

Treat route.max_shipment as a per shipment limit, not an invented per tick route limit. OPEN and CONSTRAINED depots can be eligible. Respect actual numeric dispatch capacity, and show the constraint status as context. For the documented network, Tongi and Coxsbazar each have only one route; an alternative cannot be fabricated. \[G pages 9 and 14\]

A heuristic is an accepted intelligence option in the brief. Its value comes from measurable service improvement and feasible decisions. Do not call it globally optimal. \[B page 5\]

## 16 Prove the allocation policy is useful

### A small manual allocation exercise

Use hypothetical test data with one depot holding 5000 liters of DIESEL and 4000 liters of PETROL, with 4000 liters of dispatch remaining this tick. Station A urgently needs 2500 liters of DIESEL; its route allows 3000 and its safe headroom is 3000. Station B needs 2200 liters of PETROL; its route allows 3000 and headroom is 2500.

The urgency policy gives A 2500 and reserves it. Only 1500 liters of shared dispatch remain, so B receives 1500 and has 700 liters of unresolved need. A policy that independently allocated each fuel's full need would incorrectly dispatch 4700 liters. Write a unit test that catches this mistake.

### Compare policies in the official world

| **Experiment item** | **Required control or measurement**                                                                                 |
|---------------------|---------------------------------------------------------------------------------------------------------------------|
| Baseline            | A feasible reactive low-stock policy with documented threshold, using the same validator                            |
| Candidate           | Forecast and arrival aware priority allocation policy                                                               |
| World control       | Same image, seed, tick size, initial reset and exact event schedule                                                 |
| Operator control    | Approve feasible proposals at the same tick cadence for both policies                                               |
| Horizon             | Suggested 192 ticks at 15 minutes, then report the actual tested horizon                                            |
| Metrics             | Unmet liters, service level, FAILED shipments, dispatched liters and constraint rejections                          |
| Report              | Per scenario raw values and differences, with limitations and no invented scores                                    |
| Detection check     | False flags in a quiet 192 tick run and ticks until R1 and R2 fire after an injected spike, reported as measured    |
| Modes               | Run the comparison PAUSED and stepped; run one smoke scenario RUNNING at low speed to confirm approval revalidation |

Replay normal demand, a demand spike, a route disruption and a combined crisis. Pause and step deterministically. A script may automate the operator cadence for evaluation in a test-only mode, with that mode clearly documented. The normal application still requires review.

Cumulative simulator /v1/metrics are ground truth since reset. Record start and end values or reset before each run. service_level is served divided by served plus unmet. allocation_liters excludes PENDING, FAILED and CANCELLED. allocation_failures counts FAILED shipments and does not include POST validation rejections; measure those separately. \[G page 8\]

Keep an in-app projection labeled estimated impact. A validated counterfactual needs a separate replay with the same initial world. There is no documented /v1/simulate endpoint, so do not describe your arithmetic preview as an official simulator dry run.

## 17 Build the React operator workflow

Use React with TypeScript and Vite, commit the package lockfile and pin a Node version compatible with the selected Vite release. The checked Vite guide lists Node 20.19 or 22.12 and above within those supported lines; choose a currently supported LTS version and record it. Build the UI around the operator's decisions and explicit states. \[S2; S3\]

### Screen structure

| **Area**                         | **Content and interaction**                                                                                                                                          |
|----------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Top status strip                 | SIMULATED ENVIRONMENT, tick, sim_time, PAUSED or RUNNING mode badge, data age and quality                                                                            |
| Overview                         | Fuel and region filters, depot inventory, station demand, incoming supply and service level                                                                          |
| Risk table (stations and depots) | Entity, fuel, liters, forecast horizon, projected stockout or shortfall window, anomaly badge, available route and reason                                            |
| Recommendation panel             | Source, destination, route, fuel, quantity, constraints, before and after estimate and up to two alternatives with reason codes                                      |
| Review dialog                    | Snapshot tick, expiry, reviewed quantity shown as an upper bound, mode, actor, confirm approval, duplicate click protection and Pause for review in the demo console |
| Shipment ledger                  | Application command status and official shipment state in separate columns                                                                                           |
| Events and health                | Domain disruptions, integration status, stale mode, recovery, audit history, recent p95 latency and error rate                                                       |

### Interaction states

Represent loading, empty, healthy, low data, stale, dependency unavailable, approval pending, unknown outcome, partial submission, approved with reduction, proposal changed, clock running faster than review and recovered states. Stale information remains visible with its last valid timestamp. Disable approval with a specific reason; hiding the button makes diagnosis harder.

Keep server data in one query/cache layer and local form state separate. Refetch dashboard data every two seconds initially; the backend owns simulator SSE. Cancel obsolete requests, avoid overlapping polls and stop polling when the component unmounts. A query library is optional if you already know one.

Use color plus text for risk. Include liters and simulated hours in every relevant label. Show unknown values as Unknown rather than zero. A simple table is enough for four stations; a map is optional and must use documented coordinates rather than invented precision.

### UI learning exercise

Sketch the normal state, stale state and unknown-write state in Excalidraw before building components. Explain which button is enabled in each state and why. Then implement with small components such as DataFreshnessBanner, StationRiskTable, RecommendationReview and AllocationHistory.

## 18 Define the application API contract

These are proposed Spring Boot endpoints owned by your application. Keep the /api prefix distinct from the simulator's /v1 paths. Write DTO examples and error responses before implementing controllers.

| **Application endpoint**                      | **Behavior**                                                                                                                     |
|-----------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------|
| GET /api/dashboard                            | Latest validated view with snapshot id, tick bounds and quality                                                                  |
| GET /api/risks                                | Station and depot risk with entityType, reasons, method, horizon, anomaly flags and alternatives                                 |
| GET /api/anomalies                            | Current and recent demand anomaly flags with cause label and detector status                                                     |
| POST /api/recommendations                     | Create and store a proposal from one eligible snapshot; no simulator write                                                       |
| GET /api/recommendations/{id}                 | Inspect proposal, constraints, reasons and lifecycle                                                                             |
| POST /api/recommendations/{id}/approve        | Authenticate operator, revalidate the reviewed envelope against a fresh read, persist commands; return existing result on repeat |
| GET /api/commands/{id}                        | APPROVED, SENDING, UNKNOWN, ACCEPTED or REJECTED with details                                                                    |
| GET /api/allocations                          | Reconciled shipment ledger with simulator status                                                                                 |
| GET /api/system-status                        | Backend, DB, simulator reachability, simulation mode, data quality, engine mode, recent p95 latency and error rate               |
| GET /actuator/health and /actuator/prometheus | Management access restricted to local monitoring                                                                                 |

### Read response metadata

```
{
  "snapshotId": "snapshot-uuid",
  "runId": "run-uuid",
  "tickStart": 40,
  "tickEnd": 40,
  "simTime": "2026-01-01T10:00:00+00:00",
  "observedAt": "2026-11-19T06:00:00Z",
  "quality": "FRESH",
  "warnings": [],
  "data": { "stations": [] }
}
```

For approval, the request should identify the proposal; the backend uses its stored lines rather than trusting quantities sent by the browser. Return 202 with command ids for asynchronous submission. A stale or invalid proposal returns 409 with code, message, correlationId and details. Expected validation failures use 400 or 422 consistently; document your choice. In RUNNING mode a 202 response marks each line APPROVED_AS_REVIEWED or APPROVED_REDUCED with reviewed and final quantity. A line that can no longer be sent returns code PROPOSAL_CHANGED with the difference and a regenerated proposal id.

Use application domain error codes for UI decisions. Preserve simulator status and code in audit metadata. Do not turn every upstream 409 into an internal server error or endlessly retry a domain conflict.

## 19 Define graceful failure behavior

| **Failure**                                   | **Application behavior**                                                    | **Recovery proof**                                                |
|-----------------------------------------------|-----------------------------------------------------------------------------|-------------------------------------------------------------------|
| Simulator 503 or timeout                      | Bounded retries; cached read mode; block approval                           | Successful full REST refresh restores write eligibility           |
| Stale header                                  | Invalidate decision eligibility; retain last good display                   | Unflagged validated refresh clears warning                        |
| Malformed or missing fields                   | Reject required bad data; alert; keep valid snapshot                        | Valid payload accepted without zero substitutions                 |
| SSE disconnect or silent loss                 | Backoff reconnect and periodic REST polling                                 | Full refresh after reconnect; no missed shipment state            |
| Forecast computation failure                  | Fallback to simple observed rate with explicit mode                         | Metric and UI show fallback then normal method                    |
| Low data or uncertainty                       | Review required with LOW_DATA explanation                                   | New observations update quality and projection                    |
| POST result unknown                           | Persist UNKNOWN and reconcile by stable key                                 | One simulator allocation after timeout or restart                 |
| Database unavailable                          | Keep in-memory valid read view if present; block writes                     | DB recovers; migrations and reconciliation succeed                |
| Backend restart                               | Restore command intent; refresh before decisions                            | No old proposal replay or duplicate shipments                     |
| Frontend API outage                           | Show last received data as disconnected                                     | A new response restores current state                             |
| Clock runs faster than review                 | Approval revalidates the envelope; the UI warns and offers Pause for review | Approval sends an equal or smaller quantity or asks for re-review |
| Supply delay or shortfall changes depot stock | Depot risk and reason update from snapshot differences                      | Depot window appears, then shrinks or clears when supply arrives  |
| Anomaly detector failure                      | Risk and approval continue without flags; detector status UNAVAILABLE       | Flags resume after a successful evaluation                        |

### Timeout and retry starting policy

For ordinary simulator calls, start with a one second connect timeout and a two second response timeout. Retry reads at most twice with jittered exponential delays, for example about 200 and 500 ms, within a five second total deadline. Stream timeouts are separate. Tune these values after latency tests; they are proposed application settings.

Use a circuit breaker with a small documented rolling window and a half-open recovery probe. Do not include dependency health in process liveness in a way that restarts the backend repeatedly during an external outage. Readiness and data quality should explain degraded service. A healthy /v1/health response does not prove /v1/stations works because health bypasses faults. \[G pages 4 to 5\]

Do not retry 409 or 422 blindly. Re-fetch and recompute on operational conflicts. For POST transport failures and selected 5xx responses, retry only the same stored body and key, reconcile first and stay within a budget. Unknown outcomes remain visible for operator review.

## 20 Test domain crises separately from API faults

Domain events use simulation ticks; API faults use wall time seconds. Use /admin controls only to arrange and inspect tests. The application must continue reading /v1 resources during fault tests. \[G pages 10 to 13\]

| **Scenario**     | **Input to local test driver**                    | **Expected evidence**                                                                                   |
|------------------|---------------------------------------------------|---------------------------------------------------------------------------------------------------------|
| Demand spike     | demand_spike on region-dhaka, multiplier 1.8      | Anomaly flag within the measured number of ticks, risk changes and proposal adapts                      |
| Route disruption | route_disruption on route-gazipur-mirpur          | Use feasible Patiya to Mirpur route or explain no suitable supply                                       |
| Station outage   | station_outage on station-tongi                   | No new proposal to outage station; operational impact shown                                             |
| Depot constraint | depot_constraint on depot-gazipur                 | Constraint context visible; numeric limits read from REST                                               |
| Supply delay     | shipment_delay, delay_ticks 4                     | New planned arrival, depot shortfall window and downstream station effect; no automatic undo assumption |
| Supply shortfall | supply_shortfall, factor 0.5                      | Reduced incoming quantity, depot window and revised plan                                                |
| API outage       | unavailable for 30 seconds                        | Stale display, blocked writes, recovery refresh                                                         |
| Slow API         | latency with delay_ms 2500                        | Timeout budget and cached response; no thread pileup                                                    |
| Transient errors | error_rate with rate 0.25                         | Bounded retries; failure metrics increase                                                               |
| Stale data       | stale_data for 30 seconds                         | Header respected although response is 200                                                               |
| Stream loss      | stream_disconnect for 30 seconds                  | Reconnect handling; REST fallback remains usable                                                        |
| Clock mode       | Approve once PAUSED and once RUNNING at low speed | Revalidation sends an equal or smaller quantity or asks for re-review                                   |

### Reset and cleanup

Reset only a dedicated test run. A reset destroys simulator history and reloads the baked scenario. Pause, reset, read the instance, start a new application run, collect warm-up observations and schedule injections relative to the current tick. Clear faults at the end and retain evidence before resetting. \[G page 11\]

The guide says shipment_delay and supply_shortfall are one-shot changes and do not auto-restore on resolution. Empty entity filters affect all entities of that type. Always specify intended ids in reproducible tests. \[G page 12\]

The simulator does not document a malformed-payload fault or a separate prediction service. Use a test-only adapter fixture for malformed JSON and a forecast failure switch in a nonproduction profile. Do not claim those cases were injected by the official fault API.

## 21 Observe both decisions and software health

Use Spring Actuator with Micrometer and a Prometheus registry. Expose only required management endpoints to the local monitoring network. Prometheus scrapes the backend and Grafana shows a small set of useful panels. Official Spring documentation describes management endpoints and metric integration. \[S4; S5\]

| **Category** | **Suggested measurement**                                                    | **Question it answers**                                |
|--------------|------------------------------------------------------------------------------|--------------------------------------------------------|
| HTTP         | Request count, error ratio and p50 p95 p99 duration                          | Is the application becoming slow or failing?           |
| Runtime      | JVM heap, process CPU, threads and DB pool use                               | Which resource is approaching its limit?               |
| Simulator    | Call duration, outcomes, consecutive failures and circuit state              | Is the dependency reachable on real data paths?        |
| Data         | Last valid refresh age, rejected payloads and stale response count           | Can this view support an allocation?                   |
| Intelligence | Forecast absolute error, risk count, anomaly flags and fallback count        | Is the policy useful and which method is active?       |
| Commands     | Approval count, unknown count, replays and reconciliation duration           | Could a mutation be lost or duplicated?                |
| Domain       | Service level, unmet liters, shipment failures and rejected POSTs            | What operational outcome did decisions produce?        |
| Mode         | Simulation mode gauge, measured tick rate and approval revalidation outcomes | Does the approval path behave correctly in both modes? |

### Metrics first and the status panel

Observability carries 10 percent of the brief weight and every later feature needs a measurement, so the Prometheus endpoint is a day one task. Add each metric in the same change that adds its feature. Keep a short fixed list instead of a large catalog. The names below are proposals.

| **Metric**                                               | **Source** | **Question it answers**                            |
|----------------------------------------------------------|------------|----------------------------------------------------|
| http_server_requests with percentiles                    | Actuator   | Request rate, error ratio and latency              |
| fuel_simulator_call_seconds, tag outcome                 | Adapter    | Is the dependency healthy on real data paths?      |
| fuel_data_age_seconds                                    | Ingestion  | How old is the view the operator sees?             |
| fuel_stale_responses_total, fuel_rejected_payloads_total | Ingestion  | Which data quality problems occurred?              |
| fuel_forecast_fallback_total                             | Risk       | Is the fallback method active?                     |
| fuel_anomaly_flags_total, tag rule                       | Detector   | How active is detection?                           |
| fuel_approval_total, tag outcome                         | Decision   | Did revalidation approve, reduce, change or block? |
| fuel_commands_unknown                                    | Command    | Is any write outcome uncertain?                    |
| fuel_sim_mode, fuel_sim_ticks_per_second                 | Ingestion  | Which mode, and how fast is the clock?             |

The status panel reads from the same registry. It shows backend, database, simulator, forecast engine mode, decision engine, simulation mode, refresh age, recent p95 latency and recent error rate. For one backend instance a Micrometer timer with published percentiles gives p95 directly; label it recent because the window decays. Compute the error rate as server errors divided by requests over the same recent window. Grafana computes the same figures from histogram buckets; if the two differ, explain the window difference instead of hiding one.

Proposed shape of the status response. The null values are placeholders, not measurements.

```
{
  "backend": "UP", "database": "UP", "simulator": "UP",
  "simulationMode": "PAUSED", "engineMode": "PRIMARY",
  "refreshAgeSeconds": null, "p95LatencyMs": null,
  "errorRatePercent": null, "windowSeconds": null
}
```

### Useful logs

Emit structured records with timestamp, level, correlationId, runId, snapshotId, tick, commandId, event type, result and elapsed time. Log a decision's inputs and policy version through references rather than dumping full snapshots into every request log. Never log credentials or a full authorization header.

Proposed alerts include refresh age above ten seconds, UNKNOWN command older than ten seconds, sustained integration failures, forecast fallback active and rejected simulator payloads. A metric becoming nonzero is evidence of detection; demonstrate the operator-visible response as well.

Keep identifiers such as station ids and correlation ids out of metric labels when they could create unbounded series. Use a small fixed fuel label where useful and keep unique ids in logs. The four station world is small, but the habit should scale.

### One incident recording

Capture the injection time, failing data calls, warning shown in the UI, write guard, metric change, recovery refresh and recovered state. Measure recovery time in wall seconds. Keep a screenshot or short recording plus the matching log and run id.

## 22 Package a reproducible local deployment

![Local deployment diagram (chapter 22)](images/image4.png)

Use multi-stage Dockerfiles. Build the backend with Maven Wrapper, then run the jar on a small Java runtime image. Build React with npm ci and serve static output through Nginx, proxying /api to the backend. Pin dependency and image versions; record resolved image digests for the demo.

Compose services are simulator-api, backend, frontend, postgres, prometheus and grafana. PostgreSQL uses a named volume. Prometheus is part of the P0 stack; Grafana panels are P1 and follow once the status panel works. Keep backend and database ports internal where possible. Publish the frontend, simulator admin and monitoring only on 127.0.0.1 for the local demo.

Compose starts containers in order but does not automatically wait for readiness. Configure PostgreSQL health checks and service_healthy where needed. The backend must still retry connections and recover later. Simulator failure should degrade reads and block approvals instead of preventing all application startup. \[S6\]

A fresh clone must work with a documented .env, docker compose config, docker compose up --build -d, readiness checks and a smoke test. Name every required port and explain how to stop containers without deleting data. Preserve test artifacts before any reset or volume removal.

## 23 Set up delivery automation and basic security

### CI stages

- On a push or pull request, check out code, set the pinned Java and Node versions, and restore dependency caches. Use maintained official setup actions, pin chosen versions or reviewed commit SHAs, and record them.

- Run backend ./mvnw -B verify with domain and integration tests. Run frontend npm ci, type checking, lint, selected component tests and production build. The exact npm script names must exist in package.json.

- Build both application images. In a separate integration job, start the official simulator, database and backend, wait for readiness and run smoke checks. Upload test reports and scenario evidence even on failure.

- Tag a verified release using the commit SHA. If pushing images to a registry, use scoped repository secrets and avoid printing tokens. Deploy that exact image set and verify the health and core workflow. A green build alone does not demonstrate deployment.

GitHub's official Maven workflow documentation provides the build and test pattern. Your workflow should add frontend checks, image builds and the actual simulator smoke test around it. \[S7\]

### Configuration and access

Use SIMULATOR_BASE_URL, DB_URL, DB_USER, DB_PASSWORD, simulator tick settings, request timeouts and freshness thresholds as configuration. Commit .env.example with placeholders and safe defaults. Ignore .env and credential files. No secrets belong in CLAUDE.md, prompts or evidence recordings.

For a local demo, use Spring Security with one configured operator account and a read-only viewer if useful. Let Nginx proxy the browser API under one origin. Use a server session with CSRF protection for approvals, or another documented mechanism you understand; hiding an approve button is not authorization. Keep simulator admin controls outside the public app API.

### Rollback and recovery

Retain the last verified application image tags. Roll back application images if a new version fails smoke checks, then refresh and reconcile commands before approvals resume. Use additive database migrations for the short project so rolling back code does not require restoring destructive schema changes.

A deployable local stack satisfies the proposed reproducibility target. Add remote hosting only if the assignment requires it and after the local run is stable. Confirm required access with the evaluator before exposing anything publicly.

## 24 Verify correctness and measure load

### Tests that protect real behavior

| **Layer**            | **Important cases**                                                                                                       |
|----------------------|---------------------------------------------------------------------------------------------------------------------------|
| Pure domain tests    | Tick conversion, zero demand, cold start, stockout timing, shared dispatch limit, inbound headroom and unavailable routes |
| Adapter tests        | Stale header, malformed body, both error envelopes, 422 arrays, nullable fields, timeout and replay success codes         |
| Command tests        | Double approval, response lost after acceptance, worker crash before DB update, restart and same-key mismatch             |
| Official integration | Create, step, depart, arrive, PENDING cancel, crisis event, API fault and recovery                                        |
| UI and browser       | Risk inspect, approve once, partial result, stale guard, unknown outcome and recovered state                              |

### A meaningful load workload

Load the actual GET /api/dashboard path using k6 or an equivalent tool. Warm up for 30 seconds, then run 10, 25 and 50 concurrent virtual users for two minutes each. Keep the simulator mode, tick size and machine resources fixed and report them. These loads are proposed test points, not assignment requirements.

Record average, p50, p95 and p99 latency, requests per second, HTTP and business error rates, concurrency, CPU, heap and DB connections. Start with an explicit target such as p95 below 500 ms and error rate below 1 percent for healthy cached dashboard reads at 25 users. Report the result even when it misses the target. k6 thresholds can make agreed targets fail CI. \[S8\]

Measure simulator request rate as users increase. A bounded backend refresh loop should decouple dashboard user count from simulator request volume. If it does not, fix the architecture before adding replicas.

Repeat the 25 user stage once with the simulator PAUSED and once RUNNING at the speed you plan to demo. RUNNING adds tick driven refreshes, so compare simulator request rate and p95 between the two runs and report both. Compare the status panel p95 with the k6 p95 during the run; they differ in window and client overhead, so report both without forcing agreement.

Test POST /api/recommendations separately because it exercises decision work without mutation. Do not flood the allocation endpoint in a generic load test; it changes world inventory and invalidates comparison. Run a separate controlled duplicate approval test and count simulator allocations by key.

### Record performance honestly

Include commit SHA, versions, run time, workload file, raw summary, machine specification, simulator state and an explanation of the bottleneck. A successful test proves behavior only under that workload. Never fill the final report with the brief's example latency or invented benchmark results.

## 25 Use agentic engineering as an accountable workflow

The lab screenshots cover session workflows, context management, persistent context, skills, subagents and MCP, alongside Docker, Compose, GitHub Actions and Claude Code fundamentals. They show module names, not the detailed lab exercises. This chapter proposes project evidence for those topics. \[L1; L2\]

### The issue cycle

- Understand: read the relevant contract and manually solve a small example. Write the input, expected output, failure case and invariant in your own words.

- Specify: create one small issue with bounded files and acceptance checks. Ask the agent for its plan and assumptions before it edits code.

- Implement: allow a focused patch after you understand the plan. Keep one main writer for each module. Ask for an explanation of unfamiliar constructs.

- Verify: run the required checks yourself or inspect their actual output. Review the diff and challenge one failure case. Agent confidence is not evidence.

- Review: use a separately scoped reviewer for correctness or resilience if helpful. Resolve actionable findings with tests and documented reasoning.

- Retain: commit the change, update the decision and activity logs, and prepare an evidence-based daily update. Record what you learned and what remains uncertain.

### One issue example

Issue FUEL 007: Reject flagged stale simulator responses. Input: a valid /v1/stations body with X-Simulator-Stale: true. Expected: retain the previous valid snapshot for display, mark decisions ineligible, show a stale reason and increase a counter. Recovery: accept a complete unflagged refresh. Allowed scope: simulator adapter, snapshot quality service, risk guard and related tests.

Before accepting the patch, explain why a successful HTTP 200 can still be unfit for decisions. Show that the approval endpoint checks quality on the server and does not depend only on a disabled frontend button.

### Separate engineering agents from product intelligence

A coding assistant helping you implement and test is part of the development workflow. The forecasting and allocation policy inside the application is the product intelligence. You can demonstrate both without turning the product into a chatbot or giving a model permission to dispatch fuel.

## 26 Manage persistent context and session handoffs

Use a short project CLAUDE.md for stable instructions, build commands and key invariants. Put changeable progress in docs/current-state and architecture decisions in docs/adr. Claude Code documents project memory and instructions separately from auto memory. Keep tool-specific behavior aligned with the installed version. \[S9\]

### Proposed CLAUDE md contents

```
Project: fuel operations demo using the official simulator.
Backend: Spring Boot. Frontend: React TypeScript.
Read docs/contract-notes and relevant ADRs before editing.
REST is truth. SSE only triggers refreshes.
Never modify the official simulator image or source.
Never allocate from stale or invalid data.
Support PAUSED and RUNNING modes; approval revalidates a fresh read and never exceeds the reviewed quantity.
Persist immutable command body and key before POST.
Retries reuse the same body and key.
Keep operator approval server enforced.
Explain assumptions and one failure case before coding.
Use focused diffs. Run checks required by the issue.
Update docs/current-state after a completed issue.
Do not claim a check passed without its actual output.
```

### Session handoff record

Write: objective; branch and commit; completed issues; files changed; commands and observed results; unresolved risks; simulator run id, tick and mode; next small task. Include links to contract notes and evidence. A new session should read this instead of receiving the entire prior conversation.

Use the installed tool's help to check available context and session commands. Where supported, /context inspects context use, /compact summarizes a long session, and /clear begins a fresh conversation context. Verify current command availability before relying on them. Save the handoff before compaction or clearing; conversation memory is not your system of record.

### Context discipline exercise

Finish the stale-data issue, save the handoff, start a fresh session and ask the agent to describe the current write guard using only repository context. Compare its answer to the code and correct missing or obsolete instructions. Keep the before and after as lab evidence.

CLAUDE.md and prompts guide behavior but are not enforcement. Use tests, database uniqueness, runtime access controls and tool restrictions for guarantees.

## 27 Turn repeatable work into skills and commands

A skill is a reusable procedure; a project memory file holds durable facts. In Claude Code, a project skill can live at .claude/skills/fuel-review/SKILL.md and be invoked as /fuel-review. The official documentation explains frontmatter and manual invocation. Treat the following as a template to review before installing. \[S10\]

### Example review skill template

```
---
name: fuel-review
description: Review fuel allocation changes against project invariants
disable-model-invocation: true
---
Read CLAUDE.md, the issue and relevant contract notes.
Inspect the diff and linked test results.
Check shared dispatch limits across all fuel types.
Check pending and in transit reservations.
Check stale guards and server side approval checks.
Check that revalidation can reduce a quantity but never raise it.
Check immutable body and key reuse on unknown writes.
Return findings with file, line, impact and evidence.
If no defect is found, list reviewed paths and test gaps.
Do not edit files, submit allocations or publish anything.
```

Invoke the skill after a decision or command patch. Its instruction to avoid writes is a workflow request; configure actual tool permissions or a read-only reviewer agent when enforcement matters.

### Useful repeatable workflows

| **Workflow**          | **Repeatable input and output**                                         |
|-----------------------|-------------------------------------------------------------------------|
| Review allocation     | Issue, diff and evidence produce actionable correctness findings        |
| Prepare daily update  | Completed commits and results produce a draft with links and unknowns   |
| Verify demo readiness | Runbook and release SHA produce a checklist of observed passes and gaps |

Use a normal prompt first. Promote it to a skill only after repeating the workflow and discovering what instructions are actually useful. Include a failing example in the procedure when it prevents a recurring mistake. Custom workflow names are your own; do not imply that /fuel-review ships with Claude Code.

### Skill evaluation exercise

Try the review procedure on two patches: one correct allocator and one intentionally violating the shared dispatch limit in a disposable branch. Record whether it detects the violation and whether it invents findings on the correct patch. Fix the procedure and repeat before trusting it.

## 28 Use subagents and MCP with clear boundaries

### A focused reviewer subagent

Use a separate reviewer when the implementation is large enough to benefit from an independent context. Give it the issue, exact files, invariants and expected output. One agent implements; a reviewer reads and reports. Avoid several agents writing to the same files. Save the reviewed definition at .claude/agents/fuel-reviewer.md. The official format supports a tool allowlist. \[S11\]

```
---
name: fuel-reviewer
description: Review fuel correctness and failure handling
tools: Read, Grep, Glob
---
Read the issue and relevant contract notes.
Inspect the named files and recorded test evidence.
Report reproducible defects with file and line references.
Check stale data, inventory, capacity and idempotency.
Separate verified findings from untested suspicions.
Do not modify files or submit simulator actions.
```

The allowlist intentionally excludes shell execution and write tools. A tool called read-only can still expose private data, so restrict the working scope to this project. If you add shell access to run checks, understand that shell capability can also write; use an actual permission boundary.

### MCP connects tools to an agent

MCP is a protocol for exposing tool capabilities. The simulator is an HTTP REST and SSE service; it is not automatically an MCP server. A wrapper would be needed. The core application should keep using its typed HTTP adapter and remain usable without MCP. \[S12\]

For lab evidence, optionally expose narrow read-only tools such as get_system_status, list_risks and explain_recommendation over your backend. Validate typed inputs and include run id, snapshot id and quality in every result. Omit allocation, cancellation, reset and fault-injection tools from the agent's tool list.

After implementing a local stdio MCP server, the documented connection shape is claude mcp add --transport stdio fuel-readonly -- \<server-command\>. Replace the placeholder with your actual reviewed command; it is not ready to run as printed. Use /mcp or claude mcp list to check the connection. \[S12\]

Keep this exercise behind the completed P0 workflow. If time runs short, demonstrate a read-only existing trusted MCP integration from your lab environment and clearly state that a custom simulator wrapper was deferred.

## 29 Measure agent assistance and show your understanding

### Prompt for one implementation slice

```
Task: implement only the persisted allocation command flow.
Read CLAUDE.md, contract notes and ADR 003 first.
Before editing, explain the failure window after POST.
Inputs: approved stored recommendation and valid snapshot.
Invariants: one command per proposal line; stable key;
immutable body; no write when data is stale.
Test: simulator accepts but the HTTP response is lost.
Allowed scope: command module, migration and tests.
Report changed files, checks run, actual outputs and gaps.
Stop after this issue. Do not expand product scope.
```

### Prompt for a review

```
Review this diff against the issue and invariants.
Do not edit. Focus on duplicate writes, stale snapshots,
shared dispatch capacity and reset isolation.
For each finding give file, line, failure scenario and impact.
Distinguish observed evidence from an inference.
If no actionable defect exists, report remaining test gaps.
```

### Agent activity record

For each material issue, store date, goal, context supplied, agent role, proposed plan, accepted or rejected changes, test commands and observed outcomes, your manual explanation, defects caught and next action. Keep short factual notes rather than long chat transcripts.

| **Mini evaluation**          | **What a pass looks like**                                    |
|------------------------------|---------------------------------------------------------------|
| Duplicate approval           | One command and one simulator allocation                      |
| HTTP 200 with stale header   | Agent identifies why approval must remain blocked             |
| Shared fuel dispatch limit   | Reviewer detects combined quantity overflow                   |
| Reset with repeated tick ids | Old run data and commands remain isolated                     |
| Unknown model answer         | Agent reports missing evidence rather than inventing a result |

Track correctness and evidence quality, not number of prompts. A useful record might say: the agent suggested subtracting pending shipments from a depot whose returned inventory was already reserved; I rejected that change after a contract experiment. That demonstrates judgment, provided the experiment was actually performed.

An optional runtime operations assistant can summarize validated risks with cited snapshot ids and handle unavailable tools. Evaluate its factuality, stale-data recognition and refusal to dispatch. Do not add it until the deterministic workflow and evidence are complete.

## 30 Day 1 Establish the contract and a read only slice

Thursday November 19 2026. Goal: run the official simulator, show validated fuel data in React and have a live Prometheus endpoint by the end of the day. Keep the simulator paused while learning its semantics. Budget: eight focused hours.

| **Time budget** | **Work in production order**                                                                                                                                                                                                                                                                                     |
|-----------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 1 hour          | Read brief and contract notes. Write acceptance stories, scope and the invariants, including the two operating modes. Create repository and small issue board.                                                                                                                                                   |
| 0.5 hour        | Draw one system diagram in Excalidraw. Explain truth ownership, two clocks and why one backend is sufficient. Draw the deployment diagram on day four.                                                                                                                                                           |
| 2 hours         | Run the official simulator with explicit tick settings. Capture health and instance. Test create, replay, mismatch, cancel and one stepped shipment on a disposable run. Record whether depot inventory drops at POST. Run it for 30 seconds at your chosen speed and record the measured ticks per wall second. |
| 2 hours         | Scaffold Spring Boot, React TypeScript and PostgreSQL. Implement typed GET adapter, required field validation and a snapshot endpoint.                                                                                                                                                                           |
| 1 hour          | Render station and depot inventory with fuel filters, tick, mode badge and refresh age. Add loading and failed-read UI.                                                                                                                                                                                          |
| 1.5 hours       | Add Compose skeleton and CI build skeleton. Enable Actuator and the Prometheus endpoint with default HTTP metrics and one custom counter for simulator calls. Run smoke checks, capture evidence, commit and prepare the daily update.                                                                           |

### Learn before asking an agent to code

Explain HTTP status versus business validity, why SSE cannot be the source of truth, and why a Java controller should not parse simulator JSON and allocate fuel in the same method. Manually convert a two tick route into simulated minutes using the returned tick size. Compute simulated time per wall second from speed and tick size and explain why a tick based staleness rule needs a mode.

### Done means observable behavior

Official image runs unchanged. /v1/instance reports the intended tick_minutes. Contract notebook contains actual responses, the measured tick rate, the depot inventory behavior at POST and the unresolved ambiguity list.

React shows all four stations and three fuel categories using Spring Boot data. No hardcoded operational inventory remains.

A failed read produces a clear error or last-known-data state. curl on /actuator/prometheus returns metrics from the running backend. A fresh clone has a documented startup path, even if only the simulator and database are containerized today.

### Agentic evidence and update

Create a short CLAUDE.md, one issue specification and one session handoff, keeping this to about fifteen minutes. Record one agent suggestion you verified manually. Daily update links the read-only demo and contract notes. If the image cannot run, report the exact pull or startup error early; use fixtures to continue UI work while labeling official integration incomplete.

Cut rule: if behind schedule, defer styling and maps. Do not defer simulator access, the contract experiments or the Prometheus endpoint; every later feature depends on them.

## 31 Day 2 Implement station and depot risk, detection and allocation logic

Friday November 20 2026. Goal: turn one valid snapshot into explainable station and depot risk, anomaly flags and a feasible proposal with alternatives, without changing simulator state. Budget: eight focused hours.

| **Time budget** | **Work in production order**                                                                                                                                                                        |
|-----------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 0.5 hour        | Review day one findings. Define immutable domain records, data quality, SimulationMode and freshness configuration.                                                                                 |
| 2 hours         | Ingest and deduplicate bounded demand history. Implement naive forecast, EWMA and cold-start metadata. Add unit and time conversion checks.                                                         |
| 1.5 hours       | Implement station projection, inbound timing and risk labels. Solve the worked shortage example by hand before coding.                                                                              |
| 1.5 hours       | Implement depot projection from supply arrivals, supply change detection from snapshot differences and depot labels. Solve the depot example by hand before coding.                                 |
| 1.5 hours       | Implement deterministic ranking, route selection, quantity constraints, working reservations across fuels and alternatives with reason codes. Test the shared dispatch limit and the no route case. |
| 1 hour          | Implement the rule based anomaly detector with profile ratio, regional aggregation and cause label. Unit test it with a quiet series and a spiked series.                                           |

### Learn before using generated code

Explain why demand_liters differs from served_liters, why observed demand should not be multiplied twice, why a route delay can defeat a large shipment, and how two individually valid proposals can violate a shared dispatch limit. Also explain why a morning demand rise is not an anomaly but a ratio of 1.8 to the profile is, why a depot shortfall window can exist while every station is still full, and why a delayed arrival changes depot risk before station risk.

### Done means testable intelligence

Risk output contains data source, method, sample count, horizon, projected stockout and uncertainty metadata. Cold start does not pretend to have measured confidence.

Depot risk shows a shortfall window, the delay or reduction that caused it and the stations affected. The anomaly detector flags a synthetic spike and stays silent on a quiet series.

Every proposal respects station status, route endpoints, fuel inventory, shared dispatch allowance, per shipment limit and conservative station headroom, and lists up to two alternatives or NO_ALTERNATIVE_ROUTE.

The recommendation output states why the quantity was chosen and which needs remain unmet; the UI shows it on day three. Generating a recommendation sends no allocation POST.

### Agentic evidence and update

Use an agent for one narrow pure function after writing its input and expected output yourself. Ask a read-only reviewer to challenge dispatch and inbound reservations. Save its findings and the actual test outputs, then explain the final algorithm without reading its source.

Update evidence: one manually checked station calculation, one manually checked depot calculation, a real simulator snapshot, passing invariant checks and a printed recommendation with alternatives. Report algorithm quality as unmeasured until the controlled comparison is performed.

Cut rule: keep one EWMA policy and a transparent naive baseline. Defer learned ML models, tunable optimization solvers and generative explanations. If behind, keep only detector rule R1 and move persistence to the first hour of day three.

## 32 Day 3 Complete the operator decision loop

Saturday November 21 2026. Goal: persist, show, review, approve in either mode, submit once and follow the official shipment lifecycle. Budget: eight focused hours.

| **Time budget** | **Work in production order**                                                                                                                                                                                                                                                         |
|-----------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 1 hour          | Persist snapshots, recommendations and anomaly flags with migrations. Add the read APIs.                                                                                                                                                                                             |
| 1 hour          | Build the React risk table for stations and depots, anomaly badges and the recommendation panel with alternatives. Generating a recommendation sends no allocation POST.                                                                                                             |
| 2 hours         | Draw the allocation sequence and command states. Implement the approval transaction, stable command keys, request persistence, unique line constraint, single command worker and UNKNOWN reconciliation. Test the accepted but response lost case with a controlled adapter fixture. |
| 1.5 hours       | Implement the mode aware freshness policy and revalidation envelope on the server. Test same tick approval, approval after stepping, and approval after a route disruption in RUNNING mode.                                                                                          |
| 1.5 hours       | Complete the review dialog with reviewed quantity, command status, shipment ledger, partial completion display and server enforced operator authorization.                                                                                                                           |
| 1 hour          | Submit a valid official allocation, step the paused world, follow departure and arrival, run the double click and restart checks, save evidence and report the full vertical slice.                                                                                                  |

### Learn before merging the write flow

Explain why sending a POST and then saving its key is unsafe. Explain why a new browser request id is not a suitable key for a repeated approval. Describe what happens if three proposal lines are accepted and the fourth is rejected. Explain why a quantity may fall but never rise between review and approval.

### Done means one traceable outcome

One proposal line produces one durable app command. Repeated approval returns that command instead of creating another shipment.

A lost response or worker restart reconciles using the original key. The simulator ledger contains one allocation for that key.

The UI separately shows command submission state and shipment delivery state. It does not label PENDING as arrived or a timeout as rejected.

Official simulator values change as expected after stepping. The audit connects actor, proposal, snapshot, command and simulator allocation id.

Approval in RUNNING mode either sends an equal or smaller quantity or sends nothing and asks for re-review; the audit shows reviewed and final quantity.

### Agentic evidence and update

Create the reusable review procedure only after testing it on the patch of the day. Run one scoped review focusing on crash windows, double submission and envelope revalidation. Record which finding changed your implementation and which evidence justified the change.

Update evidence: a short recording from risk to approval to arrival, a ledger screenshot, the duplicate approval test and the mode aware approval tests. If reconciliation is still incomplete, name it as a blocker to release rather than hiding it in the backlog.

Cut rule: one operator account and a simple review dialog are sufficient. Do not cut durable keys, write validation, the unknown outcome state or server side revalidation. If time runs out, defer the partial completion display and alternative pinning; displaying alternatives stays.

## 33 Day 4 Demonstrate resilience and observability

Sunday November 22 2026. Goal: retain a useful operator view during failures, guard writes, recover visibly and show health with measured p95 latency and error rate. Budget: eight focused hours.

| **Time budget** | **Work in production order**                                                                                                                                                                                                                 |
|-----------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 1.5 hours       | Finish refresh quality, explicit stale header handling, deadlines, bounded retries and circuit breaker behavior. Enforce the server side stale approval guard.                                                                               |
| 1 hour          | Add the simulator SSE reader, coalesced refresh triggers with a minimum interval, reconnect and a REST polling fallback. Verify missed notifications do not corrupt the ledger and that RUNNING mode keeps simulator request volume bounded. |
| 1 hour          | Inject unavailable, stale and latency faults. Exercise a forecast failure fixture and a backend restart. Save the exact inputs and results.                                                                                                  |
| 1 hour          | Inject demand spike, route disruption and shipment delay at known ticks. Show the anomaly flag, the depot window and alternative route availability, and record the remaining unmet need.                                                    |
| 1.5 hours       | Add structured logs, the intelligence and approval metrics from chapter 21, Prometheus scrape configuration and the essential Grafana panels.                                                                                                |
| 0.5 hour        | Build the system status panel with backend, database, simulator, engine mode, simulation mode, refresh age, recent p95 latency and error rate.                                                                                               |
| 1.5 hours       | Complete Compose health checks and local port restrictions, run a clean Compose startup, record failure and recovery, review the evidence and send the daily update.                                                                         |

### Learn before adding resilience tools

Explain the difference between a timeout, retry and circuit breaker. Explain why a healthy simulator health endpoint does not prove fresh station data. Explain why REST polling is still required when SSE is connected.

### Done means a visible failure boundary

An official 30 second unavailable fault produces cached read mode and blocked approvals while /v1/health remains healthy.

An HTTP 200 stale response cannot become an eligible decision snapshot. Missing required fields never become zero inventory.

After a cleared or expired fault, a complete valid refresh restores current state and enables approval. An unresolved UNKNOWN command still requires reconciliation.

A reviewer can connect the UI warning to logs and metrics, then see recovery. The status panel shows recent p95 latency and error rate rising during the latency and error rate faults and recovering afterward. Compose launches the complete stack from documented configuration.

### Agentic evidence and update

Ask a reviewer to inspect one incident using only logs, metrics and a run id. Compare the diagnosis with your injection script. Save one context handoff and resume it in a fresh session to demonstrate persistent context discipline.

Cut rule: keep a small useful Grafana dashboard and structured console logs. Defer Loki, distributed tracing and additional infrastructure. Grafana is P1: the Prometheus endpoint plus the status panel already satisfy the core metrics requirement. If SSE takes too long, release reliable polling first and explicitly report SSE as pending until verified.

## 34 Day 5 Validate and rehearse the release

Monday November 23 2026. Goal: produce a frozen, demonstrable release with measured evidence and a rehearsed demo. Budget: eight focused hours. November 24 to 26 remains a repair and rehearsal buffer.

| **Time budget** | **Work in production order**                                                                                                                                                                                                                      |
|-----------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 1.5 hours       | Run the baseline and candidate under the same deterministic normal, spike, route and combined crisis scenarios with a script, PAUSED and stepped. Run the detector quiet and spike replays. Save raw domain metrics and interpret the difference. |
| 1 hour          | Run the dashboard load stages in PAUSED mode and the 25 user stage again in RUNNING mode. Capture latency and resource results and identify the limiting component.                                                                               |
| 1 hour          | Complete CI, clean clone startup and smoke checks. Verify packaged images, migration behavior and command reconciliation.                                                                                                                         |
| 1 hour          | Finish README, ADRs, limitations, evidence index, runbook and daily reports. Audit claims against actual results.                                                                                                                                 |
| 3.5 hours       | Rehearse the eight minute demo three times, including the RUNNING mode approval segment. Fix critical defects only, tag the verified commit and prepare the final progress report.                                                                |

### Done means another engineer can verify it

One command launches the documented local stack. The official simulator remains unchanged, and the core workflow runs from a fresh clone.

Baseline comparison, detection replay, load reports for both modes, fault recovery recording and CI output are indexed by release SHA and run id.

You can explain the algorithm, idempotency failure window, two clocks, stale guard, reviewed envelope, tradeoffs and one agent suggestion you corrected.

The demo script has exact reset, warm-up, step, injection and cleanup actions. Known limitations are visible and accurate.

### Use the deadline buffer deliberately

Tuesday November 24: reproduce from a clean environment and repair only verified defects. Up to four hours may finish the first items on the cut list below. Wednesday November 25: run two full rehearsals, one of them a surprise drill in which another person injects an unannounced event or fault through the admin console while you narrate detection and recovery; then check evidence links and freeze dependencies. Thursday November 26: verify the agreed handover time and demonstrate the frozen release. The prompt provides a date but no cutoff hour; do not assume an end-of-day submission time.

The agent review evaluation against a seeded defect is optional lab work and moves to the buffer.

### Scope if the schedule slips

Protect official integration, usable dashboard, deterministic intelligence, mode aware reviewed writes, depot and station risk, the metrics endpoint with the status panel, cached failure mode, repeatable startup, measured evidence and demo rehearsal. Cut in this order: LLM features, custom MCP, Grafana polish, alternative pinning, map polish, agent lab exercises, detector rule R2. If only five to six focused hours per day are available, keep this reduced scope from day one.

## 35 Step by step order, understanding gates and update points

This chapter is the order of work. Do the steps in sequence and do not start a step until you can pass the understanding gate of the previous one. Every step ends with evidence you can show in a Poridhi update. Step numbers match the daily schedule in chapters 30 to 34.

### Day 1 Thursday November 19

| **Step** | **Do**                                                                                                          | **Understanding gate: explain aloud without notes**                                         | **Evidence for the update**                          |
|----------|-----------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------|------------------------------------------------------|
| 1        | Write acceptance stories, invariants and the two modes. Create the repository and issue board.                  | Why REST is the truth and SSE is a hint. What PAUSED and RUNNING mean.                      | Repository link and invariants file                  |
| 2        | Run the simulator; capture health and instance; test create, replay, mismatch, cancel and one stepped shipment. | Why HTTP 201 is not delivery. What idempotency does. Whether depot inventory drops at POST. | Contract notes with real responses                   |
| 3        | Measure ticks per wall second while RUNNING.                                                                    | Simulated time per wall second, and why a one tick staleness rule fails while running.      | Measured rate in the notes                           |
| 4        | Build the Spring adapter with validation and a snapshot endpoint; show inventory in React.                      | Why a controller does not parse simulator JSON and allocate fuel in one method.             | Screenshot of real data with tick and mode           |
| 5        | Add Compose and CI skeletons and the Prometheus endpoint.                                                       | What a metric is and why the endpoint exists before the features.                           | curl output of /actuator/prometheus and first CI run |

### Day 2 Friday November 20

| **Step** | **Do**                                            | **Understanding gate: explain aloud without notes**                                      | **Evidence for the update**                                  |
|----------|---------------------------------------------------|------------------------------------------------------------------------------------------|--------------------------------------------------------------|
| 6        | Domain records, SimulationMode and configuration. | Convert transit_ticks to hours using the returned tick size.                             | Unit test output                                             |
| 7        | Demand ingestion, naive forecast and EWMA.        | Demand versus served liters. Why the multiplier is not applied twice.                    | Forecast examples with errors                                |
| 8        | Station projection and risk labels.               | The worked example solved by hand. Why arrival timing matters.                           | Manual risk calculation file                                 |
| 9        | Depot projection and supply change detection.     | Why depot risk comes before station risk. Planned depot arrival versus station delivery. | Depot example solved by hand and its test                    |
| 10       | Allocation with reservations and alternatives.    | The shared dispatch limit. Why alternatives inform the operator.                         | Test catching the 4,700 liter mistake; sample recommendation |
| 11       | Rule based anomaly detector.                      | Why the ratio to the profile is used. Trade between false flags and detection delay.     | Quiet and spike series test results                          |

### Day 3 Saturday November 21

| **Step** | **Do**                                                          | **Understanding gate: explain aloud without notes**                                 | **Evidence for the update**                |
|----------|-----------------------------------------------------------------|-------------------------------------------------------------------------------------|--------------------------------------------|
| 12       | Persistence, risk table and recommendation panel.               | Why a snapshot is evidence and not truth.                                           | Screenshot and migration list              |
| 13       | Approval transaction, command worker and reconciliation.        | The crash window after POST. Why the key is stored before POST.                     | Duplicate approval test output             |
| 14       | Mode aware revalidation of the reviewed envelope.               | Why quantity may fall but never rise. Why blocking on tick lag fails while running. | Test output in both modes                  |
| 15       | Review dialog, ledger, authorization; official submit and step. | PENDING is not arrived. An unknown outcome is not a rejection.                      | Recording from risk to approval to arrival |

### Day 4 Sunday November 22

| **Step** | **Do**                                                      | **Understanding gate: explain aloud without notes**                                    | **Evidence for the update**                                    |
|----------|-------------------------------------------------------------|----------------------------------------------------------------------------------------|----------------------------------------------------------------|
| 16       | Stale handling, retries, timeouts and circuit breaker.      | Timeout versus retry versus circuit breaker. Why /v1/health does not prove fresh data. | Fault timeline for the 30 second unavailable fault             |
| 17       | SSE with coalescing and polling fallback.                   | Why polling remains. Why a minimum refresh interval is needed.                         | Log excerpt of reconnect and full refresh                      |
| 18       | Crisis injections: spike, route disruption, shipment delay. | What each event changes and what does not undo itself.                                 | Per scenario evidence with anomaly flag and depot window       |
| 19       | Metrics, logs, Prometheus, Grafana and the status panel.    | What each panel answers. Why ids stay out of metric labels.                            | Status panel screenshot with p95 and error rate during a fault |
| 20       | Clean Compose startup and a recorded incident.              | Liveness versus readiness. Recovery time in wall seconds.                              | Recording and run id                                           |

### Day 5 Monday November 23

| **Step** | **Do**                                                     | **Understanding gate: explain aloud without notes**                         | **Evidence for the update**                   |
|----------|------------------------------------------------------------|-----------------------------------------------------------------------------|-----------------------------------------------|
| 21       | Baseline versus candidate comparison and detection replay. | Why seed and cadence are fixed. What the numbers do and do not show.        | policy-comparison.json and anomaly-replay.txt |
| 22       | Load tests in PAUSED and RUNNING mode.                     | Which limit you found and why.                                              | load-summary.json                             |
| 23       | CI, clean clone, README, ADRs and evidence index.          | What a reviewer needs to reproduce the run.                                 | Green CI link and release SHA                 |
| 24       | Three demo rehearsals and the surprise drill.              | One complete decision and one failure, each explained in under two minutes. | Rehearsal notes and the final report          |

### When to send updates to Poridhi

Send an update at each point below. Each is tied to a step, so you only report what you can show. Use the channel and time agreed with Poridhi.

| **Update**     | **When**                                                                | **Contents**                                                                                                 |
|----------------|-------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------|
| Update 1       | End of day 1, after step 5                                              | Real data screen, contract notes with the measured tick rate, Prometheus output, first CI run                |
| Update 2       | End of day 2, after step 11                                             | Forecast, station and depot examples, anomaly test results; state that algorithm quality is still unmeasured |
| Update 3       | End of day 3, after step 15                                             | Risk to approval to arrival recording, duplicate approval test, mode aware approval tests                    |
| Update 4       | End of day 4, after step 20                                             | Fault and recovery timeline, status panel screenshot, metrics, clean Compose start                           |
| Update 5       | End of day 5, after step 24                                             | Policy comparison, load report, CI, tagged release and known limitations                                     |
| Short note     | After steps 3, 9, 14 and 19                                             | One or two lines with an evidence link, because these steps change what the system can do                    |
| Blocker update | At once for missing simulator access; otherwise after two hours blocked | Use the blocker template in chapter 37                                                                       |

### Day 1 update draft

Fill every bracket from real results and send only after the work is done. Do not report a planned test as passed.

```
Subject: Fuel platform progress - Day 1 - 2026-11-19

Completed
- Connected the Spring Boot adapter to the official simulator and showed depot and station inventory in React with tick, mode and refresh age | [commit]
- Measured the running clock: [value] ticks per second at speed [S] and tick size [T] | [contract notes]
- Prometheus endpoint live | [curl output]

Currently working on
- Demand history ingestion and the forecast baseline

Challenges or blockers
- [Problem, impact, check and next experiment] or: No blocking issues. Known risk: [specific risk].

Next
- Station and depot risk with unit tests

What I learned and agent usage
- [Concept] checked by [manual check]; [agent task and correction, if any]

Status
- Release target: November 23; deadline: November 26.
- Confidence: [on track / at risk] because [evidence].
```

### Completed and next lines for days 2 to 5

Reuse the Day 1 format. Replace the Completed and Next lines with these, filled with real values.

| **Day** | **Completed lines to adapt**                                                                                                                                                                                                                                | **Next**                                                               |
|---------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------|
| 2       | Forecast with \[WAPE value\] against the naive baseline. Station and depot projections checked by hand. Allocation respects the shared dispatch limit and lists alternatives. Anomaly rule flagged a synthetic spike in \[N\] ticks with \[N\] false flags. | Persistence, risk UI and approval flow                                 |
| 3       | Risk to approval to arrival recorded. Duplicate approval produced \[N\] simulator allocations. Approval in RUNNING mode sent \[equal / reduced / no\] quantity as tested.                                                                                   | Resilience, SSE, metrics and the status panel                          |
| 4       | Unavailable fault handled with cached read mode and blocked approvals; recovery took \[N\] seconds. Status panel showed p95 \[value\] and error rate \[value\] during the fault.                                                                            | Policy comparison, load tests and release rehearsal                    |
| 5       | Baseline versus candidate results \[raw values\]. Load report for PAUSED and RUNNING. CI green. Release \[SHA\] tagged. Known limitations listed.                                                                                                           | Buffer: clean environment check, two rehearsals and the surprise drill |

## 36 Send daily updates that can be verified

Send a short update at the end of each build day and an earlier blocker update when a dependency prevents progress. Use the channel and time agreed with Poridhi. Preparing a draft is separate from sending it; this guide does not post messages or schedule reminders.

Chapter 35 lists the five scheduled updates, the short notes after key steps and a draft for each day. Use the template below to format them.

### Reusable daily update template

```
Subject: Fuel platform progress - Day <N> - <date>

Completed
- <Delivered behavior> | commit/PR: <link>
- Evidence: <test output, screenshot or short recording>

Currently working on
- <Small active issue and why it matters>

Challenges or blockers
- <Problem, impact, attempted checks and next experiment>
- If none: No blocking issues. Known risk: <specific risk>.

Next
- <Next deliverable and its acceptance check>

What I learned and agent usage
- <Concept I can now explain and the manual check I used>
- <Agent task, review performed and correction if applicable>

Status
- Release target: November 23; deadline: November 26.
- Confidence: <on track / at risk> because <evidence>.
```

### Example after a real day one completion

Illustrative wording to adapt only after doing the work: I connected the Spring Boot adapter to the official simulator and displayed depot and station inventory in React. The screen shows simulator tick and last successful refresh time. Evidence: \[commit\] and \[recording\]. I am implementing demand history ingestion next.

Illustrative blocker wording: The guide gives conflicting tick defaults. I set TICK_MINUTES explicitly and checked /v1/instance before testing. I recorded the returned value in the contract notes. The unresolved risk is allocation departure timing; tomorrow's projection will use the observed behavior after a stepped test.

Do not report a planned test as passed or use a screenshot of a mocked screen as proof of simulator integration. A useful update makes completion, active work, blockers and next steps easy to distinguish.

## 37 Track progress through artifacts and decisions

| **Day** | **Expected evidence**                                                                                  | **Learning checkpoint**                                       |
|---------|--------------------------------------------------------------------------------------------------------|---------------------------------------------------------------|
| 1       | Read-only demo, contract notebook with measured tick rate, diagram, Prometheus output, first CI run    | Explain truth ownership, tick conversion and simulation speed |
| 2       | Forecast, station and depot examples, anomaly test, constraint tests, recommendation with alternatives | Explain demand versus sales, shared dispatch and depot risk   |
| 3       | Approval to arrival recording, command ledger, duplicate test, mode aware approval tests               | Explain crash window, stable keys and the reviewed envelope   |
| 4       | Fault timeline, stale guard, recovery, status panel with p95 and error rate, metric panels             | Explain degradation, polling and health                       |
| 5       | Policy comparison, detection replay, load report in both modes, clean run and demo                     | Explain measured benefit and limitations                      |

### Evidence folder proposal

```
docs/evidence/
  day-01/contract-responses.txt
  day-01/simulator-speed.txt
  day-02/manual-risk-example.txt
  day-02/depot-example.txt
  day-02/anomaly-replay.txt
  day-03/duplicate-approval-results.txt
  day-03/approval-revalidation-results.txt
  day-04/fault-recovery-timeline.txt
  day-04/status-panel-during-fault.png
  day-05/policy-comparison.json
  day-05/load-summary.json
  release-index.txt
  agent-activity.txt
```

Each record should include release commit, test command or manual action, configuration, run id, simulator tick bounds, observed result and file links. Screenshots support the record but do not replace the request, response or measured result.

### Use a small issue board

Suggested columns are Backlog, Ready, In Progress, Verify and Done. Keep at most one implementation issue active for yourself. An issue enters Done only after its acceptance behavior is observed, its diff is reviewed and evidence is saved.

Use a brief decision record whenever a choice affects correctness or delivery: modular monolith, forecast method, conservative headroom, polling cadence, timeout budget and operator access. Include the limitation and revisit trigger. This is more useful in an interview than naming several design patterns without explaining their effect.

### Blocker escalation template

Blocked on \[dependency\] since \[time\]. Impact: \[which acceptance story cannot complete\]. Evidence: \[error or reproduction\]. Tried: \[checks\]. Independent work continuing: \[task\]. Needed: \[specific clarification or access\]. Next checkpoint: \[time\]. Do not wait until the deadline to disclose missing simulator access or an unresolved duplicate-write defect.

## 38 Run a controlled demonstration

Prepare an eight minute demo against a local dedicated run. Keep the simulator paused between scripted steps so you can explain the state. Save the release SHA, environment and exact actions in the runbook.

| **Minute** | **Demonstration**                                                                 | **What to explain**                                                                     |
|------------|-----------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------|
| 0 to 1     | Show architecture and the healthy dashboard with the status panel                 | Official simulator, two clocks, source of truth and the mode badge                      |
| 1 to 2     | Inspect station and depot risk                                                    | Demand observations, horizon, uncertainty and the depot window                          |
| 2 to 3     | Generate and review an allocation with its alternatives                           | Reasoning, route and capacity constraints, why alternatives lost                        |
| 3 to 4     | Approve once and step to delivery; then resume the clock slowly and approve again | Stable command key, PENDING versus arrival, reviewed envelope in RUNNING mode           |
| 4 to 5     | Inject demand spike or route disruption                                           | Anomaly flag, risk adapts and only real alternatives are offered                        |
| 5 to 6     | Inject official unavailable or stale fault                                        | Cached view, alert, p95 and error rate on the status panel, server enforced write guard |
| 6 to 7     | Clear the fault and show recovery                                                 | REST refresh, reconciliation and health evidence                                        |
| 7 to 8     | Show policy and load results, CI and the agent log                                | Measured outcomes, limitations and your decisions                                       |

### Before presenting

- Reset the dedicated world and start a new application run. Verify intended tick size and paused mode. Clear old faults, warm up the required demand samples and schedule the known event.

- Verify the chosen route, inventory and station headroom before the demo allocation. Do not assume the example 3000 liter request is valid at an arbitrary tick.

Start in PAUSED mode at a known tick. Choose the speed for the RUNNING segment, restart the simulator with it, record the measured tick rate and test the segment once before presenting.

- Have the exact official fault and cleanup actions ready. Open the UI, logs and Grafana panels using the same run id and time window.

### If the live demo fails

Explain the observed state and use logs to identify the failing boundary. If needed, show the previously recorded run from the same release, clearly labeled as a recording, then demonstrate current recovery. Avoid silently switching to static mock values or claiming a recovery that was not observed.

### Handover evidence

Deliver repository URL, release tag or SHA, startup instructions, architecture diagram, configuration list, intelligence explanation, actual comparison and load results, failure recording, daily updates and known limitations. Follow Poridhi's confirmed handover channel; this plan does not infer a final submission workflow.

## 39 Prepare to defend your engineering choices

| **Question**                                   | **Answer you should be able to demonstrate**                                                                                                   |
|------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------|
| Why Spring Boot and React?                     | Requested stack; Java handles typed integration and pure policy logic, React supports operator state and review.                               |
| Why one backend?                               | The world is small and the deadline is short; clear modules provide testable boundaries without distributed failure overhead.                  |
| Where is the intelligence?                     | Forecasts become stock trajectories and constrained priorities; show a measured comparison against the baseline.                               |
| Why no reinforcement learning?                 | It is optional; the first release needs explainable feasible behavior and evaluation before a more complex policy.                             |
| What happens after an accepted POST times out? | The command stays UNKNOWN, then reconciles with its original key; show one resulting shipment.                                                 |
| Why not trust SSE?                             | No replay and bounded queues can miss events; REST refresh repairs the view.                                                                   |
| Can a healthy system still serve stale data?   | Yes. Health bypasses faults and HTTP 200 can carry X-Simulator-Stale.                                                                          |
| How did you use agents responsibly?            | Bounded issues, persisted context, reviewed diffs, actual tests and a record of your own corrections.                                          |
| What would production require next?            | Stronger access control, wider test scenarios, migration and backup operations, deployment hardening and measured scaling needs.               |
| How does approval work while the clock runs?   | The proposal is a reviewed envelope. The server revalidates against a fresh read and sends an equal or smaller quantity or asks for re-review. |
| How do you detect a demand spike?              | Ratio of observed demand to the documented hour of day profile over three ticks, with a measured detection delay and false flag count.         |
| Why does depot risk matter?                    | Delays and shortfalls hit depot supply first; station risk alone would show the effect late.                                                   |

### Release checklist

- All required operator stories work from a clean startup; no operational page depends on hardcoded data.

Approval works in PAUSED and RUNNING mode with saved test output. Station and depot risk, anomaly flags and alternatives appear in the UI from real simulator data. The Prometheus endpoint and the status panel with p95 latency and error rate are live in the Compose stack.

- Constraint, stale, duplicate approval and unknown outcome checks pass with actual output saved.

- At least one official crisis and one official API failure have a recorded detection, response and recovery.

- The policy comparison and load report contain real raw results with machine and simulator configuration.

- README, diagrams, API contract, ADRs, daily updates and evidence index match the frozen release.

A strong preparation package supports your interview, but selection remains Poridhi's decision. Your most useful proof is a system you can run, question and repair while explaining why each important decision is correct.

## 40 Sources and further reading

Local source references used throughout the guide are listed below. Page references are PDF page numbers. Technical links were checked on November 19 2026; pin the versions you actually install and recheck compatibility when they differ.

B BUP CSE FEST 2026 Participant Facing Hackathon Draft, 13 pages. Challenge, requirements, deliverables and suggested evaluation weights. The document identifies itself as a participant-facing draft.

G BUP Fuel Supply Simulator Integration Guide Final, 16 pages. Concrete HTTP contract, events, fault injection, world configuration and client behavior.

L1 First supplied Poridhi screenshot. Visible modules include Docker, AWS, Docker Compose, GitHub Actions with Docker, Claude Code Fundamentals and Agentic Engineering with Claude. AWS is excluded from this plan at your request.

L2 Second supplied screenshot. Visible agentic topics include session workflows, context window management, persistent context, skills, subagents and MCP. The screenshot does not expose individual lab instructions or an evaluation rubric.

S1 Spring Boot system requirements  
<https://docs.spring.io/spring-boot/system-requirements.html>

S2 Vite getting started  
<https://vite.dev/guide/>

S3 React Thinking in React  
<https://react.dev/learn/thinking-in-react>

S4 Spring Boot monitoring over HTTP  
<https://docs.spring.io/spring-boot/reference/actuator/monitoring.html>

S5 Spring Boot metrics  
<https://docs.spring.io/spring-boot/reference/actuator/metrics.html>

S6 Docker Compose startup order  
<https://docs.docker.com/compose/how-tos/startup-order/>

S7 GitHub Actions Java with Maven  
<https://docs.github.com/en/actions/tutorials/build-and-test-code/java-with-maven>

S8 Grafana k6 thresholds  
<https://grafana.com/docs/k6/latest/using-k6/thresholds/>

S9 to S12 Claude Code memory, skills, subagents and MCP  
<https://code.claude.com/docs/en/memory>  
<https://code.claude.com/docs/en/skills>  
<https://code.claude.com/docs/en/sub-agents>  
<https://code.claude.com/docs/en/mcp>

## 41 Reusable startup and scenario references

### Minimal simulator Compose fragment

This explicitly resolves the guide's conflicting defaults for the proposed test setup. It is a fragment for the simulator, not the complete application deployment. For a human paced RUNNING segment in the demo, change SIMULATION_SPEED to 1 and record the measured rate; keep SIMULATOR_START_MODE paused for all other runs.

```
services:
  simulator-api:
    image: asifmahmoud414/bup-fuel-supply-simulator:1.0.0
    environment:
      SIMULATION_SPEED: "8"
      TICK_MINUTES: "15"
      SIMULATOR_START_MODE: "paused"
    ports:
      - "127.0.0.1:8000:8000"
```

### Manual verification commands

```
docker compose up -d simulator-api
curl -fsS http://localhost:8000/v1/health
curl -fsS http://localhost:8000/v1/instance
curl -fsS -X POST http://localhost:8000/admin/pause
curl -fsS -X POST http://localhost:8000/admin/step
curl -fsS http://localhost:8000/v1/allocations
```

### Example payloads for a dedicated test run

```
# POST /admin/events after reset and pause
{"type":"demand_spike","start_tick":8,"duration_ticks":12,
 "parameters":{"region_ids":["region-dhaka"],"multiplier":1.8}}

# POST /admin/faults uses wall clock duration
{"type":"unavailable","duration_seconds":30,"parameters":{}}

# Cleanup: POST /admin/faults/clear
# Admin controls arrange tests; operational reads use /v1/.
```

Set event start_tick relative to the current test run. Confirm Content-Type: application/json when sending payloads. Save the response and the current tick. Commands and examples in this guide have not been run against a live simulator as part of preparing this document.

Next action: create the repository, read the Day 1 checklist and draw the architecture yourself. End the first session with one real read-only screen, a contract notebook and a short evidence-based update.
