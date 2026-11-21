package com.fuelplatform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The simulator's documented demand profile constants.
 *
 * <p><strong>These are simulator-specific, not business rules.</strong> They describe how
 * the official simulator generates demand, and they are the denominator of every
 * anomaly ratio the detector computes. If they are wrong, the detector flags normal
 * hours as anomalies, so they live in one versioned configuration block
 * ({@code app.simulator-profile} in {@code application.yml}) instead of being scattered
 * through the code.
 *
 * <p>Every value here was re-measured against the running simulator rather than trusted
 * from the PDF. See {@code docs/contract-notes.md} for the measurement and
 * {@code scripts/measure_hour_factor.py} for the script that produces it.
 */
@ConfigurationProperties(prefix = "app.simulator-profile")
public record SimulatorProfileProperties(String version, List<Profile> profiles) {

    public SimulatorProfileProperties {
        if (version == null || version.isBlank()) {
            version = "unspecified";
        }
        profiles = profiles == null ? List.of() : List.copyOf(profiles);
    }

    /**
     * One demand profile.
     *
     * @param name             the profile id the simulator reports on a station
     * @param dieselDailyLiters  litres of DIESEL per simulated day at factor 1.0
     * @param petrolDailyLiters  litres of PETROL per simulated day at factor 1.0
     * @param octaneDailyLiters  litres of OCTANE per simulated day at factor 1.0
     * @param noiseFactor       the documented per-tick noise band, used only for reporting
     * @param busyFactor        the hour-of-day factor during busy hours
     * @param offPeakFactor     the hour-of-day factor outside busy hours
     * @param busyHourRanges    the busy windows, written as {@code "06-09"}
     */
    public record Profile(
            String name,
            double dieselDailyLiters,
            double petrolDailyLiters,
            double octaneDailyLiters,
            double noiseFactor,
            double busyFactor,
            double offPeakFactor,
            List<String> busyHourRanges) {

        public Profile {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("profile name is required");
            }
            busyHourRanges = busyHourRanges == null ? List.of() : List.copyOf(busyHourRanges);
            if (busyFactor <= 0 || offPeakFactor <= 0) {
                throw new IllegalArgumentException("profile " + name + " needs positive hour factors");
            }
            if (dieselDailyLiters < 0 || petrolDailyLiters < 0 || octaneDailyLiters < 0) {
                throw new IllegalArgumentException("profile " + name + " cannot have negative daily liters");
            }
        }

        /** Daily liters for one fuel, or empty when the fuel is not modelled. */
        public Optional<Double> dailyLiters(String fuel) {
            if (fuel == null) {
                return Optional.empty();
            }
            return switch (fuel.trim().toUpperCase(Locale.ROOT)) {
                case "DIESEL" -> Optional.of(dieselDailyLiters);
                case "PETROL" -> Optional.of(petrolDailyLiters);
                case "OCTANE" -> Optional.of(octaneDailyLiters);
                default -> Optional.empty();
            };
        }

        /** The hour-of-day factor at one hour of the simulated day. */
        public double factorAt(int hour) {
            for (String range : busyHourRanges) {
                if (HourRange.parse(range).contains(hour)) {
                    return busyFactor;
                }
            }
            return offPeakFactor;
        }

        public List<HourRange> busyHours() {
            return busyHourRanges.stream().map(HourRange::parse).toList();
        }
    }

    /** Looks a profile up by its simulator id. */
    public Optional<Profile> profile(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return profiles.stream()
                .filter(p -> p.name().equalsIgnoreCase(name.trim()))
                .findFirst();
    }
}
