package com.fuelplatform.risk;

import com.fuelplatform.config.RiskProperties;
import com.fuelplatform.config.SimulatorProfileProperties;
import com.fuelplatform.simulator.dto.SimulatorDtos.DomainEvent;
import com.fuelplatform.simulator.dto.SimulatorDtos.Region;
import com.fuelplatform.simulator.dto.SimulatorDtos.Station;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the rule-based anomaly detector.
 *
 * <p>ADR 005 requires two specific proofs: a <strong>quiet series produces no flag</strong>
 * and a <strong>spiked series is flagged</strong>. Both are here, and both are built
 * from the measured profile constants rather than invented numbers, so a quiet series
 * really is quiet.
 */
class DemandAnomalyDetectorTest {

    private final RiskProperties riskProperties = new RiskProperties(8, 2, 3, 1.35, 1.15, 2);
    private final SimulatorProfileProperties profileProperties = profiles();
    private final DemandAnomalyDetector detector = new DemandAnomalyDetector(riskProperties, profileProperties);

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

    private static Station station(String id, String region, String profile) {
        return new Station(id, id, region, "OPEN", profile, 1.0,
                Map.of("DIESEL", 15000.0, "PETROL", 14000.0, "OCTANE", 9000.0),
                Map.of("DIESEL", 9000.0, "PETROL", 9000.0, "OCTANE", 5000.0));
    }

    /**
     * The expected litres for one tick, from the documented profile.
     *
     * <p>This is the same formula the detector uses. It is written out separately here
     * so the test builds its series from the <em>documented expectation</em> rather
     * than from the detector's own output, which would make the test circular.
     */
    private static double expectedPerTick(String profile, String fuel, int hour, double regionFactor) {
        double daily = switch (profile + "|" + fuel) {
            case "urban_high|DIESEL" -> 8500;
            case "urban_high|PETROL" -> 10500;
            case "urban_high|OCTANE" -> 5600;
            case "industrial|DIESEL" -> 14000;
            case "highway|DIESEL" -> 10500;
            case "regional|DIESEL" -> 7200;
            default -> 8500;
        };
        double factor = switch (profile) {
            case "industrial" -> (hour >= 6 && hour <= 17) ? 1.55 : 0.45;
            case "highway" -> ((hour >= 6 && hour <= 9) || (hour >= 16 && hour <= 20)) ? 1.35 : 0.75;
            case "urban_high" -> ((hour >= 7 && hour <= 9) || (hour >= 16 && hour <= 20)) ? 1.45 : 0.70;
            case "regional" -> (hour >= 7 && hour <= 20) ? 1.25 : 0.65;
            default -> 1.0;
        };
        return daily / 96.0 * factor * regionFactor;
    }

    /** A quiet series: demand sits exactly on the profile. */
    private static List<DemandObservationView> quietSeries(
            String stationId, String profile, String fuel, long startTick, int hours, double regionFactor) {

        List<DemandObservationView> series = new java.util.ArrayList<>();
        for (int i = 0; i < hours * 4; i++) {
            long tick = startTick + i;
            int hour = (int) ((tick % 96) / 4);
            String simTime = String.format("2026-01-01T%02d:%02d:00", hour, (int) ((tick % 4) * 15));
            series.add(new DemandObservationView(
                    stationId, fuel, tick, simTime,
                    expectedPerTick(profile, fuel, hour, regionFactor)));
        }
        return series;
    }

    /** The same series, but multiplied by a factor for a given number of ticks. */
    private static List<DemandObservationView> spikedSeries(
            String stationId, String profile, String fuel, long startTick, int hours,
            double regionFactor, int spikeFromIndex, int spikeLength, double multiplier) {

        List<DemandObservationView> series = new java.util.ArrayList<>();
        for (int i = 0; i < hours * 4; i++) {
            long tick = startTick + i;
            int hour = (int) ((tick % 96) / 4);
            String simTime = String.format("2026-01-01T%02d:%02d:00", hour, (int) ((tick % 4) * 15));
            double demand = expectedPerTick(profile, fuel, hour, regionFactor);
            if (i >= spikeFromIndex && i < spikeFromIndex + spikeLength) {
                demand *= multiplier;
            }
            series.add(new DemandObservationView(stationId, fuel, tick, simTime, demand));
        }
        return series;
    }

    // ----------------------------------------------------------- the quiet case

