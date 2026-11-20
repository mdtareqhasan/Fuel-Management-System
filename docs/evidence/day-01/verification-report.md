# Day 01 verification report

Generated: 2026-11-20T11:10:18.045522+00:00

Every result below was produced by running the script, not asserted by hand.

**33 passed, 0 failed**

## 1. Stack reachability

- **PASS** — backend health
  - HTTP 200 from http://localhost:8080/actuator/health
- **PASS** — dashboard
  - HTTP 200 from http://localhost:8080/api/dashboard
- **PASS** — system status
  - HTTP 200 from http://localhost:8080/api/system-status
- **PASS** — prometheus metrics
  - HTTP 200 from http://localhost:8080/actuator/prometheus
- **PASS** — frontend
  - HTTP 200 from http://localhost:5173
- **PASS** — simulator instance
  - HTTP 200 from http://localhost:8000/v1/instance

## 2. Dashboard content comes from the simulator

- **PASS** — 4 stations
  - got 4: ['station-mirpur', 'station-tongi', 'station-karnaphuli', 'station-coxsbazar']
- **PASS** — 2 depots
  - got 2: ['depot-gazipur', 'depot-patiya']
- **PASS** — 6 routes
  - got 6
- **PASS** — 2 regions
  - got 2
- **PASS** — all 3 fuels present for every station
  - DIESEL, PETROL and OCTANE on each of the 4 stations
- **PASS** — inventory is non-zero (real data, not placeholders)
  - every station has at least one non-zero fuel level
- **PASS** — depot dispatch capacity present
  - dispatch_capacity_per_tick = [12000.0, 11000.0]
- **PASS** — snapshot is decision eligible
  - quality=FRESH

## 3. The simulator's own response is the reference

- **PASS** — simulator returns 4 stations
  - 4

## 4. Stale header handling

- **PASS** — no stale header in the healthy state
  - x-simulator-stale = (absent)
- **PASS** — simulator returns HTTP 200 with the stale header under the fault
  - HTTP 200, x-simulator-stale = true
- **PASS** — backend marks the view STALE
  - data_quality = STALE
- **PASS** — backend blocks decisions on stale data
  - decision_eligible = False
- **PASS** — simulator still reported reachable
  - simulator = UP (reachability is separate from freshness)
- **PASS** — last known data is still shown while stale
  - 4 stations still visible

## 5. Recovery

- **PASS** — view returns to FRESH after the fault clears
  - data_quality = FRESH
- **PASS** — decisions are re-enabled
  - decision_eligible = True

## 6. Unavailable fault (cached read mode)

- **PASS** — simulator reported DOWN
  - simulator = DOWN
- **PASS** — view marked INVALID
  - data_quality = INVALID
- **PASS** — decisions blocked
  - decision_eligible = False
- **PASS** — cached view is still served
  - 4 stations from the cached snapshot

## 7. Metrics

- **PASS** — metric fuel_data_age_seconds
  - present in /actuator/prometheus
- **PASS** — metric fuel_database_up
  - present in /actuator/prometheus
- **PASS** — metric fuel_simulator_up
  - present in /actuator/prometheus
- **PASS** — metric fuel_simulator_call_seconds
  - present in /actuator/prometheus
- **PASS** — metric fuel_rejected_payloads_total
  - present in /actuator/prometheus
- **PASS** — metric fuel_stale_responses_total
  - present in /actuator/prometheus

## 8. Live counter values

- `fuel_data_age_seconds 1.339`
- `fuel_database_up 1.0`
