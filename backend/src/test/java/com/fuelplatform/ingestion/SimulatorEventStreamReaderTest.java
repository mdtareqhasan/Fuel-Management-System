package com.fuelplatform.ingestion;

import com.fuelplatform.config.StreamProperties;
import com.fuelplatform.observability.DecisionMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Tests for the simulator event stream reader.
 *
 * <p>The stream is a <strong>hint</strong>, so the property that matters is
 * that a malformed or unexpected frame can never corrupt the view. The reader
 * only ever asks for a re-read — it never applies a payload — so the worst a
 * bad frame can do is trigger one extra refresh.
 */
class SimulatorEventStreamReaderTest {

    private SimulatorEventStreamReader reader() {
        return new SimulatorEventStreamReader(
                new StreamProperties(true, 1000, 100, 1000, 10000),
                new SimpleMeterRegistry(),
                mock(RefreshScheduler.class),
                "http://localhost:8000");
    }

    @Test
    @DisplayName("the reader starts in the disconnected state")
    void startsDisconnected() {
        SimulatorEventStreamReader reader = reader();

        assertThat(reader.isConnected()).isFalse();
        assertThat(reader.state()).isEqualTo("DISCONNECTED");
        assertThat(reader.eventsReceived()).isZero();
    }

    @Test
    @DisplayName("a disabled reader reports DISABLED rather than a false outage")
    void disabledReaderReportsDisabled() {
        SimulatorEventStreamReader reader = new SimulatorEventStreamReader(
                new StreamProperties(false, 1000, 100, 1000, 10000),
                new SimpleMeterRegistry(),
                mock(RefreshScheduler.class),
                "http://localhost:8000");

        assertThat(reader.state()).isEqualTo("DISABLED");
    }

    @Test
    @DisplayName("the last error starts as none")
    void startsWithoutAnError() {
        assertThat(reader().lastError()).isEqualTo("none");
    }
}
