package com.fuelplatform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Resilience policy for calls to the simulator.
 *
 * <p>Three separate mechanisms, each with one job. Conflating them is the usual
 * mistake, so they are configured separately and do the different things:
 *
 * <table border="1">
 *   <caption>Timeout, retry and circuit breaker</caption>
 *   <tr><th>Mechanism</th><th>Question it answers</th><th>Failure mode it prevents</th></tr>
 *   <tr>
 *     <td><strong>Timeout</strong></td>
 *     <td>How long may one attempt take?</td>
 *     <td>A thread blocked forever on an unanswered request</td>
 *   </tr>
 *   <tr>
 *     <td><strong>Retry</strong></td>
 *     <td>May the same request be attempted again?</td>
 *     <td>A single transient blip losing the whole refresh</td>
 *   </tr>
 *   <tr>
 *     <td><strong>Circuit breaker</strong></td>
 *     <td>Should we try at all?</td>
 *     <td>Thousands of doomed calls, each paying the full timeout</td>
 *   </tr>
 * </table>
 *
 * <p>The retry count is deliberately small. The simulator's own baseline response
 * time is ~2.05 s, so three attempts at a 2 s deadline already span ~6 s of wall
 * clock — longer than the 10 s freshness limit. A retry policy that outlives the
 * freshness window would hand the operator a view that expired while the retry
 * was still running.
 *
 * @param failureThreshold   consecutive failures that open the breaker
 * @param openSeconds        how long the breaker stays open before it probes again
 * @param halfOpenProbes     how many probes the half-open state allows
 * @param maxRetries         additional attempts after the first one, for reads
 * @param retryBackoffMs     base backoff between read retries
 */
@ConfigurationProperties(prefix = "app.resilience")
public record ResilienceProperties(
        int failureThreshold,
        int openSeconds,
        int halfOpenProbes,
        int maxRetries,
        long retryBackoffMs) {

    public ResilienceProperties {
        if (failureThreshold <= 0) {
            failureThreshold = 3;
        }
        if (openSeconds <= 0) {
            openSeconds = 30;
        }
        if (halfOpenProbes <= 0) {
            halfOpenProbes = 1;
        }
        if (maxRetries < 0) {
            maxRetries = 1;
        }
        if (maxRetries > 3) {
            // A retry policy that outlives the freshness window is worse than no
            // retry at all: the operator gets a view that expired while the
            // retries were still running.
            maxRetries = 3;
        }
        if (retryBackoffMs < 0) {
            retryBackoffMs = 250;
        }
    }
}
