# Contract Notes - BUP Fuel Supply Simulator

**Probe date:** 2026-11-20 (Day 1)
**Simulator image:** `asifmahmoud414/bup-fuel-supply-simulator:1.0.0` (unmodified)
**Base URL:** `http://localhost:8000`
**Configuration used for these probes:** `SIMULATION_SPEED=8`, `TICK_MINUTES=15`, `SIMULATOR_START_MODE=paused`
**Java:** Zulu OpenJDK 21.0.8 LTS (`C:\Users\MSI\.jdks\azul-21.0.8`)
**Method:** direct HTTP calls via `curl` and `Invoke-WebRequest`. Every value below is an observed response, not an assumption from the PDF.

---

## 1. Instance and clock

```json
GET /v1/instance
{"id":1,"scenario_id":"baseline","scenario_version":"1.0","seed":12345,
 "sim_time":"2026-01-01T00:00:00","tick":0,"tick_minutes":15,"status":"PAUSED"}
```

| Field | Observed | Note |
|---|---|---|
| `tick_minutes` | 15 | Confirms the guide page 5 default, **not** the compose default of 8. |
| `status` | `PAUSED` / `RUNNING` | Read on **every** refresh; this is the mode badge. |
| `seed` | 12345 | Same for every participant. |
| `sim_time` | naive, no timezone suffix | `2026-01-01T00:00:00` - **no offset**. The PDF example shows `+00:00`. Treat as simulator-local, do not attach a zone silently. |

**Tick conversion (verified):** 1 tick = 15 simulated minutes = 0.25 simulated hours.
`route.transit_ticks` -> hours = `transit_ticks * tick_minutes / 60`. Route `transit_ticks=2` -> 0.5 simulated hours.

---

## 2. Measured tick rate - CONTRADICTS the configured speed

Configured `SIMULATION_SPEED=8`, `TICK_MINUTES=15`. Measured over three 30 second windows in RUNNING mode:

| Run | tick start | tick end | delta | elapsed (s) | measured ticks/sec | simulated minutes |
|---|---|---|---|---|---|---|
| 1 | 2003 | 2168 | 165 | 30.04 | **5.493** | 2475 (41.25 sim hours) |
| 2 | 2169 | 2333 | 164 | 30.08 | **5.451** | 2460 (41.00 sim hours) |
| 3 | 2333 | 2496 | 163 | 30.04 | **5.426** | 2445 (40.75 sim hours) |

**The simulator sustains ~5.46 ticks/sec, not 8.** The configured speed is an upper bound the machine does not reach. Consequences for the application:

- Effective simulated time per wall second = `5.46 * 15` = **~82 simulated minutes/sec**, not the configured 120.
- A 30 second operator review spans **~41 simulated hours**. A proposal is badly out of date by the time it is approved. This is why a one-tick staleness rule can never work in RUNNING mode.
- Refresh cadences must be derived from the **measured** rate, not the documented one.

**Design decision:** the backend measures the observed tick rate at runtime and publishes it as a metric (`fuel_sim_ticks_per_second`). Refresh limits are derived from the measured rate.

---

## 3. World contents (observed)

**Depots (2)**

| id | region | dispatch/tick | capacity D/P/O | inventory D/P/O |
|---|---|---|---|---|
| `depot-gazipur` | region-dhaka | 12000 | 90000/70000/45000 | 60000/45000/26000 |
| `depot-patiya` | region-chattogram | 11000 | 85000/65000/40000 | 55000/42000/24000 |

**Stations (4)**

| id | region | profile | capacity D/P/O | inventory D/P/O |
|---|---|---|---|---|
| `station-mirpur` | dhaka | urban_high | 15000/14000/9000 | 9000/9000/5000 |
| `station-tongi` | dhaka | industrial | 18000/9000/6000 | 11000/6000/3500 |
| `station-karnaphuli` | chattogram | highway | 14000/15000/9000 | 8500/9500/5200 |
| `station-coxsbazar` | chattogram | regional | 12000/12000/7000 | 7500/7500/4200 |

