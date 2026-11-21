package com.fuelplatform.risk;

import com.fuelplatform.config.RiskProperties;
import com.fuelplatform.config.SimulatorProfileProperties;
import com.fuelplatform.simulator.dto.SimulatorDtos.DomainEvent;
import com.fuelplatform.simulator.dto.SimulatorDtos.Region;
import com.fuelplatform.simulator.dto.SimulatorDtos.Station;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The rule-based demand anomaly detector.
 *
 * <p>One sentence describes the whole rule:
 * <em>observed demand that stays at or above 1.35 times the documented profile for three
 * consecutive ticks is a demand spike.</em>
 *
 * <p>Why the <strong>ratio</strong> and not the raw liters: the profile already explains
 * the normal morning and evening peaks. Dividing by it means a busy hour is not an
 * anomaly, while a genuine 1.8× spike is. A detector on raw liters would flag every
 * morning.
 *
 * <p>Why <strong>rules</strong> and not a model (ADR 005): every participant sees the
 * same single scenario, so there is no training data, and a judge cannot be shown why a
 * model fired. A rule can be stated, tested against a quiet series and a spiked series,
 * and explained in one sentence.
 *
 * <p>The detector is <strong>advisory</strong>. It adds a flag, a reason and a timestamp.
 * It never changes a quantity and never blocks risk output or approval (INV-25).
 *
 * <p>Known limit, stated rather than hidden: a spike shorter than three ticks is not
 * detected, and the thresholds are proposed, not tuned.
 */
public final class DemandAnomalyDetector {

    /** The outcome of a detector run. */
    public record Detection(
            List<AnomalyFlag> flags,
            String detectorStatus,
            List<String> notes) {

        public Detection {
            flags = flags == null ? List.of() : List.copyOf(flags);
            notes = notes == null ? List.of() : List.copyOf(notes);
        }
    }

    private final RiskProperties properties;
    private final SimulatorProfileProperties profileProperties;

    public DemandAnomalyDetector(RiskProperties properties, SimulatorProfileProperties profileProperties) {
        this.properties = properties;
        this.profileProperties = profileProperties;
    }

    /**
     * Runs the detector across every station and fuel.
     *
     * <p>If the detector cannot run it returns {@code UNAVAILABLE} rather than throwing.
     * The risk table must keep working without flags (ADR 005).
     */
    public Detection detect(
            Map<String, List<DemandObservationView>> observationsByStation,
            List<Station> stations,
            List<Region> regions,
            List<DomainEvent> events,
            int tickMinutes) {

        try {
            return run(observationsByStation, stations, regions, events, tickMinutes);
        } catch (RuntimeException e) {
            // The detector is advisory. A failure must not take the risk table down.
            return new Detection(List.of(), "UNAVAILABLE",
                    List.of("the anomaly detector failed: " + e.getMessage()));
        }
    }

