package com.fuelplatform.domain;

import java.util.List;

/**
 * Whether a snapshot may back a decision.
 *
 * <p>The distinction that matters: a snapshot can be perfectly <em>displayable</em> and
 * still be <em>ineligible</em> for a decision. Under a {@code stale_data} fault the
 * simulator returns HTTP 200 with perfectly well-formed JSON. Showing it is fine;
 * allocating fuel from it is not.
 *
 * @param eligible  whether approval may be based on this snapshot
 * @param reasons   why it is not eligible, empty when it is
 */
public record DataQuality(boolean eligible, List<String> reasons) {

    public static DataQuality fresh() {
        return new DataQuality(true, List.of());
    }

    public static DataQuality ineligible(List<String> reasons) {
        return new DataQuality(false, List.copyOf(reasons));
    }

    public static DataQuality ineligible(String reason) {
        return new DataQuality(false, List.of(reason));
    }

    /** FRESH when no fault affected the read; STALE when a stale header was present. */
    public String label() {
        if (!eligible && reasons.stream().anyMatch(r -> r.toLowerCase().contains("stale"))) {
            return "STALE";
        }
        return eligible ? "FRESH" : "INVALID";
    }
}
