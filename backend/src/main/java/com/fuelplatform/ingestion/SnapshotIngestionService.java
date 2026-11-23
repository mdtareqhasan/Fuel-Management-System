package com.fuelplatform.ingestion;

import com.fuelplatform.domain.DataQuality;
import com.fuelplatform.domain.WorldSnapshot;
import com.fuelplatform.observability.DecisionMetrics;
import com.fuelplatform.observability.HealthRegistry;
import com.fuelplatform.simulator.SimulatorClient;
import com.fuelplatform.simulator.SimulatorClient.Result;
import com.fuelplatform.simulator.dto.SimulatorDtos.Allocation;
import com.fuelplatform.simulator.dto.SimulatorDtos.Depot;
import com.fuelplatform.simulator.dto.SimulatorDtos.DomainEvent;
import com.fuelplatform.simulator.dto.SimulatorDtos.Instance;
import com.fuelplatform.simulator.dto.SimulatorDtos.Region;
import com.fuelplatform.simulator.dto.SimulatorDtos.Route;
import com.fuelplatform.simulator.dto.SimulatorDtos.Station;
import com.fuelplatform.simulator.dto.SimulatorDtos.SupplyArrival;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reads the world from the simulator and publishes a validated snapshot.
 *
 * <p>There is <strong>one</strong> refresh loop for the whole application. A hundred
 * dashboard clients must not create a hundred simulator polling loops. The browser
 * reads the cached, validated view; it never talks to the simulator.
 *
 * <p>Publication is atomic: the new snapshot replaces the old one in a single
 * reference swap, so a reader either sees the whole previous view or the whole new
 * one. There is no window where a reader could see a half-updated world.
 *
 * <p>On any failure the previous valid snapshot is <strong>kept</strong> and its
 * quality is downgraded. The operator keeps seeing the last known good data with an
 * explicit stale warning, and approval is blocked. Losing the view entirely would be
 * worse than showing it with a warning.
 */
@Service
public class SnapshotIngestionService {

    private static final Logger log = LoggerFactory.getLogger(SnapshotIngestionService.class);

    private final SimulatorClient simulatorClient;
    private final SnapshotValidator validator = new SnapshotValidator();
    private final Clock clock;
    private final HealthRegistry healthRegistry;
    private final DecisionMetrics decisionMetrics;

    /** The single source of the current view. Swapped atomically. */
    private final AtomicReference<WorldSnapshot> current = new AtomicReference<>();

    /** The last snapshot that passed validation, kept for display when reads fail. */
    private final AtomicReference<WorldSnapshot> lastValid = new AtomicReference<>();

    private final Counter staleResponses;
    private final Counter rejectedPayloads;
    private final AtomicReference<Double> measuredTicksPerSecond = new AtomicReference<>(0.0);
    private final AtomicReference<Long> lastTickSeen = new AtomicReference<>(0L);
    private final AtomicReference<Instant> lastTickAt = new AtomicReference<>(Instant.now());

    public SnapshotIngestionService(SimulatorClient simulatorClient,
                                    MeterRegistry meterRegistry,
                                    Clock clock,
                                    HealthRegistry healthRegistry,
                                    DecisionMetrics decisionMetrics) {
        this.simulatorClient = simulatorClient;
        this.clock = clock;
        this.healthRegistry = healthRegistry;
        this.decisionMetrics = decisionMetrics;

        this.staleResponses = Counter.builder("fuel_stale_responses_total")
                .description("Simulator responses that carried the X-Simulator-Stale header")
                .register(meterRegistry);
        this.rejectedPayloads = Counter.builder("fuel_rejected_payloads_total")
                .description("Payloads rejected by snapshot validation")
                .register(meterRegistry);
        Gauge.builder("fuel_data_age_seconds", this, SnapshotIngestionService::currentAgeSeconds)
                .description("Wall-clock age of the current validated view")
                .register(meterRegistry);
        Gauge.builder("fuel_sim_ticks_per_second", measuredTicksPerSecond, AtomicReference::get)
                .description("Measured tick rate; SIMULATION_SPEED is only an upper bound")
                .register(meterRegistry);
    }

    /** The current view, or empty if nothing has ever been read successfully. */
    public WorldSnapshot currentSnapshot() {
        return current.get();
    }

    /** How long ago the current view was refreshed, in wall seconds. */
    public double currentAgeSeconds() {
        WorldSnapshot snapshot = current.get();
        if (snapshot == null) {
            return -1;
        }
        return Math.max(0, Duration.between(snapshot.observedAt(), clock.instant()).toMillis() / 1000.0);
    }

