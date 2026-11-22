package com.fuelplatform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The approval policy.
 *
 * <p>These numbers come from ADR 004, and the reasoning behind them is measured,
 * not guessed:
 *
 * <ul>
 *   <li>{@code wallClockFreshnessSeconds} — how long a snapshot may be old before
 *       approval is blocked. This is a <strong>wall-clock</strong> rule, so it
 *       behaves identically whether the simulator is paused or running. Ten
 *       seconds is short enough that a human is looking at the current world.</li>
 *   <li>{@code runningProposalExpirySeconds} — how long a proposal stays
 *       actionable in RUNNING mode. At the measured rate of ~5.46 ticks/sec and
 *       ~82 simulated minutes per wall second, a 120 second review spans
 *       ~2.7 simulated days. The proposal is stale long before the human
 *       finishes reading it.</li>
 *   <li>{@code pausedMaxTickLag} — in PAUSED mode the world does not move on its
 *       own, so the only thing that can invalidate a proposal is a deliberate
 *       step. A lag of one tick is the tolerance.</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "app.decision")
public record DecisionProperties(
        double wallClockFreshnessSeconds,
        double runningProposalExpirySeconds,
        long pausedMaxTickLag,
        int maxApprovedQuantityLiters) {

    public DecisionProperties {
        if (wallClockFreshnessSeconds <= 0) {
            wallClockFreshnessSeconds = 10;
        }
        if (runningProposalExpirySeconds <= 0) {
            runningProposalExpirySeconds = 120;
        }
        if (pausedMaxTickLag < 0) {
            pausedMaxTickLag = 1;
        }
        if (maxApprovedQuantityLiters <= 0) {
            maxApprovedQuantityLiters = 50000;
        }
    }
}
