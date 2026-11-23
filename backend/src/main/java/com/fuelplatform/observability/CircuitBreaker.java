package com.fuelplatform.observability;

import com.fuelplatform.config.ResilienceProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A circuit breaker for simulator calls.
 *
 * <p>The problem it solves is narrow and specific: when the simulator is
 * unavailable, every read fails after the full response timeout. Without a
 * breaker, a refresh loop that retries would keep paying that timeout forever,
 * and the operator's screen would freeze rather than show a cached view with a
 * warning.
 *
 * <p>The three states and what each one does:
 *
 * <table border="1">
 *   <caption>Circuit breaker states</caption>
 *   <tr><th>State</th><th>What happens</th><th>Why</th></tr>
 *   <tr>
 *     <td><strong>CLOSED</strong></td>
 *     <td>Calls pass through. Failures are counted.</td>
 *     <td>Normal operation. The simulator is reachable.</td>
 *   </tr>
 *   <tr>
 *     <td><strong>OPEN</strong></td>
 *     <td>Calls are refused immediately without touching the network.</td>
 *     <td>The simulator is known to be down. Paying the timeout again buys
 *         nothing and delays the operator's view of the outage.</td>
 *   </tr>
 *   <tr>
 *     <td><strong>HALF_OPEN</strong></td>
 *     <td>A limited number of probes is allowed through.</td>
 *     <td>Something may have recovered, but it has to be proven, not assumed.</td>
 *   </tr>
 * </table>
 *
 * <p><strong>Only availability failures count.</strong> A domain error — the
 * simulator saying "no such station" — is a correct answer from a reachable
 * simulator and must not open the breaker. An injected fault (HTTP 503) is the
 * opposite: the simulator is down, and that is exactly what the breaker is for.
 *
 * <p><strong>Reads and writes have separate breakers.</strong> A write that
 * times out is not evidence that the simulator is down for reads; it may be one
 * slow depot. Sharing one breaker would let a single slow write stop the whole
 * refresh loop.
 *
 * <p>This class is deliberately free of I/O so it can be tested with a fake
 * clock and no simulator at all.
 */
public class CircuitBreaker {

    /** The states a breaker can be in. */
    public enum State {
        /** Calls pass through. */
        CLOSED,
        /** Calls are refused immediately. */
        OPEN,
        /** A limited number of probes is allowed through. */
        HALF_OPEN
    }

    private final String channel;
    private final Clock clock;
    private final int failureThreshold;
    private final int openSeconds;
    private final int halfOpenProbes;

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicInteger halfOpenPermits = new AtomicInteger();
    private final AtomicLong openedAt = new AtomicLong();
    private final AtomicLong rejectedCalls = new AtomicLong();

    private final Counter rejectedCounter;
    private final Counter openedCounter;

    /**
     * @param channel   "read" or "write". It is a metric label, so it comes from a
     *                  fixed set and never from a request.
     * @param clock     the wall clock. A fake one makes the whole cycle testable.
     */
    public CircuitBreaker(String channel,
                           ResilienceProperties properties,
                           MeterRegistry meterRegistry,
                           Clock clock) {
        this.channel = channel;
        this.clock = clock;
        this.failureThreshold = properties.failureThreshold();
        this.openSeconds = properties.openSeconds();
        this.halfOpenProbes = properties.halfOpenProbes();

        this.rejectedCounter = Counter.builder("fuel_circuit_breaker_rejections_total")
                .description("Calls refused because the circuit breaker was open")
                .tag("channel", channel)
                .register(meterRegistry);
        this.openedCounter = Counter.builder("fuel_circuit_breaker_opened_total")
                .description("Times the circuit breaker transitioned to OPEN")
                .tag("channel", channel)
                .register(meterRegistry);

        Gauge.builder("fuel_circuit_breaker_open", this, breaker -> breaker.isOpen() ? 1.0 : 0.0)
                .description("1 when the circuit breaker is OPEN and refusing calls")
                .tag("channel", channel)
                .register(meterRegistry);
        Gauge.builder("fuel_circuit_breaker_state_code", this, breaker -> breaker.stateCode())
                .description("Circuit breaker state as a number: 0 CLOSED, 0.5 HALF_OPEN, 1 OPEN")
                .tag("channel", channel)
                .register(meterRegistry);
        Gauge.builder("fuel_circuit_breaker_rejected_calls", rejectedCalls, AtomicLong::get)
                .description("Total calls refused by the circuit breaker")
                .tag("channel", channel)
                .register(meterRegistry);
    }

    /**
     * Whether a call may proceed.
     *
     * <p>In HALF_OPEN state the number of permits is limited, so a recovering
     * simulator is probed a bounded number of times rather than being flooded
     * by the refresh loop.
     */
    public boolean allowCall() {
        State current = currentState();
        if (current == State.CLOSED) {
            return true;
        }
        if (current == State.OPEN) {
            rejectedCalls.incrementAndGet();
            rejectedCounter.increment();
            return false;
        }
        // HALF_OPEN. Take a permit if one is left.
        int permits = halfOpenPermits.getAndUpdate(existing -> Math.max(0, existing - 1));
        if (permits > 0) {
            return true;
        }
        rejectedCalls.incrementAndGet();
        rejectedCounter.increment();
        return false;
    }

    /** Records a successful call. */
    public void recordSuccess() {
        consecutiveFailures.set(0);
        halfOpenPermits.set(halfOpenProbes);
        state.set(State.CLOSED);
    }

    /**
     * Records an availability failure.
     *
     * <p>A domain error does not come here — it is a correct answer from a
     * reachable simulator.
     */
    public void recordFailure() {
        if (state.get() == State.HALF_OPEN) {
            // A probe failed. Back to open, and wait out the whole window.
            open();
            return;
        }
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= failureThreshold && state.get() == State.CLOSED) {
            open();
        }
    }

    private void open() {
        state.set(State.OPEN);
        openedAt.set(clock.millis());
        halfOpenPermits.set(0);
        openedCounter.increment();
    }

    /** The current state, applying the open-to-half-open transition if it is due. */
    public State currentState() {
        State current = state.get();
        if (current != State.OPEN) {
            return current;
        }
        long elapsedSeconds = (clock.millis() - openedAt.get()) / 1000;
        if (elapsedSeconds >= openSeconds) {
            state.compareAndSet(State.OPEN, State.HALF_OPEN);
            halfOpenPermits.set(halfOpenProbes);
            return state.get();
        }
        return State.OPEN;
    }

    /** The state name, for the status panel. */
    public String stateName() {
        return currentState().name();
    }

    /** Whether the breaker would refuse a call right now. */
    public boolean isOpen() {
        return currentState() == State.OPEN;
    }

    /**
     * The state as a number, for the metric.
     *
     * <p>CLOSED is 0, HALF_OPEN is 0.5 and OPEN is 1. A gauge cannot carry a
     * string, and encoding the state as a number keeps the dashboard able to
     * show the transition rather than only the two end states.
     */
    public double stateCode() {
        return switch (currentState()) {
            case CLOSED -> 0.0;
            case HALF_OPEN -> 0.5;
            case OPEN -> 1.0;
        };
    }

    /** The number of consecutive failures recorded. */
    public int consecutiveFailures() {
        return consecutiveFailures.get();
    }

    /** The channel this breaker guards. */
    public String channel() {
        return channel;
    }

    /** Resets the breaker. Used by tests and by an operator-triggered recovery. */
    public void reset() {
        consecutiveFailures.set(0);
        halfOpenPermits.set(halfOpenProbes);
        state.set(State.CLOSED);
    }
}
