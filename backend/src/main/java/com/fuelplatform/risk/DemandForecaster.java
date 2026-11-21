package com.fuelplatform.risk;

import com.fuelplatform.config.ForecastProperties;
import com.fuelplatform.config.SimulatorProfileProperties;
import com.fuelplatform.simulator.dto.SimulatorDtos.DemandObservation;
import com.fuelplatform.simulator.dto.SimulatorDtos.Region;
import com.fuelplatform.simulator.dto.SimulatorDtos.Station;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Turns raw demand history into an explainable demand forecast.
 *
 * <p>Pure. No HTTP, no clock, no database. It takes observations and configuration and
 * returns numbers, which is what makes it testable without a running simulator.
 *
 * <p>Three rules are enforced here, and each one exists because getting it wrong
 * produces a wrong decision:
 *
 * <ol>
 *   <li><strong>Forecast on {@code demand_liters}, never {@code served_liters}</strong>
 *       (INV-19). Sales are capped by available stock, so a station that ran dry looks
 *       like it stopped selling. Learning from that under-forecasts forever and hides
 *       the very shortage we are trying to catch.</li>
 *   <li><strong>Sort ascending by tick before any EWMA</strong> (INV-20). The simulator
 *       returns demand history newest-first. An EWMA over a reversed series computes
 *       the average backwards in time.</li>
 *   <li><strong>Split rows by fuel after fetching</strong> (INV-21). One {@code limit}
 *       is shared across all three fuels, so a naive count is three times too large.</li>
 * </ol>
 *
 * <p>On the multiplier: the observed demand already contains the station's current
 * {@code demand_multiplier}, so the EWMA is <strong>not</strong> multiplied by it again.
 * The multiplier is only applied on the cold-start path, where there are no observations
 * to carry it.
 */
public final class DemandForecaster {

    private final ForecastProperties properties;
    private final SimulatorProfileProperties profileProperties;

    public DemandForecaster(ForecastProperties properties, SimulatorProfileProperties profileProperties) {
        this.properties = properties;
        this.profileProperties = profileProperties;
    }

    /**
     * Builds a forecast for every station and fuel in the snapshot.
     *
     * @param observationsByStation raw rows, exactly as the simulator returned them
     * @param stations             the stations to forecast for
     * @param regions              the regions, for the region demand factor
     * @param tickMinutes          simulated minutes per tick, read from the simulator
     * @param horizonHours         the projection horizon this forecast supports
     */
    public Map<String, DemandForecast> forecastAll(
            Map<String, List<DemandObservation>> observationsByStation,
            List<Station> stations,
            List<Region> regions,
            int tickMinutes,
            int horizonHours) {

        Map<String, DemandForecast> forecasts = new LinkedHashMap<>();
        Map<String, Double> regionFactors = regionFactors(regions);

        for (Station station : stations) {
            List<DemandObservation> rows = observationsByStation.getOrDefault(station.id(), List.of());
            Map<String, List<DemandObservationView>> byFuel = splitByFuel(rows, station.id());

            for (String fuel : List.of("DIESEL", "PETROL", "OCTANE")) {
                forecasts.put(
                        key(station.id(), fuel),
                        forecast(station, fuel, byFuel.getOrDefault(fuel, List.of()),
                                regionFactors.getOrDefault(station.regionId(), 1.0),
                                tickMinutes, horizonHours));
            }
        }
        return forecasts;
    }