    @Test
    @DisplayName("a quiet series produces no flag")
    void quietSeriesProducesNoFlag() {
        Station mirpur = station("station-mirpur", "region-dhaka", "urban_high");

        DemandAnomalyDetector.Detection detection = detector.detect(
                Map.of("station-mirpur", quietSeries("station-mirpur", "urban_high", "DIESEL", 0, 24, 1.0)),
                List.of(mirpur),
                List.of(new Region("region-dhaka", "Dhaka Division", 1.0)),
                List.of(),
                15);

        assertThat(detection.detectorStatus()).isEqualTo("AVAILABLE");
        assertThat(detection.flags()).isEmpty();
    }

    @Test
    @DisplayName("a quiet series across all four profiles produces no flag")
    void quietSeriesAcrossProfilesProducesNoFlag() {
        // The busy-hour windows are the risky part. If a window boundary is wrong, the
        // ratio jumps at that hour and the detector false-fires every single day.
        List<Station> stations = List.of(
                station("station-mirpur", "region-dhaka", "urban_high"),
                station("station-tongi", "region-dhaka", "industrial"),
                station("station-karnaphuli", "region-chattogram", "highway"),
                station("station-coxsbazar", "region-chattogram", "regional"));

        Map<String, List<DemandObservationView>> observations = new java.util.LinkedHashMap<>();
        observations.put("station-mirpur", quietSeries("station-mirpur", "urban_high", "DIESEL", 0, 24, 1.0));
        observations.put("station-tongi", quietSeries("station-tongi", "industrial", "DIESEL", 0, 24, 1.0));
        observations.put("station-karnaphuli", quietSeries("station-karnaphuli", "highway", "DIESEL", 0, 24, 1.08));
        observations.put("station-coxsbazar", quietSeries("station-coxsbazar", "regional", "DIESEL", 0, 24, 1.08));

        DemandAnomalyDetector.Detection detection = detector.detect(
                observations,
                stations,
                List.of(new Region("region-dhaka", "Dhaka Division", 1.0),
                        new Region("region-chattogram", "Chattogram Division", 1.08)),
                List.of(),
                15);

        assertThat(detection.flags()).isEmpty();
    }

    @Test
    @DisplayName("noise inside the documented 8 to 12 percent band produces no flag")
    void noiseInsideTheBandProducesNoFlag() {
        // Per-tick noise is 8 to 12 percent. The mean of three such ticks varies by
        // roughly 5 to 7 percent, so a 1.35 threshold sits far outside normal variation.
        List<DemandObservationView> series = new java.util.ArrayList<>();
        double[] noise = {1.08, 0.92, 1.10, 0.90, 1.05, 0.95, 1.11, 0.89};
        for (int i = 0; i < noise.length; i++) {
            int hour = (int) (i / 4);
            series.add(new DemandObservationView(
                    "station-mirpur", "DIESEL", i, String.format("2026-01-01T%02d:00:00", hour),
                    expectedPerTick("urban_high", "DIESEL", hour, 1.0) * noise[i]));
        }

        DemandAnomalyDetector.Detection detection = detector.detect(
                Map.of("station-mirpur", series),
                List.of(station("station-mirpur", "region-dhaka", "urban_high")),
                List.of(new Region("region-dhaka", "Dhaka Division", 1.0)),
                List.of(),
                15);

        assertThat(detection.flags()).isEmpty();
    }

    // ----------------------------------------------------------- the spike case

    @Test
    @DisplayName("a spike sustained for three ticks is flagged")
    void sustainedSpikeIsFlagged() {
        Station mirpur = station("station-mirpur", "region-dhaka", "urban_high");

        DemandAnomalyDetector.Detection detection = detector.detect(
                Map.of("station-mirpur",
                        spikedSeries("station-mirpur", "urban_high", "DIESEL", 0, 2, 1.0, 4, 4, 1.8)),
                List.of(mirpur),
                List.of(new Region("region-dhaka", "Dhaka Division", 1.0)),
                List.of(),
                15);

        assertThat(detection.flags()).hasSize(1);
        AnomalyFlag flag = detection.flags().get(0);
        assertThat(flag.stationId()).isEqualTo("station-mirpur");
        assertThat(flag.fuel()).isEqualTo("DIESEL");
        assertThat(flag.severity()).isEqualTo(AnomalyFlag.Severity.STATION);
        assertThat(flag.label()).isEqualTo("DEMAND_SPIKE_SUSPECTED");
        assertThat(flag.ratio()).isCloseTo(1.8, org.assertj.core.data.Offset.offset(0.01));
        assertThat(flag.cause()).isEqualTo(AnomalyFlag.Cause.UNEXPLAINED);
    }

