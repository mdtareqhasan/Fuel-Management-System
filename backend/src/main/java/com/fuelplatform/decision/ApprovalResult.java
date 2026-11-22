package com.fuelplatform.decision;

import java.util.List;
import java.util.UUID;

/**
 * The result of an approval attempt.
 *
 * <p>The distinction that matters is between a line that <strong>will ship</strong>
 * and one that will not. An approval that reduced a quantity still shipped
 * something, and the operator needs to be told that clearly rather than being
 * shown a generic failure.
 *
 * @param proposalId    the proposal that was approved
 * @param status        the resulting proposal status
 * @param lines         the per-line outcome
 * @param commands      the commands created, one per shipping line
 * @param correlationId the id to quote when reporting a problem
 */
public record ApprovalResult(
        UUID proposalId,
        Proposal.Status status,
        List<LineOutcome> lines,
        List<UUID> commandIds,
        String correlationId) {

    public ApprovalResult {
        lines = lines == null ? List.of() : List.copyOf(lines);
        commandIds = commandIds == null ? List.of() : List.copyOf(commandIds);
    }

    /** Whether anything will actually be shipped. */
    public boolean shippedAnything() {
        return lines.stream().anyMatch(LineOutcome::willShip);
    }

    /** The total quantity that will be shipped. */
    public double totalShippedQuantity() {
        return lines.stream().mapToDouble(LineOutcome::finalQuantity).sum();
    }

    /**
     * The outcome of one line.
     *
     * @param lineId          the line
     * @param status          the line's resulting status
     * @param reviewedQuantity the upper bound the operator reviewed
     * @param finalQuantity   what will be shipped, zero when nothing ships
     * @param reason          why, in one sentence the operator can read
     * @param commandId       the command that carries the shipment, if any
     */
    public record LineOutcome(
            String lineId,
            ProposalLine.Status status,
            double reviewedQuantity,
            double finalQuantity,
            String reason,
            UUID commandId) {

        /** Whether this line will produce a shipment. */
        public boolean willShip() {
            return status.willShip();
        }

        /** Whether the quantity was reduced below the reviewed upper bound. */
        public boolean wasReduced() {
            return finalQuantity < reviewedQuantity;
        }
    }
}
