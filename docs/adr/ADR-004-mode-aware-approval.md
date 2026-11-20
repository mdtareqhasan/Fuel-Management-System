# ADR 004 — Mode-Aware Approval (The Proposal Is an Envelope)

**Status:** Accepted · **Date:** 2026-11-20

## Context

The simulator runs in two modes. `PAUSED` freezes the clock and is driven by `POST /admin/step`. `RUNNING` advances the clock continuously while the operator is still reading the screen.

A naive staleness rule — "reject the proposal if it is more than one tick old" — **can never be satisfied in RUNNING mode.** This is not a tuning problem; it is arithmetic.

Measured on this machine (`docs/evidence/day-01/simulator-speed.txt`), with `SIMULATION_SPEED=8` and `TICK_MINUTES=15`:

| Metric | Value |
|---|---|
| Measured tick rate | **5.46 ticks/sec** (configured speed was 8; the simulator does not sustain it) |
| Effective simulated time per wall second | 5.46 × 15 = **~82 simulated minutes/sec** |
| Simulated time in a 30-second review | **~41 simulated hours** |
| Ticks in a 30-second review | ~164 |

At the documented default of 120 simulated minutes per wall second, the same review spans **60 simulated hours**. A proposal reviewed for 30 seconds is two simulated days out of date.

So the approval rule has to be different in the two modes, and the difference has to be driven by measured behaviour rather than by a guess.

## Decision

**A proposal is a reviewed envelope, not a frozen order.**

At approval time the backend re-reads the relevant simulator resources and revalidates the reviewed line against the fresh state:

| Fresh check result | Action | Operator sees |
|---|---|---|
| Still feasible, need still positive | Send the reviewed quantity | "Approved as reviewed" |
| Feasible only for a smaller quantity | **Send the smaller quantity, never more** | "Approved with reduction" + reason |
| Route disrupted, station OUTAGE, depot closed, best source/route changed | **Send nothing**, mark line `CHANGED` | "Re-review required" + the difference |
| Fresh read stale, invalid, or circuit open | **Send nothing**, keep the proposal | "Approval blocked" + specific reason |

Supporting rules:

- **Quantity may fall but never rise.** The operator reviewed an *upper bound*. Rising above it would mean shipping a quantity no human agreed to.
- **PAUSED:** the proposal must belong to the current tick; if the tick moved, regenerate it.
- **RUNNING:** tick lag is displayed, not blocked on. The fresh-read revalidation is the control.
- **Always blocking, in both modes:** stale header, invalid payload, unknown write outcome, reset detected, open circuit.
- **Wall-clock freshness limit:** 10 seconds since the last valid refresh, in both modes. This is a wall-clock rule, not a tick rule, so it behaves identically while paused and running.
- **Proposal expiry (RUNNING):** configurable, starting at 120 wall seconds. The review dialog warns when the clock is outpacing the review and offers *Pause for review*.

The idempotency key and the exact request body are fixed **inside the approval transaction, after revalidation**, so every retry reuses that body.

## Alternatives rejected

- **Fixed tick-staleness threshold.** Proved unsatisfiable in RUNNING mode by the arithmetic above. It would block every approval during a live demo.
- **Freeze the simulator on approval.** Convenient, but it hides the real problem — an operator reviewing against a moving world is exactly what happens in a real operations centre.
- **Re-freeze and re-review on every change.** Correct but unusable: at ~41 simulated hours per review, the proposal would be stale before the operator finished reading it.
- **Ship the stale quantity anyway.** Violates ADR 003. The human reviewed a different world than the one being shipped to.

## Consequences

- **Positive:** approval works in both modes without ever shipping more than the operator agreed to.
- **Positive:** the "re-review required" path is honest — it tells the operator the world changed, instead of silently adjusting their decision.
- **Negative:** some approvals are blocked, which will look like friction in a demo. Mitigated by the demo console's *Pause for review* button, which is an explicit, audited operator action.
- **Negative:** the revalidation read adds latency to approval. Mitigated by reusing the cached validated snapshot where the freshness rule allows, and only re-reading when the envelope is at risk.

## Verification

Day 3 tests three cases: approve in PAUSED at the same tick; approve after stepping eight ticks; approve in RUNNING after a route disruption removes the route. **The third case must send nothing.**
