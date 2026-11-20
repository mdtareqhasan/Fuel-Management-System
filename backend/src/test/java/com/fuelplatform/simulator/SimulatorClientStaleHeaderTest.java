package com.fuelplatform.simulator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the stale-header check.
 *
 * <p>This is one of the highest-value tests in the project. A {@code stale_data} fault
 * makes the simulator return <strong>HTTP 200</strong> with perfectly well-formed JSON
 * and the header {@code X-Simulator-Stale: true}. A client that trusts the status code
 * alone will happily allocate fuel from data the simulator itself says is stale.
 *
 * <p>Measured on 2026-11-20 — see {@code docs/contract-notes.md} §6.
 */
class SimulatorClientStaleHeaderTest {

    @Test
    @DisplayName("a 200 response carrying the stale header is flagged stale")
    void detectsStaleHeader() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Simulator-Stale", "true");

        assertThat(SimulatorClient.isStale(headers)).isTrue();
    }

    @Test
    @DisplayName("the header name is matched case-insensitively, as HTTP requires")
    void detectsStaleHeaderWithDifferentCase() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("x-simulator-stale", "true");

        assertThat(SimulatorClient.isStale(headers)).isTrue();
    }

    @Test
    @DisplayName("the header value is matched case-insensitively")
    void detectsStaleHeaderWithUpperCaseValue() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Simulator-Stale", "TRUE");

        assertThat(SimulatorClient.isStale(headers)).isTrue();
    }

    @Test
    @DisplayName("a normal response is not stale")
    void normalResponseIsNotStale() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Content-Type", "application/json");

        assertThat(SimulatorClient.isStale(headers)).isFalse();
    }

    @Test
    @DisplayName("an explicit false value is not stale")
    void explicitFalseIsNotStale() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Simulator-Stale", "false");

        assertThat(SimulatorClient.isStale(headers)).isFalse();
    }

    @Test
    @DisplayName("no headers at all is not stale")
    void emptyHeadersAreNotStale() {
        assertThat(SimulatorClient.isStale(new HttpHeaders())).isFalse();
    }

    @Test
    @DisplayName("a value with surrounding whitespace is still read as stale")
    void trimsWhitespaceAroundValue() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Simulator-Stale", " true ");

        assertThat(SimulatorClient.isStale(headers)).isTrue();
    }
}