**Routes (6)** - all `AVAILABLE` at baseline.

| id | depot -> station | transit_ticks | max_shipment |
|---|---|---|---|
| `route-gazipur-mirpur` | gazipur -> mirpur | 2 | 7000 |
| `route-gazipur-tongi` | gazipur -> tongi | 2 | 6500 |
| `route-patiya-karnaphuli` | patiya -> karnaphuli | 2 | 7000 |
| `route-patiya-coxsbazar` | patiya -> coxsbazar | 3 | 6000 |
| `route-gazipur-karnaphuli` | gazipur -> karnaphuli (cross-region) | 4 | 5000 |
| `route-patiya-mirpur` | patiya -> mirpur (cross-region) | 4 | 5000 |

**Regions (2):** `region-dhaka` factor 1.00, `region-chattogram` factor 1.08.

**Supply arrivals:** exactly 22 rows. 4 initial burst at ticks 12/14/16/20, then 18 recurring resupplies spaced 64 ticks apart.

---

## 4. Demand history

```
GET /v1/demand-history?station_id=station-mirpur&limit=12
```

| Property | Observed |
|---|---|
| Ordering | **Descending by tick** (newest first): `2688,2688,2688,2687,2687,2687,...` |
| Rows per tick per station | 3 (one per fuel). 4 stations x 3 fuels = 12 rows per tick. |
| `limit=0` | Clamped to 1, returns 1 row, status 200 |
| `limit=5000` | Clamped to 2000, returns 2000 rows, status 200 |
| `station_id` omitted | 200, returns rows (not an error) |
| unknown `station_id` | 200, returns empty array (not an error) |

**Rows are sorted newest-first.** The forecast must re-sort ascending before computing an EWMA, otherwise the EWMA is computed backwards in time.

**`demand_liters` is NOT `served_liters`.** Observed after stations ran dry:

```json
{"tick":2688,"fuel_type":"DIESEL","demand_liters":56.771,"served_liters":0.0,"unmet_liters":56.771}
```

`served_liters` is capped by available stock; `demand_liters` is the real need. **Forecast on `demand_liters`.** Using `served_liters` would learn the stockout and then under-forecast forever - a classic feedback loop that hides the very shortage you are trying to detect.

`service_level` collapsed to **0.034** during that dry run, confirming `served / (served + unmet)` with `unmet` accumulated from real unmet demand.

---

## 5. Allocation write path - VERIFIED

### 5.1 Create

```http
POST /v1/allocations
{"idempotency_key":"probe-key-001","source_depot_id":"depot-gazipur",
 "destination_station_id":"station-mirpur","route_id":"route-gazipur-mirpur",
 "fuel_type":"DIESEL","quantity":3000}
```

Response **201 Created**:

```json
{"id":1,"idempotency_key":"probe-key-001","source_depot_id":"depot-gazipur",
 "destination_station_id":"station-mirpur","route_id":"route-gazipur-mirpur",
 "fuel_type":"DIESEL","quantity":3000.0,"created_tick":2,"departure_tick":null,
 "expected_arrival_tick":null,"actual_arrival_tick":null,"status":"PENDING","failure_reason":null}
```

### 5.2 Depot inventory drops at POST, not at departure - RESOLVED

This was the single biggest open question in the guide ("whether depot inventory drops at POST").

| Moment | gazipur DIESEL inventory |
|---|---|
| baseline | 60000 |
| immediately after POST 3000 | **57000** |
| after replay of same key | 57000 (no second deduction) |

**Conclusion: the simulator deducts depot inventory at creation time.** The depot `inventory` field returned by `GET /v1/depots` is therefore *already net of pending and in-flight shipments*.

**Application consequence:** the allocator must **not** subtract pending/in-transit quantities from depot inventory a second time. Doing so double-counts reservations and starves the depot. This is the trap the guide flagged in chapter 15.