    /**
     * Forecasts one station and fuel.
     *
     * <p>The path taken is always visible in the result: how many observations were used,
     * which method produced the rate, and how the rate was arrived at.
     */
    public DemandForecast forecast(
            Station station,
            String fuel,
            List<DemandObservationView> observations,
            double regionFactor,
            int tickMinutes,
            int horizonHours) {

        if (tickMinutes <= 0) {
            return DemandForecast.unknown(station.id(), fuel,
                    "the simulator did not report a valid tick size");
        }

        List<DemandObservationView> window = recentWindow(observations, properties.windowSize());

        if (window.size() < properties.minimumObservations()) {
            return profileEstimate(station, fuel, window, regionFactor, tickMinutes, horizonHours);
        }

        // The naive baseline: the last complete observed rate.
        double naiveLitersPerTick = window.get(window.size() - 1).demandLiters();

        // The candidate: an EWMA over the window, oldest to newest.
        double ewmaLitersPerTick = ewma(window, properties.ewmaAlpha());

        // The honest comparison: how each method would have done one step ahead.
        Evaluation ewmaEvaluation = evaluateOneStepAhead(window, properties.ewmaAlpha(), true);
        Evaluation naiveEvaluation = evaluateOneStepAhead(window, properties.ewmaAlpha(), false);

        double centralLitersPerHour = ewmaLitersPerTick * 60.0 / tickMinutes;
        double stressLitersPerHour = centralLitersPerHour * properties.stressMultiplier();

        List<String> reasons = new ArrayList<>();
        reasons.add("EWMA over the last " + window.size() + " observed ticks (alpha "
                + properties.ewmaAlpha() + ") gives " + round(centralLitersPerHour) + " litres/hour");
        reasons.add("the naive last-observation baseline gives "
                + round(naiveLitersPerTick * 60.0 / tickMinutes)
                + " litres/hour on the same window");

        List<String> warnings = new ArrayList<>();
        if (ewmaEvaluation.meanAbsoluteError() != null && naiveEvaluation.meanAbsoluteError() != null
                && ewmaEvaluation.meanAbsoluteError() > naiveEvaluation.meanAbsoluteError()) {
            warnings.add("on this window the EWMA is less accurate than the naive baseline ("
                    + round(ewmaEvaluation.meanAbsoluteError()) + " vs "
                    + round(naiveEvaluation.meanAbsoluteError()) + " litres per tick)");
        }

        return new DemandForecast(
                station.id(),
                fuel,
                ForecastMethod.EWMA,
                DataConfidence.GOOD,
                window.size(),
                horizonHours,
                centralLitersPerHour,
                stressLitersPerHour,
                ewmaEvaluation.meanAbsoluteError(),
                naiveEvaluation.meanAbsoluteError(),
                reasons,
                warnings);
    }

    /**
     * The cold-start path.
     *
     * <p>With fewer than four observations the rate comes from the documented profile:
     * daily liters divided by 24, multiplied by the hour factor, the region factor and
     * the station's current multiplier. This is a <strong>fallback</strong> and is
     * labelled {@code LOW_DATA} so it is never confused with a measured rate.
     */
    private DemandForecast profileEstimate(
            Station station,
            String fuel,
            List<DemandObservationView> observations,
            double regionFactor,
            int tickMinutes,
            int horizonHours) {

        Optional<SimulatorProfileProperties.Profile> profile =
                profileProperties.profile(station.demandProfile());

        if (profile.isEmpty()) {
            return DemandForecast.unknown(station.id(), fuel,
                    "no demand profile constants are configured for profile '" + station.demandProfile() + "'");
        }

        Optional<Double> dailyLiters = profile.get().dailyLiters(fuel);
        if (dailyLiters.isEmpty() || dailyLiters.get() <= 0) {
            return DemandForecast.unknown(station.id(), fuel,
                    "profile '" + station.demandProfile() + "' has no daily liters for " + fuel);
        }

        double multiplier = station.demandMultiplier() == null ? 1.0 : station.demandMultiplier();
        double hourFactor = profile.get().factorAt(currentHour(observations));

        // Liters per simulated hour: daily liters / 24 hours, then scaled.
        double litersPerHour = dailyLiters.get() / 24.0 * hourFactor * regionFactor * multiplier;

        List<String> reasons = new ArrayList<>();
        reasons.add("only " + observations.size() + " observation(s) available, below the "
                + properties.minimumObservations() + " needed for a measured rate");
        reasons.add("falling back to the documented " + station.demandProfile() + " profile: "
                + round(dailyLiters.get()) + " litres/day, hour factor " + hourFactor
                + ", region factor " + regionFactor + ", station multiplier " + multiplier);

        List<String> warnings = new ArrayList<>();
        warnings.add("LOW_DATA: this rate is a profile assumption, not a measurement");
        if (observations.isEmpty()) {
            warnings.add("validate this estimate against the first observations before relying on it");
        }

        return new DemandForecast(
                station.id(),
                fuel,
                ForecastMethod.PROFILE_ESTIMATE,
                DataConfidence.LOW_DATA,
                observations.size(),
                horizonHours,
                litersPerHour,
                litersPerHour * properties.stressMultiplier(),
                null,
                null,
                reasons,
                warnings);
    }

    // ----------------------------------------------------------------- helpers

