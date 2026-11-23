package com.fuelplatform.simulator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the transport failure classifier.
 *
 * <p>This classification is what keeps a slow simulator from being mistaken
 * for a dead one. Getting it wrong is not academic: a timeout that opened the
 * breaker took the whole integration offline for 30 seconds and broke two
 * Day 3 end-to-end checks.
 */
class TransportFailureClassifierTest {

    @Test
    @DisplayName("a refused connection means the simulator is unavailable")
    void refusedConnectionIsUnavailable() {
        assertThat(TransportFailureClassifier.isUnavailable(new ConnectException("refused"))).isTrue();
    }

    @Test
    @DisplayName("an unknown host means the simulator is unavailable")
    void unknownHostIsUnavailable() {
        assertThat(TransportFailureClassifier.isUnavailable(new UnknownHostException("simulator-api"))).isTrue();
    }

    @Test
    @DisplayName("a read timeout does NOT mean the simulator is unavailable")
    void readTimeoutIsNotUnavailable() {
        assertThat(TransportFailureClassifier.isUnavailable(new SocketTimeoutException("read timed out"))).isFalse();
    }

    @Test
    @DisplayName("an HTTP timeout does NOT mean the simulator is unavailable")
    void httpTimeoutIsNotUnavailable() {
        assertThat(TransportFailureClassifier.isUnavailable(new HttpTimeoutException("request timed out"))).isFalse();
    }

    @Test
    @DisplayName("a cancelled request does NOT mean the simulator is unavailable")
    void cancelledRequestIsNotUnavailable() {
        assertThat(TransportFailureClassifier.isUnavailable(new InterruptedIOException("Request cancelled"))).isFalse();
    }

    @Test
    @DisplayName("the cause chain is followed, so a wrapped connect failure is still found")
    void followsTheCauseChain() {
        IOException wrapped = new IOException("read failed",
                new ConnectException("Connection refused"));

        assertThat(TransportFailureClassifier.isUnavailable(wrapped)).isTrue();
    }

    @Test
    @DisplayName("a timeout wrapped in another exception is still a timeout")
    void timeoutInACauseChainIsStillATimeout() {
        IOException wrapped = new IOException("read failed",
                new InterruptedIOException("Request cancelled"));

        assertThat(TransportFailureClassifier.isUnavailable(wrapped)).isFalse();
    }

    @Test
    @DisplayName("an unknown failure is not treated as an outage")
    void unknownFailureIsNotAnOutage() {
        assertThat(TransportFailureClassifier.isUnavailable(new IllegalStateException("body did not map")))
                .isFalse();
    }

    @Test
    @DisplayName("the JDK's 'Request cancelled' wording is recognised as a timeout")
    void recognisesTheJdkTimeoutWording() {
        IOException cancelled = new IOException("I/O error on GET request for \"http://simulator-api:8000/v1/instance\": Request cancelled");

        assertThat(TransportFailureClassifier.isUnavailable(cancelled)).isFalse();
    }

    @Test
    @DisplayName("a Spring-wrapped timeout with no further cause is not an outage")
    void springWrappedTimeoutIsNotAnOutage() {
        RuntimeException wrapped = new RuntimeException(
                "I/O error on GET request: Request cancelled");

        assertThat(TransportFailureClassifier.isUnavailable(wrapped)).isFalse();
    }
}
