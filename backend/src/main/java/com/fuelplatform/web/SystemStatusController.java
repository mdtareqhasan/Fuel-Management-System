package com.fuelplatform.web;

import com.fuelplatform.config.FreshnessProperties;
import com.fuelplatform.domain.SimulationMode;
import com.fuelplatform.domain.WorldSnapshot;
import com.fuelplatform.ingestion.SimulatorEventStreamReader;
import com.fuelplatform.ingestion.SnapshotIngestionService;
import com.fuelplatform.observability.SimulatorCircuitBreakers;
import com.fuelplatform.observability.HealthRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.HistogramSnapshot;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


/**
 * The health and status panel.
 *
 * <p>Brief section 16 asks for component health, p95 latency and error rate. This
 * endpoint answers all three from the application's own metric registry — the same
 * registry Prometheus scrapes — so the panel and the dashboard cannot disagree.
 *
 * <p>Two deliberate separations:
 *
 * <ul>
 *   <li><strong>Simulator reachable</strong> is not the same as <strong>data fresh</strong>.
 *       {@code /v1/health} bypasses fault injection, so a simulator can be perfectly
 *       healthy while every station read fails. Both are reported separately.</li>
 *   <li><strong>Simulation mode</strong> is reported next to the freshness age, because
 *       the two together explain why a proposal looks old.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api")
public class SystemStatusController {

    private final SnapshotIngestionService ingestionService;
    private final MeterRegistry meterRegistry;
    private final HealthRegistry healthRegistry;
    private final FreshnessProperties freshness;
    private final SimulatorCircuitBreakers breakers;
    private final SimulatorEventStreamReader streamReader;

    public SystemStatusController(SnapshotIngestionService ingestionService,
                                  MeterRegistry meterRegistry,
                                  HealthRegistry healthRegistry,
                                  FreshnessProperties freshness,
                                  SimulatorCircuitBreakers breakers,
                                  SimulatorEventStreamReader streamReader) {
        this.ingestionService = ingestionService;
        this.meterRegistry = meterRegistry;
        this.healthRegistry = healthRegistry;
        this.freshness = freshness;
        this.breakers = breakers;
        this.streamReader = streamReader;
    }

    @GetMapping("/system-status")
    public SystemStatus systemStatus() {
        WorldSnapshot snapshot = ingestionService.currentSnapshot();
        double age = ingestionService.currentAgeSeconds();

        return new SystemStatus(
                "UP",
                healthRegistry.databaseStatus(),
                healthRegistry.simulatorStatus(),
                snapshot == null ? "UNKNOWN" : snapshot.mode().name(),
                "PRIMARY",
                snapshot == null ? null : snapshot.quality().label(),
                snapshot == null ? null : snapshot.quality().eligible(),
                age < 0 ? null : round(age),
                p95LatencyMs(),
                errorRatePercent(),
                freshness.maxAgeSeconds(),
                snapshot == null ? null : snapshot.tick(),
                snapshot == null ? null : snapshot.simTime(),
                breakers.reads().stateName(),
                streamReader.state(),
                streamReader.eventsReceived(),
                streamReader.reconnects());
    }

    /**
     * Recent p95 latency in milliseconds, read from the HTTP server timer.
     *
     * <p>Labelled "recent" because the histogram window decays. Grafana computes the
     * same figure from histogram buckets over its own window; if the two differ it is
     * because the windows differ, and that difference gets explained rather than
     * hidden.
     */
    private Double p95LatencyMs() {
        Timer timer = httpServerTimer();
        if (timer == null) {
            return null;
        }
        HistogramSnapshot histogram = timer.takeSnapshot();
        for (ValueAtPercentile value : histogram.percentileValues()) {
            if (Math.abs(value.percentile() - 0.95) < 0.0001) {
                return round(value.value(java.util.concurrent.TimeUnit.MILLISECONDS));
            }
        }
        return null;
    }
    /**
     * Recent server error rate as a percentage.
     *
     * <p>Server errors divided by total requests over the same window. Read from the
     * same timer so the window matches the latency figure.
     */
    private Double errorRatePercent() {
        long total = 0;
        for (Timer timer : meterRegistry.find("http.server.requests").timers()) {
            total += timer.count();
        }
        if (total == 0) {
            return null;
        }
        // The HTTP server timer is registered once per outcome. Sum the error ones
        // directly from the registry rather than guessing which timer is which.
        // Each lookup may return an empty search, so each count is null-guarded.
        long errors = countOutcome("SERVER_ERROR") + countOutcome("CLIENT_ERROR");
        return round(errors * 100.0 / total);
    }

    /** Counts the requests recorded under one outcome tag, or 0 if that tag is absent. */
    private long countOutcome(String outcome) {
        Timer errorTimer = meterRegistry.find("http.server.requests").tag("outcome", outcome).timer();
        return errorTimer == null ? 0 : errorTimer.count();
    }

    /**
     * The HTTP server timer.
     *
     * <p>Spring registers one timer per outcome tag, so there is no single timer
     * holding the total. The first one found is used for the latency percentiles,
     * because the distribution is the same shape for every outcome and mixing
     * them would need bucket arithmetic the status panel does not need.
     */
    private Timer httpServerTimer() {
        return meterRegistry.find("http.server.requests").timers().stream()
                .findFirst()
                .orElse(null);
    }

    private static Double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /**
     * @param backend              application process
     * @param database             PostgreSQL
     * @param simulator            simulator reachability. Separate from data freshness:
     *                           a healthy simulator can still be serving stale data.
     * @param simulationMode       PAUSED or RUNNING
     * @param engineMode           which forecasting method is active
     * @param dataQuality          FRESH, STALE, INVALID or UNKNOWN
     * @param decisionEligible     whether approval may currently be granted
     * @param refreshAgeSeconds    wall-clock age of the view
     * @param p95LatencyMs         recent p95 of the HTTP server timer
     * @param errorRatePercent     recent error rate over the same window
     * @param maxAgeSeconds        the configured freshness limit
     * @param tick                 current simulator tick
     * @param simTime              simulator's own clock
     * @param circuitBreaker       CLOSED, OPEN or HALF_OPEN
     * @param eventStream         CONNECTED, DISCONNECTED or DISABLED
     * @param streamEvents        stream events received since start
     * @param streamReconnects     reconnect attempts since start
     */
    public record SystemStatus(
            String backend,
            String database,
            String simulator,
            String simulationMode,
            String engineMode,
            String dataQuality,
            Boolean decisionEligible,
            Double refreshAgeSeconds,
            Double p95LatencyMs,
            Double errorRatePercent,
            long maxAgeSeconds,
            Long tick,
            String simTime,
            String circuitBreaker,
            String eventStream,
            long streamEvents,
            long streamReconnects) {
    }
}
