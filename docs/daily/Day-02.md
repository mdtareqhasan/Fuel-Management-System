# Phase 01 · Day 2 — Intelligence & Risk

**Target:** Friday, 20 November 2026 · **Status:** Planned.

| Milestone | Focus areas | Intended result |
|:--|:--|:--|
| Intelligence & risk | Demand history, forecast, station/depot projection, anomaly rules, feasible proposals | Explainable risk view and feasible recommendations without changing simulator state |

## Planned work

- Fetch bounded demand history; deduplicate and sort observations from oldest to newest.
- Build a baseline and EWMA forecast with a cold-start fallback.
- Project station stockout risk and depot supply windows using simulator tick duration.
- Add an advisory demand anomaly rule and measure its detection delay.
- Rank feasible allocations under route, depot dispatch, and station capacity constraints; provide alternatives where possible.

## Acceptance evidence

- Hand-checked station and depot calculations.
- Quiet demand series produces no anomaly flag; injected spike is flagged with measured delay.
- Recommendation sample includes constraints, reasons, and alternatives.
- Unit and invariant checks saved with the implementation.

Detailed schedule: [Implementation guide, Day 2](../../Fuel%20Management%20System%20md%20file/Fuel_Management_Implementation_Guide_v2.md).
