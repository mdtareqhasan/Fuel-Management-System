package com.fuelplatform.risk;

import com.fuelplatform.allocation.AllocationPlan;
import com.fuelplatform.allocation.AllocationPlanner;
import com.fuelplatform.allocation.AllocationProposal;
import com.fuelplatform.config.ForecastProperties;
import com.fuelplatform.config.RiskProperties;
import com.fuelplatform.config.SimulatorProfileProperties;
import com.fuelplatform.domain.WorldSnapshot;
import com.fuelplatform.simulator.dto.SimulatorDtos.DemandObservation;
import com.fuelplatform.simulator.dto.SimulatorDtos.SupplyArrival;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the whole intelligence layer over one validated snapshot.
 *
 * <p>The pipeline, in order:
 *
 * <pre>
 *   demand history  →  forecast  →  station risk  →  depot risk  →  allocation plan
 *                          ↘  anomaly detection
 * </pre>
 *
 * <p>Every step is a pure function over the snapshot, so the whole pipeline is
 * reproducible from a snapshot alone. Nothing here writes to the simulator: a plan is
 * a proposal, and only the Day 3 approval path may act on it.
 *
 * <p>The result is cached and recomputed only when the tick or the world has actually
 * changed. Recomputing on every dashboard poll would be wasted work, and at the
 * measured tick rate the world moves fast enough to matter.
 */
@Service
public class RiskEngine {

    private final DemandHistoryReader historyReader;
    private final DemandForecaster forecaster;
    private final DemandAnomalyDetector detector;
    private final StationRiskProjector stationProjector;
    private final DepotRiskProjector depotProjector;
    private final AllocationPlanner planner;

    private final ForecastProperties forecastProperties;
    private final RiskProperties riskProperties;

    private final Counter anomalyCounter;
    private final Counter fallbackCounter;

    /** The last computed result, so repeated reads do not recompute. */
    private volatile RiskAssessment cached;

    /** The supply arrivals as first observed, used to detect delays and shortfalls. */
    private final Map<String, SupplyArrival> firstObservedSupply = new LinkedHashMap<>();

    public RiskEngine(
            DemandHistoryReader historyReader,
            DemandForecaster forecaster,
            DemandAnomalyDetector detector,
            StationRiskProjector stationProjector,
            DepotRiskProjector depotProjector,
            AllocationPlanner planner,
            ForecastProperties forecastProperties,
            RiskProperties riskProperties,
            SimulatorProfileProperties profileProperties,
            MeterRegistry meterRegistry) {

        this.historyReader = historyReader;
        this.forecaster = forecaster;
        this.detector = detector;
        this.stationProjector = stationProjector;
        this.depotProjector = depotProjector;
        this.planner = planner;
        this.forecastProperties = forecastProperties;
        this.riskProperties = riskProperties;

        this.anomalyCounter = Counter.builder("fuel_anomaly_flags_total")
                .description("Demand anomaly flags raised, by severity")
                .register(meterRegistry);
        this.fallbackCounter = Counter.builder("fuel_forecast_fallback_total")
                .description("Forecasts that fell back to the documented profile estimate")
                .register(meterRegistry);
    }

