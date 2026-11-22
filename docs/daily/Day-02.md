# Phase 01 · Day 2 — Intelligence & Risk

**Target:** Saturday, 21 November 2026 · **Status:** Complete.

| Milestone | Focus areas | Intended result |
|:--|:--|:--|
| Intelligence & risk | Demand history, forecast, station/depot projection, anomaly rules, feasible proposals | Explainable risk view and feasible recommendations without changing simulator state |

## What Day 2 delivers

A single pipeline that turns a validated simulator snapshot into an explainable
answer, and shows it on the operator console:

```
demand history → forecast → station risk → depot risk → allocation plan
                     ↘ anomaly detection
```

**Nothing writes to the simulator.** The allocation step produces *proposals*.
Day 3 owns the approval path that turns a proposal into a shipment. This boundary
is the reason the whole intelligence layer is safe to run on a live world.

---

## 1. The measurement came first, and it caught a transcription error

Before writing any detector, the profile constants were **re-measured against the
running simulator** rather than copied from the PDF. The script is
`scripts/measure_hour_factor.py` and the output is in `docs/evidence/day-02/`.

The measurement found that the guide's highway busy window is **06–09**, six hours
wide, not 07–09. With the narrow window, hour 6 of every day produced a ratio of
exactly 2.0 and a permanent false alarm on `station-karnaphuli`.

This is the single most important thing to understand about the detector: **the
ratio is only as good as the denominator.** A wrong denominator does not produce
a slightly wrong answer, it produces a detector that cries wolf every day, and an
operator who learns to ignore it is worse off than one with no detector at all.

The measured constants now live in `application.yml` under
`app.simulator-profile.profiles`, versioned `measured-2026-11-21`.

### A measurement that had to be redone

The first hour-factor measurement was taken while a `demand_spike` event was still
`ACTIVE` in region-dhaka. That inflated exactly the two region-dhaka stations by
the spike multiplier, and the output looked like a serious profile mismatch.

`scripts/measure_hour_factor_clean.py` was written to redo it properly. It resets
the simulator, steps a full day, and **refuses to report anything if an event is
still active**. A measurement that silently includes an injected fault is worse
than no measurement, because it produces confident wrong numbers.

On the clean world, **97 of 98 hours match the documented table**. The one
exception is highway hour 5, where the implied median is 0.817 against a
documented 0.75 — a ratio of **1.09** against a threshold of **1.35**. It is a
description inaccuracy, not a detection fault, and the script says so explicitly
rather than leaving the reader to work it out.

That is why the quiet run produces zero false flags.

---

## 2. Forecast — two methods, compared on identical data

`backend/src/main/java/com/fuelplatform/risk/DemandForecaster.java`

| Method | When it is used | What it is |
|:--|:--|:--|
| `EWMA` | ≥ 4 observations | Exponentially weighted mean, α = 0.3, over the last 8 ticks |
| `NAIVE` | 2–3 observations | The last observation, repeated |
| `PROFILE_FALLBACK` | 0–1 observations | The documented daily profile divided across the ticks |

Two things make this honest rather than decorative:

**The baseline is always computed alongside.** Every forecast carries both
`recent_absolute_error` (EWMA) and `baseline_absolute_error` (naive) over the
*same* window, so the console can show which method actually won. Day 5 measures
this properly; Day 2 only makes the comparison possible.

**The fallback is labelled, not hidden.** A profile estimate is a reasonable guess
built from a documented average, not a measurement of this station. It is reported
as `PROFILE_ESTIMATE` with `LOW_DATA` confidence, the console counts them in the
"Profile fallback" tile, and the reason string says so in plain language. An
operator can always tell a learned forecast from an assumed one.

### The `limit` trap (INV-21)

The simulator's demand-history endpoint takes a `limit` that is **shared across
all three fuels**. A limit of 200 rows is only about 66 ticks of one station's
history, not 200 ticks. The rows are therefore split by fuel *before* anything is
counted, and the window is measured per fuel. Getting this wrong silently
halves the effective history and the forecast quietly gets worse.

---

## 3. Station projection — arrival timing is the whole point

`backend/src/main/java/com/fuelplatform/risk/StationRiskProjector.java`

The projection answers one question: **when does this pump run dry?** It walks
the tank forward one tick at a time, subtracting the forecast demand and adding
fuel that lands on the way.

The detail that matters most is **arrival timing**:

- A shipment that lands **before** the tank empties prevents the stockout.
- A shipment that lands **after** the tank empties **does not**. The station still
  ran dry at the earlier tick.

Both cases are tested. This is why `inbound_arrivals` carries an
`arrival_is_estimate` flag: a `PENDING` shipment has not departed, so its arrival
is a best case computed from the route's transit time, and the risk row says so
rather than presenting an estimate as a fact.

### A real bug the tests caught

The first implementation used `stock < 0` to detect an empty tank. A tank at
*exactly* zero is empty, so the projection reported the stockout **one tick late**
— 59 instead of 58. The hand calculation in the test disagreed with the code, and
the code was wrong. Fixed to `stock <= 0` in
`StationRiskProjector.java`.

