package com.fuelplatform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Risk projection and anomaly detection policy.
 *
 * <p>The thresholds live here, not inside the detector, because they are
 * <strong>proposed and untuned</strong>. ADR 005 says so explicitly: the rule is one
 * sentence, but the numbers are guesses until a replay measures the false-flag count and
 * the detection delay.
 */
@ConfigurationProperties(prefix = "app.risk")
public record RiskProperties(
        int horizonHours,
        double criticalHours,
        int anomalyConsecutiveTicks,
        double anomalyRatioThreshold,
        double recoveryRatioThreshold,
        int regionalStationThreshold) {

    public RiskProperties {
        if (horizonHours <= 0) {
            horizonHours = 8;
        }
        if (criticalHours <= 0) {
            criticalHours = 2;
        }
        if (anomalyConsecutiveTicks <= 0) {
            anomalyConsecutiveTicks = 3;
        }
        if (anomalyRatioThreshold <= 1.0) {
            anomalyRatioThreshold = 1.35;
        }
        if (recoveryRatioThreshold <= 0) {
            recoveryRatioThreshold = 1.15;
        }
        if (regionalStationThreshold <= 0) {
            regionalStationThreshold = 2;
        }
    }
}
