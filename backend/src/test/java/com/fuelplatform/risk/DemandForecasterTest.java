package com.fuelplatform.risk;

import com.fuelplatform.config.ForecastProperties;
import com.fuelplatform.config.SimulatorProfileProperties;
import com.fuelplatform.simulator.dto.SimulatorDtos.DemandObservation;
import com.fuelplatform.simulator.dto.SimulatorDtos.Region;
import com.fuelplatform.simulator.dto.SimulatorDtos.Station;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the forecast engine.
 *
 * <p>Each test asks a question that could actually be wrong. A suite that only checks
 * "the number came out" proves nothing.
 */
class DemandForecasterTest {

    private static final int TICK_MINUTES = 15;

    private final ForecastProperties forecastProperties = new ForecastProperties(0.3, 8, 4, 1.25);
    private final SimulatorProfileProperties profileProperties = profiles();
    private final DemandForecaster forecaster = new DemandForecaster(forecastProperties, profileProperties);

    // ------------------------------------------------------------- fixtures

    /**
     * The measured profile constants.
     *
     * <p>The hourly windows are the ones re-measured against the running simulator.
     * The highway busy window is 06:00-09:59, which is wider than a careless reading
     * of the guide would suggest, and getting it wrong makes every hour-6 row look like
     * a 2x demand spike.
     */
    private static SimulatorProfileProperties profiles() {
        return new SimulatorProfileProperties("measured-2026-11-21", List.of(
                new SimulatorProfileProperties.Profile(
                        "urban_high", 8500, 10500, 5600, 0.10, 1.45, 0.70,
                        List.of("07-09", "16-20")),
                new SimulatorProfileProperties.Profile(
                        "industrial", 14000, 4500, 2200, 0.08, 1.55, 0.45,
                        List.of("06-17")),
                new SimulatorProfileProperties.Profile(
                        "highway", 10500, 11000, 6200, 0.12, 1.35, 0.75,
                        List.of("06-09", "16-20")),
                new SimulatorProfileProperties.Profile(
                        "regional", 7200, 7600, 3600, 0.10, 1.25, 0.65,
                        List.of("07-20"))));
    }

    private static Station mirpur() {
        return new Station("station-mirpur", "Mirpur", "region-dhaka", "OPEN", "urban_high", 1.0,
                Map.of("DIESEL", 15000.0, "PETROL", 14000.0, "OCTANE", 9000.0),
                Map.of("DIESEL", 9000.0, "PETROL", 9000.0, "OCTANE", 5000.0));
    }

    private static Region dhaka() {
        return new Region("region-dhaka", "Dhaka Division", 1.0);
    }

    private static DemandObservation row(long tick, String simTime, String fuel,
                                        double demand, double served) {
        return new DemandObservation(1L, "station-mirpur", fuel, tick, simTime,
                demand, served, Math.max(0, demand - served));
    }

    // ------------------------------------------------------- ordering (INV-20)

    @Test
    @DisplayName("INV-20: demand rows are sorted ascending by tick before any EWMA")
    void sortsAscendingByTick() {
        // The simulator returns newest first. If the rows are not re-sorted, the EWMA
        // is computed backwards in time.
        List<DemandObservation> newestFirst = List.of(
                row(3, "2026-01-01T00:45:00", "DIESEL", 300, 300),
                row(2, "2026-01-01T00:30:00", "DIESEL", 200, 200),
                row(1, "2026-01-01T00:15:00", "DIESEL", 100, 100));

        Map<String, List<DemandObservationView>> split =
                DemandForecaster.splitByFuel(newestFirst, "station-mirpur");

        List<DemandObservationView> diesel = split.get("DIESEL");
        assertThat(diesel).extracting(DemandObservationView::tick).containsExactly(1L, 2L, 3L);
    }

    @Test
    @DisplayName("INV-21: the shared limit is split by fuel, so each fuel's count is right")
    void splitsRowsByFuel() {
        // One limit is shared across all three fuels, so 9 rows is 3 ticks of 3 fuels,
        // not 9 observations for any one fuel.
        List<DemandObservation> rows = List.of(
                row(1, "2026-01-01T00:15:00", "DIESEL", 100, 100),
                row(1, "2026-01-01T00:15:00", "PETROL", 100, 100),
                row(1, "2026-01-01T00:15:00", "OCTANE", 100, 100),
                row(2, "2026-01-01T00:30:00", "DIESEL", 100, 100),
                row(2, "2026-01-01T00:30:00", "PETROL", 100, 100),
                row(2, "2026-01-01T00:30:00", "OCTANE", 100, 100));

        Map<String, List<DemandObservationView>> split =
                DemandForecaster.splitByFuel(rows, "station-mirpur");

        assertThat(split.get("DIESEL")).hasSize(2);
        assertThat(split.get("PETROL")).hasSize(2);
        assertThat(split.get("OCTANE")).hasSize(2);
    }

