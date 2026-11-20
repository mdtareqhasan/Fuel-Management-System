package com.fuelplatform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Freshness policy. See ADR 004.
 *
 * <p>{@code maxAgeSeconds} is a <strong>wall clock</strong> rule, not a tick rule. That
 * distinction is the whole reason a single policy works in both PAUSED and RUNNING mode:
 * a paused simulator legitimately sits on one tick for minutes, and that is not stale.
 */
@ConfigurationProperties(prefix = "app.freshness")
public record FreshnessProperties(
        long maxAgeSeconds,
        long minRefreshIntervalMs,
        long pollIntervalMs) {

    public FreshnessProperties {
        if (maxAgeSeconds <= 0) {
            maxAgeSeconds = 10;
        }
        if (minRefreshIntervalMs <= 0) {
            minRefreshIntervalMs = 500;
        }
        if (pollIntervalMs <= 0) {
            pollIntervalMs = 2000;
        }
    }
}
