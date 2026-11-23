package com.fuelplatform.observability;

import com.fuelplatform.config.ResilienceProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Holds the simulator's circuit breakers.
 *
 * <p>Reads and writes get separate breakers, because a failure in one does not
 * imply a failure in the other:
 *
 * <ul>
 *   <li>A write that times out may be one slow depot, or one simulator write
 *       that got stuck. It says nothing about whether reads still work.</li>
 *   <li>A read that fails repeatedly says the simulator is down for reading. If
 *       writes were blocked by the same breaker, the operator could not even
 *       record what happened.</li>
 * </ul>
 *
 * <p>Both breakers are exposed to the status panel. The panel shows the read
 * breaker, because that is the one that decides whether the operator is looking
 * at a live world.
 */
@Component
public class SimulatorCircuitBreakers {

    private final CircuitBreaker reads;
    private final CircuitBreaker writes;

    public SimulatorCircuitBreakers(ResilienceProperties properties,
                                    MeterRegistry meterRegistry,
                                    Clock clock) {
        this.reads = new CircuitBreaker("read", properties, meterRegistry, clock);
        this.writes = new CircuitBreaker("write", properties, meterRegistry, clock);
    }

    /** The breaker that guards simulator reads. */
    public CircuitBreaker reads() {
        return reads;
    }

    /** The breaker that guards simulator writes. */
    public CircuitBreaker writes() {
        return writes;
    }
}