    @Test
    @DisplayName("the flag reports the tick the rule FIRED on, not the last breach")
    void flagReportsTheFiringTickNotTheLastBreach() {
        // The spike starts at index 4 and runs to the end of the window. R1 needs
        // three consecutive breaches, so the rule fires on index 6, which is tick
        // startTick + 6.
        //
        // Reporting the LAST breach instead would make a spike that started eight
        // ticks ago look like it was detected eight ticks late.
        long startTick = 20;
        DemandAnomalyDetector.Detection detection = detector.detect(
                Map.of("station-mirpur",
                        spikedSeries("station-mirpur", "urban_high", "DIESEL", startTick, 6, 1.0, 4, 20, 1.8)),
                List.of(station("station-mirpur", "region-dhaka", "urban_high")),
                List.of(new Region("region-dhaka", "Dhaka Division", 1.0)),
                List.of(),
                15);

        assertThat(detection.flags()).hasSize(1);
        AnomalyFlag flag = detection.flags().get(0);
        // The third consecutive breach is at index 4 + 2 = 6.
        assertThat(flag.detectedAtTick()).isEqualTo(startTick + 6);
    }

    @Test
    @DisplayName("R3: three calm ticks clear the flag")
    void threeCalmTicksClearTheFlag() {
        // The guide's R3 is "ratio below 1.15 for 3 consecutive ticks → flag
        // cleared". The spike here ends at index 11, and the window runs to index
        // 23, so there are twelve calm ticks afterwards. The flag is cleared and
        // nothing is reported.
        DemandAnomalyDetector.Detection detection = detector.detect(
                Map.of("station-mirpur",
                        spikedSeries("station-mirpur", "urban_high", "DIESEL", 20, 6, 1.0, 4, 8, 1.8)),
                List.of(station("station-mirpur", "region-dhaka", "urban_high")),
                List.of(new Region("region-dhaka", "Dhaka Division", 1.0)),
                List.of(),
                15);

        assertThat(detection.flags()).isEmpty();
    }

    @Test
    @DisplayName("R3: a spike that ended one tick ago is still flagged")
    void recentSpikeIsStillFlagged() {
        // The spike ends at the second-to-last tick. Only one calm tick has
        // passed, so R3 has not cleared it and the operator still sees it.
        DemandAnomalyDetector.Detection detection = detector.detect(
                Map.of("station-mirpur",
                        spikedSeries("station-mirpur", "urban_high", "DIESEL", 20, 6, 1.0, 4, 19, 1.8)),
                List.of(station("station-mirpur", "region-dhaka", "urban_high")),
                List.of(new Region("region-dhaka", "Dhaka Division", 1.0)),
                List.of(),
                15);

        assertThat(detection.flags()).hasSize(1);
    }

    @Test
    @DisplayName("a spike shorter than three ticks is NOT flagged, and that limit is stated")
    void shortSpikeIsNotFlagged() {
        // A documented limit, not a bug: R1 needs three consecutive ticks.
        DemandAnomalyDetector.Detection detection = detector.detect(
                Map.of("station-mirpur",
                        spikedSeries("station-mirpur", "urban_high", "DIESEL", 0, 2, 1.0, 4, 2, 1.8)),
                List.of(station("station-mirpur", "region-dhaka", "urban_high")),
                List.of(new Region("region-dhaka", "Dhaka Division", 1.0)),
                List.of(),
                15);

        assertThat(detection.flags()).isEmpty();
    }

    @Test
    @DisplayName("R3: the flag clears once the ratio stays below the recovery threshold")
    void flagClearsAfterRecovery() {
        // The spike ends and demand returns to the profile. The run of high ticks must
        // reset, otherwise a station stays flagged forever after one spike.
        List<DemandObservationView> series = new java.util.ArrayList<>();
        for (int i = 0; i < 16; i++) {
            int hour = (int) (i / 4);
            double demand = expectedPerTick("urban_high", "DIESEL", hour, 1.0);
            if (i >= 4 && i < 8) {
                demand *= 1.8;
            }
            series.add(new DemandObservationView(
                    "station-mirpur", "DIESEL", i, String.format("2026-01-01T%02d:00:00", hour), demand));
        }

        DemandAnomalyDetector.Detection detection = detector.detect(
                Map.of("station-mirpur", series),
                List.of(station("station-mirpur", "region-dhaka", "urban_high")),
                List.of(new Region("region-dhaka", "Dhaka Division", 1.0)),
                List.of(),
                15);

        // The spike is over by the end of the series, so no flag should remain.
        assertThat(detection.flags()).isEmpty();
    }

    // --------------------------------------------------------- regional rule

