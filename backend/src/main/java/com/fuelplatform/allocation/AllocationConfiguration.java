package com.fuelplatform.allocation;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the allocation heuristic as a bean.
 *
 * <p>The planner is a pure function with no state, so a single instance serves every
 * request. All the mutable bookkeeping happens in a working copy created per call.
 */
@Configuration
public class AllocationConfiguration {

    @Bean
    public AllocationPlanner allocationPlanner() {
        return new AllocationPlanner();
    }
}
