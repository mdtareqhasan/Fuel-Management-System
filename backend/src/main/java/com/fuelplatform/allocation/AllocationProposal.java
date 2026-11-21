package com.fuelplatform.allocation;

import java.util.List;

/**
 * One proposed shipment line.
 *
 * <p>A proposal is an <strong>envelope</strong>, not a frozen order (ADR 004). At approval
 * the backend re-reads the world and may send less than the reviewed quantity, never
 * more and never to a different destination.
 *
 * @param lineId                 a stable id for this line within the proposal
 * @param stationId              the station that needs fuel
 * @param fuel                   the fuel type
 * @param depotId                the depot that would send it
 * @param routeId                the route it would travel on
 * @param quantityLiters         the proposed quantity
 * @param needLiters             how much the station is short
 * @param estimatedArrivalTick   when it would arrive
 * @param estimatedArrivalHours  the same, in simulated hours
 * @param coverBeforeHours        projected cover without this shipment
 * @param coverAfterHours         projected cover with this shipment
 * @param reasons                why this line was chosen, in operator language
 * @param constraints            the constraints that shaped the quantity
 * @param alternatives           up to two alternatives, or NO_ALTERNATIVE_ROUTE
 */
public record AllocationProposal(
        String lineId,
        String stationId,
        String fuel,
        String depotId,
        String routeId,
        double quantityLiters,
        double needLiters,
        Long estimatedArrivalTick,
        Double estimatedArrivalHours,
        Double coverBeforeHours,
        Double coverAfterHours,
        List<String> reasons,
        List<String> constraints,
        List<Alternative> alternatives) {

    public AllocationProposal {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        constraints = constraints == null ? List.of() : List.copyOf(constraints);
        alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
    }

    /**
     * A copy of this proposal with its alternatives attached.
     *
     * <p>The record is immutable, and the alternatives can only be worked out once the
     * primary line is settled. Rather than making the whole record mutable, it is
     * rebuilt with the alternatives in place.
     */
    public AllocationProposal withAlternatives(List<Alternative> newAlternatives) {
        return new AllocationProposal(
                lineId, stationId, fuel, depotId, routeId, quantityLiters, needLiters,
                estimatedArrivalTick, estimatedArrivalHours, coverBeforeHours, coverAfterHours,
                reasons, constraints, newAlternatives);
    }

    /**
     * An alternative the operator can consider instead of the primary line.
     *
     * <p>The reason code says <strong>why it lost</strong>, not just that it exists.
     * An alternative without a reason is decoration.
     */
    public record Alternative(
            String routeId,
            String depotId,
            double quantityLiters,
            Long estimatedArrivalTick,
            Double projectedCoverHours,
            String reasonCode,
            String reason) {

        /** Why this alternative was not chosen. */
        public static final String LATER_ARRIVAL = "LATER_ARRIVAL";
        public static final String LOWER_SOURCE_COVER = "LOWER_SOURCE_COVER";
        public static final String DISPATCH_RESERVED_FOR_MORE_URGENT = "DISPATCH_RESERVED_FOR_MORE_URGENT";
        public static final String NO_ALTERNATIVE_ROUTE = "NO_ALTERNATIVE_ROUTE";
    }
}
