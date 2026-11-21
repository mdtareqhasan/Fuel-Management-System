package com.fuelplatform.risk;

import java.util.List;

/**
 * A demand forecast for one station and one fuel.
 *
 * <p>This is the output of the forecast engine. Everything a reader needs to judge the
 * number travels with it, because a bare rate invites the reader to treat it as
 * certainty.
 *
 * <p>The rates are {@link Double} and not {@code double} on purpose. A forecast that
 * could not be computed has a <strong>null</strong> rate, never {@code 0.0}. Zero is a
 * real, meaningful demand reading; null is "we do not know" (invariant INV-24).
 *
 * @param stationId        the station this forecast is for
 * @param fuel             the fuel type
 * @param method           how the rate was produced
 * @param confidence       how much to trust it
 * @param sampleCount      how many observations the calculation actually used
 * @param horizonHours     the simulated hours this rate is meant to cover
 * @param centralLitersPerHour    the central demand rate, null when unknown
 * @param stressLitersPerHour     the conservative policy scenario, null when unknown
 * @param recentAbsoluteError     mean absolute error of the one-step-ahead EWMA
 *                                 predictions over the recent window, in litres per tick
 * @param baselineAbsoluteError   the same measure for the naive baseline, so the two can
 *                                 be compared on identical data
 * @param reasons          why this forecast looks the way it does, in operator language
 * @param warnings         anything the operator should be careful about
 */
public record DemandForecast(
        String stationId,
        String fuel,
        ForecastMethod method,
        DataConfidence confidence,
        int sampleCount,
        int horizonHours,
        Double centralLitersPerHour,
        Double stressLitersPerHour,
        Double recentAbsoluteError,
        Double baselineAbsoluteError,
        List<String> reasons,
        List<String> warnings) {

    public DemandForecast {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /** Whether a usable rate was produced at all. */
    public boolean isKnown() {
        return centralLitersPerHour != null;
    }

    /** The central rate for one simulated tick, or null when unknown. */
    public Double centralLitersPerTick(int tickMinutes) {
        return centralLitersPerHour == null || tickMinutes <= 0
                ? null
                : centralLitersPerHour * tickMinutes / 60.0;
    }

    /** The stress rate for one simulated tick, or null when unknown. */
    public Double stressLitersPerTick(int tickMinutes) {
        return stressLitersPerHour == null || tickMinutes <= 0
                ? null
                : stressLitersPerHour * tickMinutes / 60.0;
    }

    /** A forecast for a station and fuel that could not be computed. */
    public static DemandForecast unknown(String stationId, String fuel, String reason) {
        return new DemandForecast(
                stationId, fuel, ForecastMethod.PROFILE_ESTIMATE, DataConfidence.UNKNOWN,
                0, 0, null, null, null, null,
                List.of(reason),
                List.of("demand for " + stationId + " " + fuel + " is unknown, not zero"));
    }
}
