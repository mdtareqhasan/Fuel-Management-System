# ADR 005 — Detect with Rules Before Models

**Status:** Accepted · **Date:** 2026-11-20

## Context

The brief's operating loop is **Observe → Detect → Predict → Decide → Simulate → Act → Monitor → Recover**. Note that *Detect* is a separate step from *Predict*. A system that only predicts does not satisfy the loop.

Detection options:

1. **Rule on observed demand vs the documented profile.** Expected demand per tick = daily profile liters ÷ ticks per day × hour-of-day factor × region factor. Then compute `ratio = observed demand_liters ÷ expected`. Flag when the ratio stays above a threshold for N consecutive ticks.
2. **Statistical detector** (z-score on a rolling window, IQR, STL residual).
3. **Learned detector** (isolation forest, LSTM autoencoder on the demand series).

## Decision

**Ship the rule-based ratio detector first.**

Rule set:

| Rule | Condition | Output |
|---|---|---|
| R1 Station anomaly | ratio ≥ 1.35 for 3 consecutive ticks | `DEMAND_SPIKE_SUSPECTED` |
| R2 Regional anomaly | R1 true for ≥ 2 stations of the same region | `REGIONAL_DEMAND_SPIKE` |
| R3 Recovery | ratio < 1.15 for 3 consecutive ticks | Flag cleared |
| R4 Cause label | An `ACTIVE` `demand_spike` event in `/v1/events` covers the station | `KNOWN_EVENT`, else `UNEXPLAINED` |

The **ratio** form is what makes this work: dividing by the hour-of-day profile normalises away the legitimate morning and evening peaks, so a busy hour is not an anomaly but a genuine 1.8× spike is.

**The detector is advisory.** It adds a flag, a reason and a timestamp to the risk output and raises a metric. It does **not** change any quantity. The only place a flag may influence a number is the optional forecast spike adjustment, and that use must be recorded in the recommendation's reasons.

If the detector throws or lacks data, the risk table continues without flags and shows detector status `UNAVAILABLE`. It never blocks risk output and never blocks approval.

## Alternatives rejected

- **z-score / IQR on a rolling window.** Detects *unusual* rather than *wrong*. During the day-1 probe, stockouts drove `served_liters` to 0 while `demand_liters` stayed normal — a rolling detector on the wrong column would have flagged the stockout as a demand anomaly. A rolling detector also needs enough history, which does not exist on a cold start.
- **Learned detector.** Needs training data that does not exist (every participant sees the same single scenario), cannot be evaluated on day 2, and produces a score that cannot be explained to a judge. ADR 006's rule — detect with rules first — applies.
- **Using the simulator's `demand_multiplier` to detect.** That field *is* the injected multiplier. Reading it would detect the injection rather than the phenomenon, and would silently do nothing against a demand change the simulator did not inject.

## Consequences

- **Positive:** the rule is one sentence, fully explainable, and unit-testable against both a quiet series and a spiked series.
- **Positive:** no training data and no model artefacts, so it works on a cold start and reproduces exactly.
- **Negative:** the thresholds (1.35 / 1.15 / 3 ticks) are **proposed, not tuned**. Day 5 measures the false-flag count in a quiet 192-tick run and the detection delay after an injected spike, then reports both.
- **Negative:** a spike shorter than 3 ticks is not flagged. This limit must be stated in the demo, not hidden.
- **Negative:** detection depends on documented profile constants, which are simulator-specific. They live in a versioned configuration file with a clear "simulator profile constants" header, not scattered through the code.

## Verification

Day 2 delivers the detector unit tests (quiet series → no flag; spiked series → flag). Day 5 delivers the replay measurement: false flags in a quiet 192-tick run, and the number of ticks until R1 and R2 fire after injecting `demand_spike` with multiplier 1.8 on `region-dhaka`.