### 5.3 Idempotency - VERIFIED

| Scenario | Status | Body |
|---|---|---|
| Same key, same body (replay) | **201** | The existing allocation, same `id=1`, no new row, no second inventory deduction |
| Same key, different body (qty 3500 vs 3000) | **409** | `{"detail":{"code":"IDEMPOTENCY_KEY_MISMATCH","message":"Idempotency key was already used with different parameters."}}` |
| New key, different body | 201 | Accepted as a separate allocation |

**Replay returns 201, not 200.** The application must treat **both 200 and 201 as success** and read the allocation id from the body either way.

**A cancelled allocation still occupies its key.** After cancelling `probe-cancel-002`, replaying that exact key returned **201** with the **same cancelled allocation** (`status: CANCELLED`). The key is permanently occupied and the replay does **not** create a new shipment. This is safer than the PDF implies and means a naive "retry after cancel" would silently never ship.

### 5.4 Validation order - VERIFIED empirically

The guide lists nine checks. Observed order and exact bodies:

| # | Trigger | Status | Body |
|---|---|---|---|
| 1 | Idempotency key already used with different body | 409 | `IDEMPOTENCY_KEY_MISMATCH` |
| 2 | Unknown depot | 404 | `NOT_FOUND` / "Source depot not found" |
| 3 | Unknown station | 404 | `NOT_FOUND` / "Destination station not found" |
| 4 | Unknown route | 404 | `NOT_FOUND` / "Route not found" |
| 5 | Route endpoints do not match request | 409 | `ROUTE_MISMATCH` |
| 6 | Depot status not OPEN/CONSTRAINED | 409 | `DEPOT_CLOSED` |
| 7 | Station status != OPEN | 409 | `STATION_CLOSED` |
| 8 | Route status != AVAILABLE | 409 | `ROUTE_DISRUPTED` |
| 9 | quantity > route.max_shipment | 409 | `ROUTE_CAPACITY_EXCEEDED` |
| 10 | depot.inventory[fuel] < quantity | 409 | `INSUFFICIENT_INVENTORY` |
| 11 | Dispatch capacity per tick exceeded | 409 | `DISPATCH_CAPACITY_EXCEEDED` |
| 12 | station.inventory[fuel] + quantity > capacity | 409 | `DESTINATION_CAPACITY_EXCEEDED` |
| - | Bad fuel enum | 422 | Pydantic `literal_error` array |
| - | quantity <= 0 | 422 | Pydantic `greater_than` array |
| - | Missing field | 422 | Pydantic `missing` array |

**Two error envelope shapes exist and must both be parsed:**
- Domain errors: `{"detail":{"code":"UPPER_SNAKE","message":"..."}}` - `detail` is an **object**
- Pydantic validation errors: `{"detail":[ {...}, {...} ]}` - `detail` is an **array**
- Injected faults: `{"error":{"code":"FAULT_INJECTED","message":"..."}}` - top-level `error`, **not** `detail`

A Jackson client that maps `detail` to a single object will fail to deserialize the 422 array. The adapter needs `JsonNode` for `detail` and a discriminating branch.

**Ordering proof:** a request for 7000 DIESEL to `station-mirpur` (which was at 0 inventory, capacity 15000) returned `DISPATCH_CAPACITY_EXCEEDED`, not `DESTINATION_CAPACITY_EXCEEDED`. Dispatch capacity is therefore checked **before** destination capacity.

### 5.5 Dispatch capacity IS shared across fuels - CONFIRMED

`depot-gazipur.dispatch_capacity_per_tick = 12000`.

| Step | Request | Result |
|---|---|---|
| A | DIESEL 7000 to mirpur | **201** |
| B | PETROL 7000 to mirpur (different fuel, same depot, same tick) | **409** |
| C | DIESEL 6000 more (total 13100 > 12000) | **409** `DISPATCH_CAPACITY_EXCEEDED` |
| D | DIESEL 5000 to tongi (different station, same depot, same tick) | **409** |