    /**
     * Splits the fetched rows by fuel.
     *
     * <p>The simulator shares one {@code limit} across all three fuels, so the rows must
     * be split before anything is counted (INV-21).
     *
     * @param rows      the raw rows as the simulator returned them
     * @param stationId the station the rows belong to
     */
    public static Map<String, List<DemandObservationView>> splitByFuel(
            List<DemandObservation> rows, String stationId) {

        Map<String, List<DemandObservationView>> byFuel = new LinkedHashMap<>();
        for (String fuel : List.of("DIESEL", "PETROL", "OCTANE")) {
            byFuel.put(fuel, new ArrayList<>());
        }

        // Deduplicate on (station, fuel, tick): a re-read can return the same tick twice.
        Map<String, DemandObservationView> deduped = new LinkedHashMap<>();

        for (DemandObservation row : rows) {
            if (row == null || row.fuelType() == null || row.tick() == null) {
                continue;
            }
            String fuel = row.fuelType().toUpperCase(Locale.ROOT);
            if (!byFuel.containsKey(fuel)) {
                continue;
            }
            Double demand = row.demandLiters();
            if (demand == null || !Double.isFinite(demand)) {
                // A missing demand value is unknown, not zero. Skip the row rather
                // than inventing a reading for it.
                continue;
            }
            String dedupeKey = fuel + "|" + row.tick();
            deduped.putIfAbsent(dedupeKey,
                    new DemandObservationView(stationId, fuel, row.tick(), row.simTime(), demand));
        }

        for (Map.Entry<String, DemandObservationView> entry : deduped.entrySet()) {
            String fuel = entry.getKey().substring(0, entry.getKey().indexOf('|'));
            byFuel.get(fuel).add(entry.getValue());
        }

        // Sort ascending by tick. This is the step that INV-20 exists for.
        for (List<DemandObservationView> series : byFuel.values()) {
            series.sort(Comparator.comparingLong(DemandObservationView::tick));
        }
        return byFuel;
    }

    /** The most recent {@code windowSize} observations, still in ascending tick order. */
    private static List<DemandObservationView> recentWindow(
            List<DemandObservationView> observations, int windowSize) {

        if (observations.size() <= windowSize) {
            return List.copyOf(observations);
        }
        return List.copyOf(observations.subList(observations.size() - windowSize, observations.size()));
    }

    /**
     * The exponentially weighted moving average, oldest observation first.
     *
     * <p>{@code new_rate = alpha * observed + (1 - alpha) * previous}. The seed is the
     * first observation in the window, so the result is a genuine weighted average of
     * the whole window and not an artefact of an arbitrary starting value.
     */
    static double ewma(List<DemandObservationView> window, double alpha) {
        if (window.isEmpty()) {
            return 0;
        }
        double value = window.get(0).demandLiters();
        for (int i = 1; i < window.size(); i++) {
            value = alpha * window.get(i).demandLiters() + (1 - alpha) * value;
        }
        return value;
    }

    /**
     * Walks the window one step ahead and measures how each method would have done.
     *
     * <p>At each step the prediction uses <strong>only</strong> the observations up to
     * that point, and is then compared with the observation that actually arrived. No
     * future data leaks into the prediction.
     */
    private static Evaluation evaluateOneStepAhead(
            List<DemandObservationView> window, double alpha, boolean useEwma) {

        double totalAbsoluteError = 0;
        int comparisons = 0;

        for (int i = 1; i < window.size(); i++) {
            List<DemandObservationView> history = window.subList(0, i);
            double predicted = useEwma
                    ? ewma(history, alpha)
                    : history.get(history.size() - 1).demandLiters();
            double actual = window.get(i).demandLiters();
            totalAbsoluteError += Math.abs(predicted - actual);
            comparisons++;
        }

        if (comparisons == 0) {
            return new Evaluation(null, 0);
        }
        return new Evaluation(totalAbsoluteError / comparisons, comparisons);
    }

    /** The outcome of a one-step-ahead evaluation. */
    private record Evaluation(Double meanAbsoluteError, int comparisons) {
    }

    /** The hour of day of the most recent observation, used for the profile factor. */
    private static int currentHour(List<DemandObservationView> observations) {
        if (observations.isEmpty()) {
            return 0;
        }
        String simTime = observations.get(observations.size() - 1).simTime();
        if (simTime == null || simTime.length() < 13) {
            return 0;
        }
        try {
            return Integer.parseInt(simTime.substring(11, 13));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Map<String, Double> regionFactors(List<Region> regions) {
        Map<String, Double> factors = new LinkedHashMap<>();
        if (regions == null) {
            return factors;
        }
        for (Region region : regions) {
            if (region.id() != null && region.demandFactor() != null) {
                factors.put(region.id(), region.demandFactor());
            }
        }
        return factors;
    }

    /** The key for one station and fuel in the forecast map. */
    public static String key(String stationId, String fuel) {
        return stationId + "|" + fuel;
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