---

## 4. Anomaly detection — rules, measured, and honest about their limits

`backend/src/main/java/com/fuelplatform/risk/DemandAnomalyDetector.java`

| Rule | Fires when | Severity |
|:--|:--|:--|
| **R1** | One station is ≥ 1.35× its expected demand for 3 consecutive ticks | `STATION` |
| **R2** | ≥ 2 stations in one region breach R1 together | `REGIONAL` |
| **R3** | Demand stays below 1.15× for 3 consecutive ticks | clears the flag |
| **R4** | Labels the cause `KNOWN_EVENT` or `UNEXPLAINED` | — |

R4 is the rule that makes the panel useful. A spike with a known cause is a
planned event; a spike with **no** cause is the interesting one, and it gets a
highlighted row in the console.

### Two rules that were initially wrong

**R1 was clearing on one calm tick, not three.** The guide's R3 is "ratio below
1.15 for **3 consecutive ticks**". The first implementation cleared the flag on
the first calm tick. One quiet tick in the middle of a spike is ordinary noise,
so a real spike could be silently dropped.

**The flag reported the last breach, not the tick it fired on.** A spike that
started at tick 24 but was still running at tick 39 was reported as "detected at
tick 39" — which makes a spike that started fifteen ticks ago look like it was
detected fifteen ticks late. The flag now carries the tick where R1 actually
tripped, and the reason says so: *"reached 1.94x the documented urban_high
profile and tripped R1 at tick 26"*.

### Measured, not asserted

`scripts/verify_anomaly_detection.py` drives the live simulator and produces two
numbers, both captured in `docs/evidence/day-02/anomaly-detection-measurement.txt`:

| Measurement | Result |
|:--|:--|
| **False flags in a quiet run** | **0** across 4 stations, 72 observations, max ratio 1.11 |
| **R1 detection delay after an injected spike** | **2 ticks** (tick 24 → 26) |
| **R2 detection delay** | **2 ticks** (2 of 2 region-dhaka stations flagged) |

The script calculates the ratios **independently** of the backend and then
cross-checks the backend's own flags against its result, so this is a
cross-validation rather than the backend agreeing with itself. The backend
reports "tripped R1 at tick 26", the same tick the independent calculation
found.

The thresholds are **proposed, not tuned**. The console says so in a note, and
ADR 005 records it. Day 5 measures whether they are right.

---

## 5. Allocation — the shared dispatch limit

`backend/src/main/java/com/fuelplatform/allocation/AllocationPlanner.java`

### The trap the guide describes

A depot's dispatch allowance is **shared across every fuel and every
destination**. A policy that allocates each fuel independently dispatches more
than the depot is allowed to. The guide's worked example dispatches 4,700 litres
where only 4,000 is allowed.

The fix is a **working copy** of the world. Every proposal reserves its dispatch
allowance, its depot stock and its destination headroom *before* the next line is
planned, so the second line sees the room the first line left.

Verified live by `scripts/verify_dispatch_limit.py`:

```
depot              allowance    proposed     headroom     lines   ok
depot-gazipur      12000        5657         6343         4       yes
depot-patiya       11000        9793         1207         6       yes
```

### Two more traps

**Depot stock is not reduced twice (INV-16).** The simulator already deducts fuel
for a pending shipment, so the reported stock is the truth. Deducting it again
would silently under-order every shipment.

**Destination headroom reserves inbound fuel (INV-18).** A tank with room for
6,000 litres that already has 3,000 on the way has 3,000 of headroom, not 6,000.

### Alternatives carry a reason

An alternative without a reason is decoration, and a fabricated alternative is
worse than none. Every alternative states **why it lost**:

| Code | Meaning |
|:--|:--|
| `LATER_ARRIVAL` | It arrives later than the chosen route |
| `DISPATCH_RESERVED_FOR_MORE_URGENT` | The allowance is reserved for a more urgent need |
| `LOWER_SOURCE_COVER` | It would leave the depot with less cover |
| `NO_ALTERNATIVE_ROUTE` | **No alternative genuinely exists** |

`NO_ALTERNATIVE_ROUTE` is stated explicitly rather than returning an empty list.
An empty list reads as "nothing was considered", and a reader cannot tell it from
a bug.

### Two real bugs the tests caught

**Alternatives were never generated.** The loop broke as soon as the need was
met, so the alternative branch was unreachable. The alternatives are now computed
*after* the primary line is settled, so they describe what a different route
could have done.

**Alternatives were computed but never attached.** The record is immutable, so
the proposal was rebuilt with `withAlternatives(...)`. Forgetting that silently
dropped every alternative — the code compiled, the tests passed, and the panel
was empty.

### The unmet-need codes

