package com.fuelplatform.simulator;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Metrics for simulator calls.
 *
 * <p>One timer per (endpoint, outcome), so a dashboard can show success rate and
 * latency separately. The label set is fixed and small: {@code endpoint} and
 * {@code outcome} only. No id ever becomes a label (invariant INV-27), because a
 * label per allocation or per station would create an unbounded number of time
 * series in Prometheus.
 *
 * <p>Lives in the {@code simulator} package rather than {@code observability} so
 * that the client can record its own calls without a dependency cycle.
 */
@Component
public class SimulatorMetrics {

    private final MeterRegistry meterRegistry;
    private final Map<String, Timer> timers = new ConcurrentHashMap<>();
    private final Counter simulatorCalls;

    public SimulatorMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        this.simulatorCalls = Counter.builder("fuel_simulator_calls_total")
                .description("Total simulator calls, by outcome")
                .register(meterRegistry);
    }

    /** Records a successful, non-stale call. */
    public void recordSuccess(String endpoint, long durationNanos) {
        record(endpoint, "success", durationNanos);
    }

    /** Records a call that returned 200 but carried the stale header. */
    public void recordStale(String endpoint, long durationNanos) {
        record(endpoint, "stale", durationNanos);
    }

    /** Records a failure. The outcome label is one of the fixed set below. */
    public void recordFailure(String endpoint, String outcome, long durationNanos) {
        record(endpoint, outcome, durationNanos);
    }

    private void record(String endpoint, String outcome, long durationNanos) {
        simulatorCalls.increment();
        timers.computeIfAbsent(endpoint + ":" + outcome, key -> Timer.builder("fuel_simulator_call_seconds")
                        .description("Simulator call duration by endpoint and outcome")
                        .tag("endpoint", endpoint)
                        .tag("outcome", outcome)
                        .publishPercentiles(0.5, 0.95)
                        .register(meterRegistry))
                .record(durationNanos, TimeUnit.NANOSECONDS);
    }
}
