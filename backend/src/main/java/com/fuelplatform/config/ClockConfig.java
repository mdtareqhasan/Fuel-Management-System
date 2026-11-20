package com.fuelplatform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Wall clock as an injected dependency.
 *
 * <p>Nothing in the domain code calls {@code Instant.now()} directly. That keeps the
 * freshness rules testable: a test can move the clock forward and assert that
 * approval becomes blocked, without sleeping.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