| Code | Meaning | Operator's action |
|:--|:--|:--|
| `NO_AVAILABLE_ROUTE` | No route reaches this station | Network problem |
| `ROUTE_CAPACITY_EXCEEDED` | A route exists but the tank cannot take it all | Send it in two shipments |
| `DISPATCH_LIMIT` | Every depot has used its allowance | Wait for the next tick |
| `SOURCE_SHORTFALL` | No depot has the fuel left | Replenish the depot |
| `STATION_CLOSED` | The station is in `OUTAGE` | Nothing can be done |

`ROUTE_CAPACITY_EXCEEDED` was added during Day 2 because the original code used
`NO_AVAILABLE_ROUTE` for both "no route exists" and "the tank is full". Those are
opposite problems, and telling an operator the wrong one sends them looking for
a broken route that does not exist.

### Two more bugs the live run exposed

**One need could produce two explanations.** When a route was refused because the
depot had used its allowance, the code added an unmet need *inside* the loop and
then added another one after the loop. A station appeared twice in the list, once
with `DISPATCH_LIMIT` and once with `NO_AVAILABLE_ROUTE`, and the operator could
not tell which was true. The reason is now worked out once, after the loop.

**A rounding residue was reported as a shortfall.** Quantities are rounded down to
whole litres, so a need of 4253.718 litres is met by 4253 litres with 0.718
litres left over. That residue is not a shortfall, but the code reported it as one,
which filled the operator's list with phantom problems and buried the seven real
ones.

The live run went from **18 unmet needs, 6 of them duplicated, 5 of them phantom**
to **7 unmet needs, none duplicated, none phantom**. The remaining seven are all
genuine `DISPATCH_LIMIT` cases where the depot allowance ran out.

---

## 6. The operator console

`frontend/src/RiskPanel.tsx`

The panel follows three rules:

1. **Unknown is not zero.** A null stockout tick renders as "no stockout
   projected". A zero would claim it runs dry *right now*.
2. **Every number carries its basis.** The rate is shown with its method,
   confidence and sample count, so a profile fallback and a learned EWMA forecast
   do not look alike.
3. **Every proposal carries its reasoning.** The quantity alone would hide the
   constraint that decided it, and the operator has to be able to check it.

The panel shows the summary tiles, the anomaly flags, the station risk table, the
depot risk table, the recommended shipments with their alternatives, and the needs
that could not be met with the reason each one failed.

---

## 7. What was built

| Package | Files | Purpose |
|:--|:--|:--|
| `risk` | `DemandHistoryReader`, `DemandForecaster`, `DemandAnomalyDetector`, `StationRiskProjector`, `DepotRiskProjector`, `RiskEngine`, `DemandForecast`, `StationRisk`, `DepotRisk`, `AnomalyFlag` | The intelligence layer |
| `allocation` | `AllocationPlanner`, `AllocationPlan`, `AllocationProposal` | The feasible proposal heuristic |
| `web` | `RiskController` | The `/api/risk` endpoint |
| `config` | `ForecastProperties`, `RiskProperties`, `SimulatorProfileProperties` | The tunable policy, kept out of the code |
| `frontend` | `RiskPanel.tsx`, `useRisk.ts`, `types.ts` | The console panel |

### The test count

| Suite | Tests |
|:--|--:|
| `AllocationPlannerTest` | 15 |
| `DemandAnomalyDetectorTest` | 14 |
| `DemandForecasterTest` | 13 |
| `StationRiskProjectorTest` | 11 |
| `SnapshotValidatorTest` | 14 |
| `SimulatorClientStaleHeaderTest` | 9 |
| `SimulatorErrorParserTest` | 7 |

**83 tests, all green.** Up from 30 at the end of Day 1.

---

## 8. What Day 2 deliberately did not do

- **No shipment was dispatched.** The allocation output is a proposal. The
  approval path is Day 3.
- **No threshold was tuned.** The ratios are proposed values with a measured
  false-flag count, not values chosen to look good.
- **No state was written to the simulator.** The whole pipeline is a pure
  function of a snapshot.

---

## 9. The evidence

| File | What it shows |
|:--|:--|
| `docs/evidence/day-02/hour-factor-measurement.txt` | The re-measured profile constants, with the false-flag verdict |
| `docs/evidence/day-02/profile-measurement.txt` | The per-hour demand ratios behind the constants |
| `docs/evidence/day-02/anomaly-detection-measurement.txt` | 0 false flags in a quiet run; R1 and R2 within 2 ticks of an injected spike |
| `docs/evidence/day-02/dispatch-limit-check.txt` | Every depot within its shared allowance; route integrity; alternatives |
| `docs/evidence/day-02/risk-response.txt` | The full `/api/risk` response, read as a table |
| `docs/evidence/day-02/console-intelligence-panel.txt` | What the operator actually sees |

Reproduce them with:

```
python scripts/measure_hour_factor_clean.py
python scripts/verify_anomaly_detection.py
python scripts/verify_dispatch_limit.py
python scripts/summarize_risk.py
python scripts/summarize_console.py
```

Detailed schedule: [Implementation guide, Day 2](../../Fuel%20Management%20System%20md%20file/Fuel_Management_Implementation_Guide_v2.md).