    @Test
    @DisplayName("duplicate ticks are deduplicated, so a re-read does not double count")
    void deduplicatesRows() {
        List<DemandObservation> rows = List.of(
                row(1, "2026-01-01T00:15:00", "DIESEL", 100, 100),
                row(1, "2026-01-01T00:15:00", "DIESEL", 100, 100),
                row(2, "2026-01-01T00:30:00", "DIESEL", 120, 120));

        Map<String, List<DemandObservationView>> split =
                DemandForecaster.splitByFuel(rows, "station-mirpur");

        assertThat(split.get("DIESEL")).hasSize(2);
    }

    // ----------------------------------------------------- demand vs sales

    @Test
    @DisplayName("INV-19: the forecast uses demand_liters, never served_liters")
    void forecastsOnDemandNotServed() {
        // A station that ran dry served 0 but still needed 100 litres. Forecasting on
        // served would learn the stockout and under-forecast forever.
        List<DemandObservation> rows = List.of(
                row(1, "2026-01-01T00:15:00", "DIESEL", 100, 0),
                row(2, "2026-01-01T00:30:00", "DIESEL", 100, 0),
                row(3, "2026-01-01T00:45:00", "DIESEL", 100, 0),
                row(4, "2026-01-01T01:00:00", "DIESEL", 100, 0));

        DemandForecast forecast = forecaster.forecast(
                mirpur(), "DIESEL",
                DemandForecaster.splitByFuel(rows, "station-mirpur").get("DIESEL"),
                1.0, TICK_MINUTES, 8);

        // 100 litres per tick at 15 minutes per tick is 400 litres per hour.
        assertThat(forecast.centralLitersPerHour()).isEqualTo(400.0);
        assertThat(forecast.method()).isEqualTo(ForecastMethod.EWMA);
    }

    // ------------------------------------------------------- cold start

