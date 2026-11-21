package com.fuelplatform.risk;

/**
 * How a demand rate was produced.
 *
 * <p>The method is always reported next to the number, because a rate without a method
 * cannot be defended to an operator. A {@code PROFILE_ESTIMATE} rate is a fallback and
 * is labelled {@code LOW_DATA} — it is not silently mixed in with measured rates.
 */
public enum ForecastMethod {

    /** An exponentially weighted moving average over the recent observed window. */
    EWMA,

    /** The last complete observed demand rate, used as the baseline to beat. */
    NAIVE,

    /**
     * The documented profile estimate, used when there is not enough history.
     *
     * <p>Always carries {@code LOW_DATA} quality so the operator knows the number is a
     * profile assumption rather than a measurement.
     */
    PROFILE_ESTIMATE;

    /** Whether this method is a fallback that must be shown as low data. */
    public boolean isFallback() {
        return this == PROFILE_ESTIMATE;
    }
}