    private Detection run(
            Map<String, List<DemandObservationView>> observationsByStation,
            List<Station> stations,
            List<Region> regions,
            List<DomainEvent> events,
            int tickMinutes) {

        List<String> notes = new ArrayList<>();
        List<AnomalyFlag> flags = new ArrayList<>();

        Map<String, Double> regionFactors = new LinkedHashMap<>();
        if (regions != null) {
            for (Region region : regions) {
                if (region.id() != null && region.demandFactor() != null) {
                    regionFactors.put(region.id(), region.demandFactor());
                }
            }
        }

        // station id -> the fuels for which R1 currently holds
        Map<String, Set<String>> stationSpike = new LinkedHashMap<>();

        for (Station station : stations) {
            Optional<SimulatorProfileProperties.Profile> profile =
                    profileProperties.profile(station.demandProfile());
            if (profile.isEmpty()) {
                notes.add("no profile constants for '" + station.demandProfile()
                        + "', " + station.id() + " was not evaluated for anomalies");
                continue;
            }

            double regionFactor = regionFactors.getOrDefault(station.regionId(), 1.0);
            List<DemandObservationView> observations =
                    observationsByStation.getOrDefault(station.id(), List.of());

            for (String fuel : List.of("DIESEL", "PETROL", "OCTANE")) {
                List<DemandObservationView> series = observations.stream()
                        .filter(o -> fuel.equals(o.fuel()))
                        .toList();
                if (series.isEmpty()) {
                    continue;
                }

                RatioRun run = evaluateRatioSeries(series, profile.get(), regionFactor, tickMinutes);

                // The flag is raised when the rule fired inside the window and R3 has
                // not yet cleared it. A spike that ended one tick ago is still worth
                // showing; one that ended three calm ticks ago is not.
                if (run.active()) {
                    AnomalyFlag.Cause cause = causeFor(station, events);
                    flags.add(new AnomalyFlag(
                            station.id(),
                            fuel,
                            AnomalyFlag.Severity.STATION,
                            cause,
                            round(run.peakRatio()),
                            run.consecutiveHighTicks(),
                            run.firedAtTick(),
                            "demand at " + station.id() + " for " + fuel + " reached "
                                    + round(run.peakRatio()) + "x the documented "
                                    + station.demandProfile() + " profile and tripped R1 at tick "
                                    + run.firedAtTick() + " (" + cause.name() + ")"));
                    stationSpike.computeIfAbsent(station.id(), key -> new LinkedHashSet<>()).add(fuel);
                }
            }
        }

        // R2: the same region is off its profile at two or more stations.
        Map<String, Set<String>> regionStations = new LinkedHashMap<>();
        Map<String, String> stationRegion = new LinkedHashMap<>();
        for (Station station : stations) {
            stationRegion.put(station.id(), station.regionId());
        }
        for (Map.Entry<String, Set<String>> entry : stationSpike.entrySet()) {
            String region = stationRegion.get(entry.getKey());
            if (region != null) {
                regionStations.computeIfAbsent(region, key -> new LinkedHashSet<>()).add(entry.getKey());
            }
        }

        for (Map.Entry<String, Set<String>> entry : regionStations.entrySet()) {
            if (entry.getValue().size() >= properties.regionalStationThreshold()) {
                flags = upgradeToRegional(flags, entry.getKey(), entry.getValue());
            }
        }

        if (flags.isEmpty()) {
            notes.add("no station or region exceeded the " + properties.anomalyRatioThreshold()
                    + " ratio threshold for " + properties.anomalyConsecutiveTicks() + " consecutive ticks");
        }
        notes.add("thresholds are proposed, not tuned; a spike shorter than "
                + properties.anomalyConsecutiveTicks() + " ticks is not detected");

        return new Detection(flags, "AVAILABLE", notes);
    }

    /** Re-labels the station flags of a region as regional flags. */
    private static List<AnomalyFlag> upgradeToRegional(
            List<AnomalyFlag> flags, String region, Set<String> stationIds) {

        List<AnomalyFlag> upgraded = new ArrayList<>();
        for (AnomalyFlag flag : flags) {
            if (flag.severity() == AnomalyFlag.Severity.STATION && stationIds.contains(flag.stationId())) {
                upgraded.add(new AnomalyFlag(
                        flag.stationId(),
                        flag.fuel(),
                        AnomalyFlag.Severity.REGIONAL,
                        flag.cause(),
                        flag.ratio(),
                        flag.consecutiveTicks(),
                        flag.detectedAtTick(),
                        "demand in region " + region + " is off its documented profile at "
                                + stationIds.size() + " stations, including " + flag.stationId()
                                + " (" + flag.cause().name() + ")"));
            } else {
                upgraded.add(flag);
            }
        }
        return upgraded;
    }

