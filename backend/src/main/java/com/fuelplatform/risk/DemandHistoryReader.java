package com.fuelplatform.risk;

import com.fuelplatform.simulator.SimulatorClient;
import com.fuelplatform.simulator.SimulatorClient.Result;
import com.fuelplatform.simulator.dto.SimulatorDtos.DemandObservation;
import com.fuelplatform.simulator.dto.SimulatorDtos.Station;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fetches the bounded demand history that the forecast needs.
 *
 * <p>Demand history is read <strong>per station</strong>, because the simulator's
 * endpoint is keyed by {@code station_id}. Four stations means four calls per refresh.
 *
 * <p>The bound matters. The guide warns that one {@code limit} is shared across all
 * three fuels, so a limit of 200 rows is only about 66 ticks of one station's history.
 * That is why the rows are split by fuel before anything is counted (INV-21).
 *
 * <p>A failed read is not fatal. The forecast falls back to the documented profile
 * estimate and is labelled {@code LOW_DATA}, because a missing history is not a reason
 * to lose the risk table entirely.
 */
@Service
public class DemandHistoryReader {

    private static final Logger log = LoggerFactory.getLogger(DemandHistoryReader.class);

    /** Rows fetched per station per refresh. Shared across all three fuels. */
    private static final int LIMIT = 600;

    private final SimulatorClient simulatorClient;
    private final Counter fallbackCounter;

    public DemandHistoryReader(SimulatorClient simulatorClient, MeterRegistry meterRegistry) {
        this.simulatorClient = simulatorClient;
        this.fallbackCounter = Counter.builder("fuel_forecast_fallback_total")
                .description("Forecasts that fell back to the documented profile estimate")
                .register(meterRegistry);
    }

    /**
     * Reads the demand history for every station.
     *
     * @return raw rows per station id, exactly as the simulator returned them
     */
    public Map<String, List<DemandObservation>> readAll(List<Station> stations) {
        Map<String, List<DemandObservation>> byStation = new LinkedHashMap<>();
        for (Station station : stations) {
            Result<List<DemandObservation>> result = simulatorClient.getDemandHistory(station.id(), LIMIT);
            if (result.isSuccess()) {
                byStation.put(station.id(), result.body());
            } else {
                // The forecast falls back to the profile for this station. It is
                // recorded so the operator can see the forecast is an assumption.
                fallbackCounter.increment();
                log.warn("demand history read failed for {}: {}", station.id(), result.error());
                byStation.put(station.id(), List.of());
            }
        }
        return byStation;
    }

    /**
     * Splits the raw rows into the cleaned, sorted per-station series.
     *
     * <p>This is where INV-19, INV-20 and INV-21 are applied: demand instead of served,
     * deduplicated, split by fuel and sorted ascending by tick.
     */
    public Map<String, List<DemandObservationView>> toObservations(
            Map<String, List<DemandObservation>> rawByStation) {

        Map<String, List<DemandObservationView>> byStation = new LinkedHashMap<>();
        for (Map.Entry<String, List<DemandObservation>> entry : rawByStation.entrySet()) {
            Map<String, List<DemandObservationView>> byFuel =
                    DemandForecaster.splitByFuel(entry.getValue(), entry.getKey());
            List<DemandObservationView> merged = new java.util.ArrayList<>();
            for (List<DemandObservationView> series : byFuel.values()) {
                merged.addAll(series);
            }
            merged.sort(java.util.Comparator.comparingLong(DemandObservationView::tick));
            byStation.put(entry.getKey(), merged);
        }
        return byStation;
    }
}
