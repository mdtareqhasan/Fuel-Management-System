package com.fuelplatform.config;

/**
 * An inclusive range of hours of the simulated day.
 *
 * <p>Used only by the demand profile windows. The simulator's {@code sim_time} is
 * naive, so "hour 7" means the seventh hour of the simulated day, counted from
 * midnight, exactly as the integration guide writes it.
 */
public record HourRange(int fromHourInclusive, int toHourInclusive) {

    public HourRange {
        if (fromHourInclusive < 0 || fromHourInclusive > 23) {
            throw new IllegalArgumentException("from hour out of range: " + fromHourInclusive);
        }
        if (toHourInclusive < fromHourInclusive || toHourInclusive > 23) {
            throw new IllegalArgumentException(
                    "to hour must be at or after from hour and within 0..23: " + toHourInclusive);
        }
    }

    public boolean contains(int hour) {
        return hour >= fromHourInclusive && hour <= toHourInclusive;
    }

    /**
     * Parses the {@code "06-09"} form used in the configuration file.
     *
     * <p>A single hour is written as {@code "06-06"}.
     */
    public static HourRange parse(String text) {
        String trimmed = text == null ? "" : text.trim();
        int dash = trimmed.indexOf('-');
        if (dash < 0) {
            throw new IllegalArgumentException("busy hour range must look like '06-09': " + text);
        }
        try {
            int from = Integer.parseInt(trimmed.substring(0, dash).trim());
            int to = Integer.parseInt(trimmed.substring(dash + 1).trim());
            return new HourRange(from, to);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("busy hour range must look like '06-09': " + text, e);
        }
    }

    @Override
    public String toString() {
        return String.format("%02d-%02d", fromHourInclusive, toHourInclusive);
    }
}
