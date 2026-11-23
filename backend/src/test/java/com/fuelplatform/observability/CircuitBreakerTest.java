package com.fuelplatform.observability;

import com.fuelplatform.config.ResilienceProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the circuit breaker.
 *
 * <p>The breaker is the piece of Day 4 that protects the operator's screen when
 * the simulator is down. These tests use a fake clock, so the whole open →
 * half-open → closed cycle is exercised in microseconds and without a running
 * simulator.
 */
class CircuitBreakerTest {

    /** A clock the test moves by hand. */
    private static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-11-22T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private MovableClock clock;
    private CircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        clock = new MovableClock();
        breaker = new CircuitBreaker(
                "read",
                new ResilienceProperties(3, 30, 1, 1, 250),
                new SimpleMeterRegistry(),
                clock);
    }

    @Test
    @DisplayName("a fresh breaker is closed and lets calls through")
    void startsClosed() {
        assertThat(breaker.stateName()).isEqualTo("CLOSED");
        assertThat(breaker.isOpen()).isFalse();
        assertThat(breaker.allowCall()).isTrue();
    }

    @Test
    @DisplayName("the breaker opens only after the configured number of consecutive failures")
    void opensAfterThreshold() {
        breaker.recordFailure();
        breaker.recordFailure();

        assertThat(breaker.stateName()).isEqualTo("CLOSED");
        assertThat(breaker.allowCall()).isTrue();

        breaker.recordFailure();

        assertThat(breaker.stateName()).isEqualTo("OPEN");
        assertThat(breaker.isOpen()).isTrue();
    }

    @Test
    @DisplayName("an open breaker refuses calls without touching the network")
    void openBreakerRefusesCalls() {
        for (int i = 0; i < 3; i++) {
            breaker.recordFailure();
        }

        assertThat(breaker.allowCall()).isFalse();
        assertThat(breaker.allowCall()).isFalse();
    }

    @Test
    @DisplayName("a success resets the failure count")
    void successResetsTheFailureCount() {
        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordSuccess();

        breaker.recordFailure();
        breaker.recordFailure();

        assertThat(breaker.stateName()).isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("the breaker stays open until the open window has passed")
    void staysOpenUntilTheWindowPasses() {
        for (int i = 0; i < 3; i++) {
            breaker.recordFailure();
        }
        assertThat(breaker.stateName()).isEqualTo("OPEN");

        clock.advance(Duration.ofSeconds(29));

        assertThat(breaker.stateName()).isEqualTo("OPEN");
        assertThat(breaker.allowCall()).isFalse();
    }

    @Test
    @DisplayName("after the window the breaker half-opens and allows one probe")
    void halfOpensAfterTheWindow() {
        for (int i = 0; i < 3; i++) {
            breaker.recordFailure();
        }
        clock.advance(Duration.ofSeconds(30));

        assertThat(breaker.stateName()).isEqualTo("HALF_OPEN");
        assertThat(breaker.allowCall()).isTrue();
        // Only one probe is allowed. A second caller is refused.
        assertThat(breaker.allowCall()).isFalse();
    }

    @Test
    @DisplayName("a successful probe closes the breaker")
    void successfulProbeClosesTheBreaker() {
        for (int i = 0; i < 3; i++) {
            breaker.recordFailure();
        }
        clock.advance(Duration.ofSeconds(30));
        assertThat(breaker.allowCall()).isTrue();

        breaker.recordSuccess();

        assertThat(breaker.stateName()).isEqualTo("CLOSED");
        assertThat(breaker.allowCall()).isTrue();
    }

    @Test
    @DisplayName("a failed probe reopens the breaker for the whole window")
    void failedProbeReopensTheBreaker() {
        for (int i = 0; i < 3; i++) {
            breaker.recordFailure();
        }
        clock.advance(Duration.ofSeconds(30));
        assertThat(breaker.allowCall()).isTrue();

        breaker.recordFailure();

        assertThat(breaker.stateName()).isEqualTo("OPEN");
        assertThat(breaker.allowCall()).isFalse();
    }

    @Test
    @DisplayName("the state code distinguishes closed, half-open and open")
    void stateCodeDistinguishesTheStates() {
        assertThat(breaker.stateCode()).isEqualTo(0.0);

        for (int i = 0; i < 3; i++) {
            breaker.recordFailure();
        }
        assertThat(breaker.stateCode()).isEqualTo(1.0);

        clock.advance(Duration.ofSeconds(30));
        assertThat(breaker.stateCode()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("the retry count is capped so a retry policy cannot outlive the freshness window")
    void capsTheRetryCount() {
        ResilienceProperties capped = new ResilienceProperties(3, 30, 1, 99, 250);

        assertThat(capped.maxRetries()).isEqualTo(3);
    }
}