    /**
     * Computes the risk assessment for a snapshot.
     *
     * <p>The result is cached against the snapshot id, so a poll that returns the same
     * snapshot does not recompute the whole pipeline.
     */
    public RiskAssessment assess(WorldSnapshot snapshot) {
        RiskAssessment previous = cached;
        if (previous != null && previous.snapshotId().equals(snapshot.snapshotId())) {
            return previous;
        }

        int tickMinutes = snapshot.tickMinutes();
        int horizonHours = riskProperties.horizonHours();

        // 1. Demand history.
        Map<String, List<DemandObservation>> rawHistory = historyReader.readAll(snapshot.stations());
        Map<String, List<DemandObservationView>> observations =
                historyReader.toObservations(rawHistory);

        // 2. Forecasts.
        Map<String, DemandForecast> forecasts = forecaster.forecastAll(
                rawHistory, snapshot.stations(), snapshot.regions(), tickMinutes, horizonHours);

        for (DemandForecast forecast : forecasts.values()) {
            if (forecast.method().isFallback()) {
                fallbackCounter.increment();
            }
        }

        // 3. Anomaly detection.
        DemandAnomalyDetector.Detection detection = detector.detect(
                observations, snapshot.stations(), snapshot.regions(), snapshot.events(), tickMinutes);

        for (AnomalyFlag flag : detection.flags()) {
            anomalyCounter.increment();
        }

        // 4. Station risk.
        List<StationRisk> stationRisks = stationProjector.projectAll(
                snapshot.tick(), snapshot.stations(), snapshot.routes(), snapshot.allocations(),
                forecasts, tickMinutes);

        // 5. Depot risk.
        rememberSupplyArrivals(snapshot.supplyArrivals());
        List<DepotRisk> depotRisks = depotProjector.projectAll(
                snapshot.tick(), snapshot.depots(), snapshot.stations(), snapshot.routes(),
                snapshot.allocations(), snapshot.supplyArrivals(), firstObservedSupply,
                forecasts, tickMinutes);

        // 6. Allocation plan. This writes nothing.
        AllocationPlan plan = planner.plan(
                snapshot.tick(),
                snapshot.depots(),
                snapshot.stations(),
                snapshot.routes(),
                snapshot.allocations(),
                stationRisks,
                forecasts,
                new AllocationPlanner.Policy(6, 200),
                tickMinutes);

        RiskAssessment assessment = new RiskAssessment(
                snapshot.snapshotId(),
                snapshot.runId(),
                snapshot.tick(),
                snapshot.simTime(),
                snapshot.quality(),
                tickMinutes,
                horizonHours,
                forecasts,
                stationRisks,
                depotRisks,
                detection.flags(),
                detection.detectorStatus(),
                detection.notes(),
                plan.proposals(),
                plan.unmetNeeds(),
                plan.policyVersion());

        cached = assessment;
        return assessment;
    }

    /**
     * Remembers the first observed planned tick and quantity for each supply.
     *
     * <p>The simulator <strong>overwrites</strong> these fields when a delay or
     * shortfall event fires, so the change is only visible by comparing against the
     * first reading. Keeping the first one is what makes the change detectable.
     */
    private void rememberSupplyArrivals(List<SupplyArrival> arrivals) {
        if (arrivals == null) {
            return;
        }
        for (SupplyArrival arrival : arrivals) {
            if (arrival != null && arrival.id() != null) {
                firstObservedSupply.putIfAbsent(arrival.id(), arrival);
            }
        }
    }

    /**
     * The complete intelligence output for one snapshot.
     *
     * @param forecasts      every station and fuel forecast, keyed by station and fuel
     * @param stationRisks   the projected station risk, most urgent first
     * @param depotRisks     the projected depot risk, most urgent first
     * @param anomalyFlags   the advisory anomaly flags
     * @param proposals      the feasible shipment proposals, ranked by urgency
     * @param unmetNeeds     the needs that could not be met, with the reason why
     */
    public record RiskAssessment(
            String snapshotId,
            String runId,
            long tick,
            String simTime,
            com.fuelplatform.domain.DataQuality quality,
            int tickMinutes,
            int horizonHours,
            Map<String, DemandForecast> forecasts,
            List<StationRisk> stationRisks,
            List<DepotRisk> depotRisks,
            List<AnomalyFlag> anomalyFlags,
            String detectorStatus,
            List<String> detectorNotes,
            List<AllocationProposal> proposals,
            List<AllocationPlan.UnmetNeed> unmetNeeds,
            String policyVersion) {

        public RiskAssessment {
            forecasts = forecasts == null ? Map.of() : Map.copyOf(forecasts);
            stationRisks = stationRisks == null ? List.of() : List.copyOf(stationRisks);
            depotRisks = depotRisks == null ? List.of() : List.copyOf(depotRisks);
            anomalyFlags = anomalyFlags == null ? List.of() : List.copyOf(anomalyFlags);
            detectorNotes = detectorNotes == null ? List.of() : List.copyOf(detectorNotes);
            proposals = proposals == null ? List.of() : List.copyOf(proposals);
            unmetNeeds = unmetNeeds == null ? List.of() : List.copyOf(unmetNeeds);
        }

        /** The forecast for one station and fuel, or null. */
        public DemandForecast forecast(String stationId, String fuel) {
            return forecasts.get(DemandForecaster.key(stationId, fuel));
        }

        /** The flags that apply to one station, for the risk table. */
        public List<AnomalyFlag> flagsFor(String stationId) {
            List<AnomalyFlag> matching = new ArrayList<>();
            for (AnomalyFlag flag : anomalyFlags) {
                if (flag.stationId().equals(stationId)) {
                    matching.add(flag);
                }
            }
            return matching;
        }
    }
}
