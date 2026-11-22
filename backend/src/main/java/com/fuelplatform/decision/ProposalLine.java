package com.fuelplatform.decision;

import java.util.List;
import java.util.UUID;

/**
 * One shipment line of a proposal.
 *
 * <p>The pair {@code reviewedQuantity} and {@code finalQuantity} carries the whole
 * approval contract:
 *
 * <ul>
 *   <li>{@code reviewedQuantity} is the <strong>upper bound</strong> the operator
 *       saw and agreed to.</li>
 *   <li>{@code finalQuantity} is what will actually be shipped. It may be lower,
 *       never higher. The database enforces this too, so a bug in the service
 *       cannot ship more than the human agreed to.</li>
 * </ul>
 *
 * @param id                     the line id
 * @param proposalId             the proposal this line belongs to
 * @param lineId                 the stable line key, unique within the proposal
 * @param stationId              the destination
 * @param fuelType               the fuel
 * @param sourceDepotId          the source
 * @param routeId                the route
 * @param reviewedQuantity       the upper bound the operator reviewed
 * @param finalQuantity          what will be shipped, null until decided
 * @param estimatedArrivalTick   when the shipment is expected to land
 * @param coverBeforeHours       the projected cover before the shipment
 * @param coverAfterHours        the projected cover after the shipment
 * @param reasons                why the shipment was proposed
 * @param constraints            which constraints decided the quantity
 * @param alternatives           the alternatives, each with its reason
 * @param status                 where this line is in its lifecycle
 */
public record ProposalLine(
        UUID id,
        UUID proposalId,
        String lineId,
        String stationId,
        String fuelType,
        String sourceDepotId,
        String routeId,
        double reviewedQuantity,
        Double finalQuantity,
        Long estimatedArrivalTick,
        Double coverBeforeHours,
        Double coverAfterHours,
        List<String> reasons,
        List<String> constraints,
        List<Alternative> alternatives,
        Status status) {

    public ProposalLine {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        constraints = constraints == null ? List.of() : List.copyOf(constraints);
        alternatives = alternatives == null ? List.of() : List.copyOf(alternatives);
    }

    /**
     * The lifecycle of one line.
     *
     * <p>{@link #APPROVED_AS_REVIEWED} and {@link #APPROVED_REDUCED} are
     * separate states because the difference matters to the operator: the first
     * shipped what they saw, the second shipped less because the world changed.
     */
    public enum Status {
        PENDING,
        APPROVED_AS_REVIEWED,
        APPROVED_REDUCED,
        CHANGED,
        BLOCKED;

        /** Whether this line will produce a shipment. */
        public boolean willShip() {
            return this == APPROVED_AS_REVIEWED || this == APPROVED_REDUCED;
        }
    }

    /** Whether the shipped quantity was lower than the reviewed quantity. */
    public boolean wasReduced() {
        return finalQuantity != null && finalQuantity < reviewedQuantity;
    }

    /**
     * A copy with the decided quantity and status.
     *
     * <p>The record is immutable, and the decision is made once, so the copy is
     * the honest way to express it.
     */
    public ProposalLine withDecision(Double newFinalQuantity, Status newStatus) {
        return new ProposalLine(
                id, proposalId, lineId, stationId, fuelType, sourceDepotId, routeId,
                reviewedQuantity, newFinalQuantity, estimatedArrivalTick,
                coverBeforeHours, coverAfterHours, reasons, constraints, alternatives,
                newStatus);
    }

    /**
     * An alternative route the operator could have chosen instead.
     *
     * @param routeId            the alternative route, null when none exists
     * @param sourceDepotId      its source depot
     * @param quantityLiters     the quantity it could carry
     * @param expectedArrivalTick when it would land
     * @param coverAfterHours    the cover it would produce
     * @param reasonCode         a fixed code the UI can colour
     * @param reason             one sentence explaining why it lost
     */
    public record Alternative(
            String routeId,
            String sourceDepotId,
            Double quantityLiters,
            Long expectedArrivalTick,
            Double coverAfterHours,
            String reasonCode,
            String reason) {
    }
}
