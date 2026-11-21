package com.fuelplatform.risk;

import java.util.List;

/**
 * A demand anomaly flag.
 *
 * <p>The detector is <strong>advisory</strong> (ADR 005, invariant INV-25). It adds a
 * flag, a reason and a timestamp. It never changes a quantity and never blocks risk
 * output or approval.
 *
 * @param stationId     the station the flag is about
 * @param fuel          the fuel type
 * @param severity      {@code STATION} or {@code REGIONAL}
 * @param cause         {@code KNOWN_EVENT} or {@code UNEXPLAINED}
 * @param ratio         the observed/expected ratio that triggered the rule
 * @param consecutiveTicks  how many consecutive ticks the rule held
 * @param detectedAtTick   the tick at which the rule fired
 * @param reason        one sentence explaining the flag
 */
public record AnomalyFlag(
        String stationId,
        String fuel,
        Severity severity,
        Cause cause,
        double ratio,
        int consecutiveTicks,
        long detectedAtTick,
        String reason) {

    /** How wide the anomaly is. */
    public enum Severity {
        /** One station is off its profile. */
        STATION,
        /** Several stations of one region are off their profile at once. */
        REGIONAL
    }

    /**
     * Whether the cause is known.
     *
     * <p>{@code KNOWN_EVENT} means an ACTIVE {@code demand_spike} event covers the
     * station. {@code UNEXPLAINED} means the demand moved and the simulator has no
     * event for it — which is the more interesting case for an operator.
     */
    public enum Cause {
        KNOWN_EVENT,
        UNEXPLAINED
    }

    /** The label the operator sees. */
    public String label() {
        return severity == Severity.REGIONAL
                ? "REGIONAL_DEMAND_SPIKE"
                : "DEMAND_SPIKE_SUSPECTED";
    }

    public static List<AnomalyFlag> empty() {
        return List.of();
    }
}