    /**
     * Evaluates the ratio series and tracks the consecutive high-tick run.
     *
     * <p>R3 (recovery) is applied here too: a run below the recovery threshold resets
     * the consecutive counter, so a spike that has passed does not stay flagged.
     */
    private RatioRun evaluateRatioSeries(
            List<DemandObservationView> series,
            SimulatorProfileProperties.Profile profile,
            double regionFactor,
            int tickMinutes) {

        int consecutiveHigh = 0;
        int consecutiveLow = 0;
        double peakRatio = Double.NaN;
        long firedAtTick = -1;

        for (DemandObservationView observation : series) {
            int hour = hourOf(observation.simTime());
            double factor = profile.factorAt(hour);
            double expected = expectedLitersPerTick(profile, observation, factor, regionFactor, tickMinutes);
            if (expected <= 0) {
                continue;
            }

            double ratio = observation.demandLiters() / expected;

            if (ratio >= properties.anomalyRatioThreshold()) {
                consecutiveHigh++;
                consecutiveLow = 0;
                if (firedAtTick < 0 && consecutiveHigh == properties.anomalyConsecutiveTicks()) {
                    // The tick on which the rule FIRED. This is not the last tick
                    // that was above the threshold: reporting that would make a
                    // spike that started ten ticks ago look like it was detected
                    // ten ticks late.
                    firedAtTick = observation.tick();
                }
                if (firedAtTick >= 0) {
                    peakRatio = Math.max(Double.isNaN(peakRatio) ? ratio : peakRatio, ratio);
                }
            } else if (ratio < properties.recoveryRatioThreshold()) {
                // R3: the flag is cleared only after THREE consecutive calm ticks.
                // A single calm tick is not enough, because one quiet tick in the
                // middle of a spike is ordinary noise.
                consecutiveHigh = 0;
                consecutiveLow++;
            } else {
                // Between the two thresholds. Neither the spike run nor the calm
                // run continues, so both counters reset. The spike itself is not
                // cleared: R3 needs three consecutive calm ticks.
                consecutiveHigh = 0;
                consecutiveLow = 0;
            }
        }

        // The flag is raised if the rule fired at any point in the window AND the
        // recovery rule has not yet cleared it. A spike that ended one tick ago
        // is still worth showing; a spike that ended ten calm ticks ago is not.
        boolean active = firedAtTick >= 0 && consecutiveLow < properties.anomalyConsecutiveTicks();

        return new RatioRun(consecutiveHigh, consecutiveLow, peakRatio, firedAtTick, active);
    }

    /**
     * The expected demand for one tick, from the documented profile.
     *
     * <p>The multiplier is deliberately <strong>1.0</strong> here. The guide's anomaly
     * rule divides by the profile with the multiplier taken as one, so a spike shows up
     * as a ratio instead of being normalised away. The station's own multiplier is
     * already inside the observations, which is exactly why the ratio is informative.
     */
    private static double expectedLitersPerTick(
            SimulatorProfileProperties.Profile profile,
            DemandObservationView observation,
            double hourFactor,
            double regionFactor,
            int tickMinutes) {

        Optional<Double> dailyLiters = profile.dailyLiters(observation.fuel());
        if (dailyLiters.isEmpty()) {
            return 0;
        }
        int ticksPerDay = (24 * 60) / tickMinutes;
        if (ticksPerDay <= 0) {
            return 0;
        }
        return dailyLiters.get() / ticksPerDay * hourFactor * regionFactor;
    }

    /**
     * R4: labels the cause.
     *
     * <p>An ACTIVE {@code demand_spike} event covering the station means the cause is
     * known. Otherwise the demand moved and the simulator has no explanation for it,
     * which is the more interesting case for an operator.
     */
    private static AnomalyFlag.Cause causeFor(Station station, List<DomainEvent> events) {
        if (events == null) {
            return AnomalyFlag.Cause.UNEXPLAINED;
        }
        for (DomainEvent event : events) {
            if (!"demand_spike".equalsIgnoreCase(event.type())) {
                continue;
            }
            if (!"ACTIVE".equalsIgnoreCase(event.status())) {
                continue;
            }
            if (coversStation(event, station)) {
                return AnomalyFlag.Cause.KNOWN_EVENT;
            }
        }
        return AnomalyFlag.Cause.UNEXPLAINED;
    }

    /**
     * Whether an event covers this station.
     *
     * <p>The simulator treats an empty filter list as "applies to all", so an event
     * with no station and no region list covers every station.
     */
    private static boolean coversStation(DomainEvent event, Station station) {
        Map<String, Object> parameters = event.parameters();
        if (parameters == null) {
            return false;
        }

        List<String> stationIds = stringList(parameters.get("station_ids"));
        if (!stationIds.isEmpty()) {
            return stationIds.contains(station.id());
        }

        List<String> regionIds = stringList(parameters.get("region_ids"));
        if (!regionIds.isEmpty()) {
            return regionIds.contains(station.regionId());
        }

        // No filter at all: the event applies to every entity of that type.
        return true;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> strings = new ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                strings.add(item.toString());
            }
        }
        return strings;
    }

    private static int hourOf(String simTime) {
        if (simTime == null || simTime.length() < 13) {
            return 0;
        }
        try {
            return Integer.parseInt(simTime.substring(11, 13));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /** The outcome of walking one ratio series. */
    private record RatioRun(
            int consecutiveHighTicks,
            int consecutiveLowTicks,
            double peakRatio,
            long firedAtTick,
            boolean active) {
    }
}
