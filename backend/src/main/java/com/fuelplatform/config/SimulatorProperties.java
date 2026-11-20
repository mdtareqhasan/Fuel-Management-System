package com.fuelplatform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Simulator connection settings.
 *
 * <p>The timeouts are deliberate and finite. The guide's starting policy is a 1 second
 * connect timeout and a 2 second response timeout: short enough that a hanging simulator
 * does not pile up threads behind a dashboard full of users, long enough for a normal
 * call to succeed on a loaded machine.
 */
@ConfigurationProperties(prefix = "app.simulator")
public record SimulatorProperties(
        String baseUrl,
        long connectTimeoutMs,
        long responseTimeoutMs) {

    public SimulatorProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://localhost:8000";
        }
        if (connectTimeoutMs <= 0) {
            connectTimeoutMs = 1000;
        }
        if (responseTimeoutMs <= 0) {
            responseTimeoutMs = 2000;
        }
    }
}
