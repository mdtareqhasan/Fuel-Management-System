package com.fuelplatform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Event stream policy.
 *
 * <p>The simulator's SSE feed is a <strong>hint</strong>, never the source of
 * truth. Everything the operator sees is re-read from the simulator's REST API
 * after a stream event, so a dropped, duplicated or malformed event can never
 * corrupt the view.
 *
 * <p>That is why the reconnect policy exists at all. If the stream were the
 * truth, a disconnect would mean lost data. Because it is only a hint, a
 * disconnect means the view refreshes a little less promptly — and the REST
 * poll, which always runs, keeps it correct.
 *
 * @param enabled                 whether the stream reader runs at all
 * @param connectTimeoutMs        how long a connection attempt may take
 * @param reconnectDelayMs        the first reconnect delay
 * @param maxReconnectDelayMs     the ceiling the delay backs off to
 * @param outageThresholdMs       how long a disconnect lasts before it is an outage
 */
@ConfigurationProperties(prefix = "app.stream")
public record StreamProperties(
        boolean enabled,
        int connectTimeoutMs,
        long reconnectDelayMs,
        long maxReconnectDelayMs,
        long outageThresholdMs) {

    public StreamProperties {
        if (connectTimeoutMs <= 0) {
            connectTimeoutMs = 1000;
        }
        if (reconnectDelayMs <= 0) {
            reconnectDelayMs = 2000;
        }
        if (maxReconnectDelayMs < reconnectDelayMs) {
            maxReconnectDelayMs = reconnectDelayMs;
        }
        if (outageThresholdMs <= 0) {
            outageThresholdMs = 10000;
        }
    }
}