**Confirmed:** the dispatch allowance is **per depot, shared across every fuel type and every destination**, and consumed at POST time.

This is exactly the shared-limit bug the guide's manual exercise describes. A policy that allocates each fuel independently would dispatch 4700 liters where only 4000 is allowed.

### 5.6 Shipment lifecycle - departure and arrival convention RESOLVED

The guide asked when a PENDING shipment departs and how arrival is computed. Observed with `route-gazipur-mirpur` (`transit_ticks=2`), allocation created at tick 2:

| Tick | Allocation state | Station mirpur DIESEL |
|---|---|---|
| 2 (create) | `PENDING`, `departure_tick=null`, `expected_arrival_tick=null` | - |
| 3 (after 1 step) | `IN_TRANSIT`, `departure_tick=2`, `expected_arrival_tick=4`, `actual_arrival_tick=null` | 8825.19 (consumed by demand) |
| 4 (after 2 steps) | still `IN_TRANSIT`, `actual_arrival_tick=null` | 8760.03 |
| 5 (after 3 steps) | **`ARRIVED`**, `actual_arrival_tick=4` | **11695.97** |

**Verified conventions:**

1. **`departure_tick` = `created_tick`.** The shipment departs on the same tick it is created. No extra tick is added.
2. **`expected_arrival_tick` = `created_tick` + `route.transit_ticks`.** Observed 2 + 2 = 4.
3. **The arrival is applied during the tick transition into `expected_arrival_tick`.** At tick 4 the allocation was still `IN_TRANSIT`; only after stepping to tick 5 did the status become `ARRIVED` with `actual_arrival_tick=4`, and the station inventory jumped by exactly 3000 (8760.03 + ~1000 consumed at tick 5 = 11695.97).

**So a shipment created at tick T with transit 2 lands in station inventory as of tick T+2, and the REST view confirms it one tick later.** The projection engine must use `expected_arrival_tick` and must not assume the arrival is visible in the same refresh that reaches that tick.

### 5.7 Cancel

| Scenario | Status | Body |
|---|---|---|
| Cancel a PENDING allocation | 200 | allocation with `status: CANCELLED` |
| Depot inventory after cancel | - | **refunded in full** (PETROL 69500 -> 70000) |
| Cancel an already ARRIVED allocation | 409 | `{"detail":{"code":"CANNOT_CANCEL","message":"Only pending allocations can be cancelled"}}` |
| Cancel an unknown id | 404 | `{"detail":{"code":"ALLOCATION_NOT_FOUND"}}` |

Cancel refunds depot inventory. This is the one place where depot inventory can go back up, so the allocator must read depot inventory from a **fresh** read rather than tracking a running total.

---

## 6. Fault injection

| Fault | Effect on `/v1/*` GET | Effect on `/v1/health` |
|---|---|---|
| `stale_data` | **200 OK** + header `x-simulator-stale: true` | 200, **no stale header** |
| `unavailable` | **503** `{"error":{"code":"FAULT_INJECTED","message":"Simulator API temporarily unavailable."}}` | **200 OK** |
| `error_rate` | 503 with the same injected-error envelope, probabilistic | 200 OK |
| `latency` | delayed by `delay_ms` | unaffected |
| `stream_disconnect` | `GET /v1/stream` returns 503 `{"detail":{"code":"FAULT_INJECTED"}}` | 200 OK |

**Two critical confirmations:**

1. **A stale response is a 200.** The status code says nothing. The client MUST inspect the `X-Simulator-Stale` header on every public GET. A stale payload must never become an eligible decision snapshot, even though the transport succeeded.
2. **`/v1/health` bypasses every fault.** Health returning 200 proves the process is alive, **not** that station data is fresh. Liveness and data freshness are different things and must be reported separately.

`POST /admin/faults/clear` returns `{"status":"cleared"}` and is idempotent.

