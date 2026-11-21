package com.fuelplatform.allocation;

import java.util.List;

/**
 * The result of planning allocations.
 *
 * @param proposals        the feasible lines, ranked most urgent first
 * @param unmetNeeds       the needs that could not be met, with the reason why
 * @param alternativesByLine  the alternatives for each line, keyed by line id
 */
public record AllocationPlan(
        List<AllocationProposal> proposals,
        List<UnmetNeed> unmetNeeds,
        String policyVersion) {

    public AllocationPlan {
        proposals = proposals == null ? List.of() : List.copyOf(proposals);
        unmetNeeds = unmetNeeds == null ? List.of() : List.copyOf(unmetNeeds);
    }

    /**
     * A need that could not be satisfied.
     *
     * @param reasonCode one of {@code NO_AVAILABLE_ROUTE}, {@code DISPATCH_LIMIT},
     *                   {@code SOURCE_SHORTFALL} or {@code STATION_CLOSED}
     */
    public record UnmetNeed(
            String stationId,
            String fuel,
            double needLiters,
            double metLiters,
            String reasonCode,
            String reason) {

        public static final String NO_AVAILABLE_ROUTE = "NO_AVAILABLE_ROUTE";
        public static final String DISPATCH_LIMIT = "DISPATCH_LIMIT";
        public static final String SOURCE_SHORTFALL = "SOURCE_SHORTFALL";
        public static final String STATION_CLOSED = "STATION_CLOSED";

        /**
         * A route exists, but none of them could carry the whole remaining need.
         *
         * <p>This is deliberately a different code from {@link #NO_AVAILABLE_ROUTE}.
         * The two mean opposite things to an operator: "there is no way to reach
         * this station" is a network problem, while "the best route cannot carry
         * this much in one shipment" is a sizing problem with a known fix —
         * send it in two shipments.
         */
        public static final String ROUTE_CAPACITY_EXCEEDED = "ROUTE_CAPACITY_EXCEEDED";
    }
}