    /**
     * Performs one refresh.
     *
     * @return true if a new valid snapshot was published
     */
    public boolean refresh() {
        Instant observedAt = clock.instant();

        Result<Instance> instanceResult = simulatorClient.getInstance();
        if (!instanceResult.isSuccess()) {
            healthRegistry.recordSimulatorReachable(false);
            return degrade(instanceResult.error().toString());
        }
        healthRegistry.recordSimulatorReachable(true);
        Instance instance = instanceResult.body();

        Result<List<Region>> regions = simulatorClient.getRegions();
        Result<List<Depot>> depots = simulatorClient.getDepots();
        Result<List<Station>> stations = simulatorClient.getStations();
        Result<List<Route>> routes = simulatorClient.getRoutes();
        Result<List<SupplyArrival>> supplyArrivals = simulatorClient.getSupplyArrivals();
        Result<List<DomainEvent>> events = simulatorClient.getEvents();
        Result<List<Allocation>> allocations = simulatorClient.getAllocations();

        List<String> readFailures = new ArrayList<>();
        for (Result<?> result : List.of(regions, depots, stations, routes, supplyArrivals, events, allocations)) {
            if (!result.isSuccess()) {
                readFailures.add(result.error().toString());
            }
        }
        if (!readFailures.isEmpty()) {
            decisionMetrics.recordRefreshFailed("partial_read_failure");
            return degrade("simulator read failed: " + String.join("; ", readFailures));
        }

        // Any response carrying the stale header makes the whole view ineligible.
        // One stale component means the view no longer describes a consistent world.
        boolean anyStale = instanceResult.stale()
                || regions.stale() || depots.stale() || stations.stale() || routes.stale()
                || supplyArrivals.stale() || events.stale() || allocations.stale();
        if (anyStale) {
            staleResponses.increment();
        }

        // Read the instance again to learn whether the tick moved during the refresh.
        Result<Instance> instanceAfter = simulatorClient.getInstance();
        Long tickEnd = instanceAfter.isSuccess() ? instanceAfter.body().tick() : instance.tick();
        Long tickStart = instance.tick();

        List<String> validationErrors = validator.validate(
                instance, depots.body(), stations.body(), routes.body(),
                supplyArrivals.body(), events.body(), allocations.body());

        if (!validationErrors.isEmpty()) {
            rejectedPayloads.increment();
            log.warn("snapshot rejected: {}", validationErrors);
            return degrade("simulator payload failed validation: " + String.join("; ", validationErrors));
        }

        WorldSnapshot snapshot = new WorldSnapshot(
                currentRunId(),
                UUID.randomUUID().toString(),
                tickStart,
                tickEnd,
                instance.simTime(),
                observedAt,
                anyStale ? DataQuality.ineligible("simulator reported stale data") : DataQuality.fresh(),
                anyStale ? List.of("simulator reported stale data") : List.of(),
                instance,
                regions.body(),
                depots.body(),
                stations.body(),
                RouteEventOverlay.apply(routes.body(), events.body()),
                supplyArrivals.body(),
                events.body(),
                allocations.body());

        // Publish atomically. A reader sees the old snapshot or the new one, never a mix.
        current.set(snapshot);
        if (!anyStale) {
            lastValid.set(snapshot);
        }
        updateTickRate(tickEnd, observedAt);
        decisionMetrics.recordRefreshSucceeded();
        return true;
    }

    /**
     * Maps a simulator error to a fixed metric label.
     *
     * <p>The label set is fixed. An error message contains a URL and a variable
     * description, and using it as a label would create an unbounded number of
     * time series (invariant INV-27).
     */
    private static String reasonOf(com.fuelplatform.simulator.SimulatorError error) {
        if (error == null) {
            return "unknown";
        }
        return switch (error) {
            case com.fuelplatform.simulator.SimulatorError.Fault ignored -> "simulator_fault";
            case com.fuelplatform.simulator.SimulatorError.Domain ignored -> "domain_error";
            case com.fuelplatform.simulator.SimulatorError.Validation ignored -> "validation_error";
            case com.fuelplatform.simulator.SimulatorError.Unparseable ignored -> "unparseable";
        };
    }

    /**
     * Keeps the last known good view visible but marks it ineligible for decisions.
     *
     * <p>This is the cached read mode. The operator still sees the last valid world,
     * with the reason it can no longer be trusted and the age of the data.
     */
    private boolean degrade(String reason) {
        WorldSnapshot previous = lastValid.get();
        if (previous == null) {
            current.set(null);
            log.warn("refresh failed and no previous snapshot exists: {}", reason);
            return false;
        }
        WorldSnapshot degraded = new WorldSnapshot(
                previous.runId(),
                previous.snapshotId(),
                previous.tickStart(),
                previous.tickEnd(),
                previous.simTime(),
                previous.observedAt(),
                DataQuality.ineligible(reason),
                concat(previous.warnings(), reason),
                previous.instance(),
                previous.regions(),
                previous.depots(),
                previous.stations(),
                previous.routes(),
                previous.supplyArrivals(),
                previous.events(),
                previous.allocations());
        current.set(degraded);
        log.warn("refresh degraded, keeping last valid snapshot: {}", reason);
        return false;
    }

    /**
     * Measures the actual tick rate.
     *
     * <p>{@code SIMULATION_SPEED} is an upper bound the simulator does not always
     * sustain: measured at ~5.46 ticks/sec while configured for 8. Refresh cadences
     * must be derived from this number, not from the configuration.
     */
    private void updateTickRate(Long tick, Instant now) {
        if (tick == null) {
            return;
        }
        Long previousTick = lastTickSeen.get();
        Instant previousAt = lastTickAt.get();
        if (previousTick != null && tick > previousTick) {
            double seconds = Duration.between(previousAt, now).toMillis() / 1000.0;
            if (seconds > 0.5) {
                double rate = (tick - previousTick) / seconds;
                measuredTicksPerSecond.set(rate);
            }
        }
        lastTickSeen.set(tick);
        lastTickAt.set(now);
    }

    /** The application run id. A reset starts a new run so old records stay isolated. */
    private String currentRunId() {
        if (runId == null) {
            runId = UUID.randomUUID().toString();
        }
        return runId;
    }

    private String runId;

    /**
     * Starts a new application run. Called on a detected reset or tick rollback.
     */
    public String startNewRun() {
        runId = UUID.randomUUID().toString();
        log.info("new application run started: {}", runId);
        return runId;
    }

    public String runId() {
        return currentRunId();
    }

    private static List<String> concat(List<String> first, String extra) {
        List<String> combined = new ArrayList<>(first);
        combined.add(extra);
        return combined;
    }

    /** Minimal duration helper to keep the import list short. */
    private static final class Duration {
        static java.time.Duration between(Instant start, Instant end) {
            return java.time.Duration.between(start, end);
        }
    }
}
