package com.fuelplatform.ingestion;

import com.fuelplatform.config.FreshnessProperties;
import com.fuelplatform.observability.DecisionMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

class RefreshSchedulerTest {

    @Test
    void coalescesBurstOfStreamEventsWithinConfiguredMinimumInterval() {
        SnapshotIngestionService ingestion = mock(SnapshotIngestionService.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RefreshScheduler scheduler = new RefreshScheduler(ingestion,
                new DecisionMetrics(registry), new FreshnessProperties(10, 500, 2000));

        for (int i = 0; i < 20; i++) {
            scheduler.onSimulatorEvent();
        }

        verify(ingestion, times(1)).refresh();
        assertThat(registry.counter("fuel_refresh_triggers_total", "trigger", "event").count()).isEqualTo(1.0);
        assertThat(registry.counter("fuel_refresh_events_coalesced_total").count()).isEqualTo(19.0);
    }
}