    @Test
    @DisplayName("cold start falls back to the documented profile and says LOW_DATA")
    void coldStartUsesProfileEstimate() {
        List<DemandObservation> rows = List.of(
                row(1, "2026-01-01T00:15:00", "DIESEL", 100, 100));

        DemandForecast forecast = forecaster.forecast(
                mirpur(), "DIESEL",
                DemandForecaster.splitByFuel(rows, "station-mirpur").get("DIESEL"),
                1.0, TICK_MINUTES, 8);

        assertThat(forecast.method()).isEqualTo(ForecastMethod.PROFILE_ESTIMATE);
        assertThat(forecast.confidence()).isEqualTo(DataConfidence.LOW_DATA);
        assertThat(forecast.sampleCount()).isEqualTo(1);
        assertThat(forecast.warnings()).anyMatch(w -> w.contains("LOW_DATA"));

        // urban_high DIESEL is 8,500 litres/day. At hour 0 the factor is 0.70.
        // 8500 / 24 * 0.70 = 247.92 litres per hour.
        assertThat(forecast.centralLitersPerHour()).isCloseTo(247.92, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    @DisplayName("cold start multiplies the profile by the station multiplier exactly once")
    void coldStartAppliesMultiplierOnce() {
        Station spiked = new Station("station-mirpur", "Mirpur", "region-dhaka", "OPEN",
                "urban_high", 1.8,
                Map.of("DIESEL", 15000.0, "PETROL", 14000.0, "OCTANE", 9000.0),
                Map.of("DIESEL", 9000.0, "PETROL", 9000.0, "OCTANE", 5000.0));

        DemandForecast forecast = forecaster.forecast(
                spiked, "DIESEL", List.of(), 1.0, TICK_MINUTES, 8);

        // 247.92 * 1.8 = 446.26. Applying the multiplier twice would give 803.26.
        assertThat(forecast.centralLitersPerHour()).isCloseTo(446.26, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    @DisplayName("cold start applies the region factor")
    void coldStartAppliesRegionFactor() {
        DemandForecast forecast = forecaster.forecast(
                mirpur(), "DIESEL", List.of(), 1.08, TICK_MINUTES, 8);

        // 247.92 * 1.08 = 267.75
        assertThat(forecast.centralLitersPerHour()).isCloseTo(267.75, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    @DisplayName("an unknown profile produces an unknown rate, not zero")
    void unknownProfileProducesUnknownRate() {
        Station odd = new Station("station-x", "X", "region-dhaka", "OPEN", "unmodelled", 1.0,
                Map.of("DIESEL", 100.0), Map.of("DIESEL", 50.0));

        DemandForecast forecast = forecaster.forecast(odd, "DIESEL", List.of(), 1.0, TICK_MINUTES, 8);

        assertThat(forecast.isKnown()).isFalse();
        assertThat(forecast.centralLitersPerHour()).isNull();
        assertThat(forecast.confidence()).isEqualTo(DataConfidence.UNKNOWN);
    }

    // -------------------------------------------------------------- EWMA

    @Test
    @DisplayName("the EWMA weights recent observations more than old ones")
    void ewmaWeightsRecentMore() {
        List<DemandObservationView> window = List.of(
                new DemandObservationView("station-mirpur", "DIESEL", 1, "2026-01-01T00:15:00", 100),
                new DemandObservationView("station-mirpur", "DIESEL", 2, "2026-01-01T00:30:00", 100),
                new DemandObservationView("station-mirpur", "DIESEL", 3, "2026-01-01T00:45:00", 100),
                new DemandObservationView("station-mirpur", "DIESEL", 4, "2026-01-01T01:00:00", 400));

        double ewma = DemandForecaster.ewma(window, 0.3);

        // 0.3*400 + 0.7*(0.3*100 + 0.7*(0.3*100 + 0.7*100)) = 120 + 0.7*(30+0.7*100) = 120+0.7*100 = 190
        assertThat(ewma).isCloseTo(190.0, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    @DisplayName("the EWMA is computed over the window, oldest observation first")
    void ewmaFollowsTimeOrder() {
        List<DemandObservationView> ascending = List.of(
                new DemandObservationView("s", "DIESEL", 1, "t1", 100),
                new DemandObservationView("s", "DIESEL", 2, "t2", 400));
        List<DemandObservationView> descending = List.of(
                new DemandObservationView("s", "DIESEL", 2, "t2", 400),
                new DemandObservationView("s", "DIESEL", 1, "t1", 100));

        assertThat(DemandForecaster.ewma(ascending, 0.3)).isNotEqualTo(
                DemandForecaster.ewma(descending, 0.3));
    }

    @Test
    @DisplayName("the forecast reports both the EWMA error and the naive baseline error")
    void reportsBothErrors() {
        List<DemandObservation> rows = List.of(
                row(1, "2026-01-01T00:15:00", "DIESEL", 100, 100),
                row(2, "2026-01-01T00:30:00", "DIESEL", 110, 110),
                row(3, "2026-01-01T00:45:00", "DIESEL", 120, 120),
                row(4, "2026-01-01T01:00:00", "DIESEL", 130, 130));

        DemandForecast forecast = forecaster.forecast(
                mirpur(), "DIESEL",
                DemandForecaster.splitByFuel(rows, "station-mirpur").get("DIESEL"),
                1.0, TICK_MINUTES, 8);

        assertThat(forecast.recentAbsoluteError()).isNotNull();
        assertThat(forecast.baselineAbsoluteError()).isNotNull();
        // On a rising series the EWMA lags more than the naive last value, so its
        // error must be the larger one. If this ever flips, the comparison is broken.
        assertThat(forecast.recentAbsoluteError()).isGreaterThan(forecast.baselineAbsoluteError());
    }

    @Test
    @DisplayName("the stress scenario is the central rate times the stress multiplier")
    void stressIsCentralTimesMultiplier() {
        List<DemandObservation> rows = List.of(
                row(1, "2026-01-01T00:15:00", "DIESEL", 100, 100),
                row(2, "2026-01-01T00:30:00", "DIESEL", 100, 100),
                row(3, "2026-01-01T00:45:00", "DIESEL", 100, 100),
                row(4, "2026-01-01T01:00:00", "DIESEL", 100, 100));

        DemandForecast forecast = forecaster.forecast(
                mirpur(), "DIESEL",
                DemandForecaster.splitByFuel(rows, "station-mirpur").get("DIESEL"),
                1.0, TICK_MINUTES, 8);

        assertThat(forecast.centralLitersPerHour()).isEqualTo(400.0);
        assertThat(forecast.stressLitersPerHour()).isEqualTo(500.0);
    }

    @Test
    @DisplayName("the forecast covers every station and fuel, including missing ones")
    void forecastsEveryStationAndFuel() {
        Map<String, DemandForecast> forecasts = forecaster.forecastAll(
                Map.of("station-mirpur", List.of(
                        row(1, "2026-01-01T00:15:00", "DIESEL", 100, 100))),
                List.of(mirpur()),
                List.of(dhaka()),
                TICK_MINUTES,
                8);

        assertThat(forecasts).hasSize(3);
        assertThat(forecasts).containsKeys(
                "station-mirpur|DIESEL",
                "station-mirpur|PETROL",
                "station-mirpur|OCTANE");
        // PETROL has no observations, so it is a LOW_DATA profile estimate.
        assertThat(forecasts.get("station-mirpur|PETROL").confidence())
                .isEqualTo(DataConfidence.LOW_DATA);
    }
}