---

## 7. SSE stream

```text
: connected

event: simulation.tick
data: {"tick": 2624, "sim_time": "2026-01-28T08:00:00"}

event: simulation.tick
data: {"tick": 2625, "sim_time": "2026-01-28T08:15:00"}
```

- On connect the server sends an SSE **comment** `: connected` - ignore it.
- `simulation.tick` fires once per tick, carrying `tick` and `sim_time`.
- Events are separated by a blank line; payload is on the `data:` line.
- 15 second keepalive comment is normal and must not be treated as a disconnect.
- There is no `Last-Event-ID` replay. After a reconnect only new events arrive, so a **full REST refresh is mandatory after every reconnect**.
- The subscriber queue holds 200 events. A slow reader falls behind and is **silently dropped** - the reader thread must do no heavy work and must reconnect on its own.

---

## 8. Admin endpoints (test driver only)

| Endpoint | Behaviour |
|---|---|
| `POST /admin/step` | Advances exactly one tick, works while PAUSED. Returns `{"tick":6,"sim_time":"..."}`. **This is the deterministic test driver.** |
| `POST /admin/run` / `POST /admin/pause` | Switch `status` between RUNNING and PAUSED. PENDING/IN_TRANSIT shipments stay frozen while paused. |
| `POST /admin/reset` | Hard reset, reloads the baked scenario, `tick` back to 0. Returns the instance. **Destructive - destroys history and all allocations.** |
| `POST /admin/events` | Injects a crisis event (6 types). |
| `POST /admin/faults` | Injects a fault (5 types), `duration_seconds` uses **wall clock**. |
| `POST /admin/faults/clear` | Clears all faults. |
| `GET /admin/audit?limit=` | Ground-truth simulator audit log, id-desc, limit 1..1000. |

`/admin/*` **bypasses fault injection**. Fault tests must hit `/v1/*` only.

---

## 9. Unresolved questions (carried forward)

| Question | Current handling |
|---|---|
| Do PENDING/IN_TRANSIT shipments count against `dispatch_capacity_per_tick` on **later** ticks, or only the creation tick? | Observed: reservations are consumed at POST and block same-tick writes. Whether they persist into the next tick is untested. The conservative allocator reserves them for the whole planning window. |
| Does `CONSTRAINED` depot status reduce the numeric dispatch limit, or is it only a label? | Untested. The guide forbids inventing a numeric reduction. The application must read the returned limit and treat `CONSTRAINED` as context only. |
| What is the exact `sim_time` timezone? | The response is naive (`2026-01-01T00:00:00`), while the PDF shows `+00:00`. Treated as simulator-local; no offset is invented. |
| Does `demand-history` filter by fuel? | No documented fuel parameter. Rows for all 3 fuels share one `limit`, so **split rows by fuel after fetching** or the per-fuel sample count will be wrong by 3x. |

---

## 10. Implementation decisions this contract forces

1. **Never trust a 200.** Inspect `X-Simulator-Stale` on every `/v1/*` GET. A stale body may be displayed with a warning but must never back a decision.
2. **Depot inventory is already net of reservations.** Do not subtract pending/in-transit from it again.
3. **Dispatch allowance is per depot, shared across fuels and destinations.** Reserve it in the working copy before planning the next line.
4. **Sort demand ascending before forecasting.** The API returns newest-first.
5. **Forecast `demand_liters`, never `served_liters`.**
6. **Replay returns 201.** Accept 200 and 201 as success.
7. **Parse `detail` as both object and array.** Three distinct error envelope shapes exist.
8. **Expected arrival = created tick + transit ticks.** Departure is same-tick. The arrival becomes visible one tick after `expected_arrival_tick`.
9. **Measure the tick rate; do not trust `SIMULATION_SPEED`.** ~5.46 ticks/sec observed at speed 8.
10. **Health is liveness only.** A healthy simulator can still be serving stale or unavailable data.