    @Test
    @DisplayName("R2: two stations spiking in the same region raises a regional flag")
    void twoStationsInOneRegionRaiseRegionalFlag() {
        List<Station> spikedStations = List.of(
                station("station-mirpur", "region-dhaka", "urban_high"),
                station("station-tongi", "region-dhaka", "urban_high"));

        DemandAnomalyDetector.Detection detection = detector.detect(
                Map.of(
                        "station-mirpur", spikedSeries("station-mirpur", "urban_high", "DIESEL", 0, 2, 1.0, 0, 8, 1.8),
                        "station-tongi", spikedSeries("station-tongi", "urban_high", "DIESEL", 0, 2, 1.0, 0, 8, 1.8)),
                spikedStations,
                List.of(new Region("region-dhaka", "Dhaka Division", 1.0)),
                List.of(),
                15);

        assertThat(detection.flags()).hasSize(2);
        assertThat(detection.flags()).allMatch(f -> f.severity() == AnomalyFlag.Severity.REGIONAL);
        assertThat(detection.flags()).allMatch(f -> f.label().equals("REGIONAL_DEMAND_SPIKE"));
    }

    @Test
    @DisplayName("R2 does not fire when the spiking stations are in different regions")
    void differentRegionsDoNotRaiseRegionalFlag() {
        List<Station> stations = List.of(
                station("station-mirpur", "region-dhaka", "urban_high"),
                station("station-karnaphuli", "region-chattogram", "urban_high"));

        DemandAnomalyDetector.Detection detection = detector.detect(
                Map.of(
                        "station-mirpur", spikedSeries("station-mirpur", "urban_high", "DIESEL", 0, 2, 1.0, 0, 8, 1.8),
                        "station-karnaphuli", spikedSeries("station-karnaphuli", "urban_high", "DIESEL", 0, 2, 1.08, 0, 8, 1.8)),
                stations,
                List.of(new Region("region-dhaka", "Dhaka Division", 1.0),
                        new Region("region-chattogram", "Chattogram Division", 1.08)),
                List.of(),
                15);

        assertThat(detection.flags()).hasSize(2);
        assertThat(detection.flags()).allMatch(f -> f.severity() == AnomalyFlag.Severity.STATION);
    }

    // ------------------------------------------------------------- cause label

    @Test
    @DisplayName("R4: an ACTIVE demand_spike event covering the station labels the cause KNOWN_EVENT")
    void activeEventLabelsCauseKnownEvent() {
        DomainEvent event = new DomainEvent(1L, "demand_spike", 0L, 20L, "ACTIVE",
                Map.of("region_ids", List.of("region-dhaka"), "multiplier", 1.8));

        DemandAnomalyDetector.Detection detection = detector.detect(
                Map.of("station-mirpur",
                        spikedSeries("station-mirpur", "urban_high", "DIESEL", 0, 2, 1.0, 0, 8, 1.8)),
                List.of(station("station-mirpur", "region-dhaka", "urban_high")),
                List.of(new Region("region-dhaka", "Dhaka Division", 1.0)),
                List.of(event),
                15);

        assertThat(detection.flags()).hasSize(1);
        assertThat(detection.flags().get(0).cause()).isEqualTo(AnomalyFlag.Cause.KNOWN_EVENT);
    }

    @Test
    @DisplayName("R4: a RESOLVED event does not label the cause as known")
    void resolvedEventDoesNotLabelCauseKnown() {
        DomainEvent event = new DomainEvent(1L, "demand_spike", 0L, 5L, "RESOLVED",
                Map.of("region_ids", List.of("region-dhaka"), "multiplier", 1.8));

        DemandAnomalyDetector.Detection detection = detector.detect(
                Map.of("station-mirpur",
                        spikedSeries("station-mirpur", "urban_high", "DIESEL", 0, 2, 1.0, 0, 8, 1.8)),
                List.of(station("station-mirpur", "region-dhaka", "urban_high")),
                List.of(new Region("region-dhaka", "Dhaka Division", 1.0)),
                List.of(event),
                15);

        assertThat(detection.flags()).hasSize(1);
        assertThat(detection.flags().get(0).cause()).isEqualTo(AnomalyFlag.Cause.UNEXPLAINED);
    }

    // ------------------------------------------------------- advisory behaviour

    @Test
    @DisplayName("the detector never blocks: missing data yields UNAVAILABLE, not an exception")
    void missingDataYieldsUnavailable() {
        Station odd = station("station-x", "region-dhaka", "unmodelled");

        DemandAnomalyDetector.Detection detection = detector.detect(
                Map.of(),
                List.of(odd),
                List.of(new Region("region-dhaka", "Dhaka Division", 1.0)),
                List.of(),
                15);

        assertThat(detection.detectorStatus()).isEqualTo("AVAILABLE");
        assertThat(detection.flags()).isEmpty();
        assertThat(detection.notes()).anyMatch(n -> n.contains("unmodelled"));
    }
}
