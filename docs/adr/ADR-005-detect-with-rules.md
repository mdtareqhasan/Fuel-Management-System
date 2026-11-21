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

Day 2 delivers the detector unit tests (quiet series → no flag; spiked series →
flag) **and** an early live measurement. Day 5 delivers the full replay
measurement: false flags in a quiet 192-tick run, and the number of ticks until R1
and R2 fire after injecting `demand_spike` with multiplier 1.8 on `region-dhaka`.

### The Day 2 early measurement

`scripts/verify_anomaly_detection.py` drives the live simulator and produces:

| Measurement | Result |
|---|---|
| False flags in a quiet run | **0** across 4 stations, 72 observations, max ratio 1.11 |
| R1 detection delay | **2 ticks** after the spike was injected |
| R2 detection delay | **2 ticks** (2 of 2 region-dhaka stations flagged) |

The script computes the ratios **independently** of the backend and then
cross-checks the backend's own flags against its result, so this is a
cross-validation rather than the backend agreeing with itself. The evidence is in
`docs/evidence/day-02/anomaly-detection-measurement.txt`.

### The profile constants had to be re-measured

The rule is only as good as its denominator, so the profile constants were
**re-measured against the running simulator** rather than transcribed from the
PDF. `scripts/measure_hour_factor_clean.py` resets the simulator, steps a full
day, and refuses to report if any event is still active.

Two findings:

- The guide's highway busy window is **06–09**, six hours wide, not 07–09. With
  the narrow window, hour 6 produced a ratio of exactly 2.0 and a permanent false
  alarm on `station-karnaphuli`.
- 97 of 98 hours match the documented table. The exception is highway hour 5
  (implied 0.817 against a documented 0.75), which gives a ratio of **1.09**
  against a threshold of **1.35**. It is a description inaccuracy, not a
  detection fault — which is exactly why the quiet run produces zero false flags.

The measured constants live in `application.yml` under
`app.simulator-profile.profiles`, versioned `measured-2026-11-21`.

### A measurement that had to be redone

The first hour-factor measurement was taken while a `demand_spike` event was
still `ACTIVE` in region-dhaka. It inflated exactly the two region-dhaka stations
by the spike multiplier and looked like a serious profile mismatch.

`measure_hour_factor_clean.py` exists because of that. It **refuses to report**
when an event is active. A measurement that silently includes an injected fault
is worse than no measurement, because it produces confident wrong numbers.
