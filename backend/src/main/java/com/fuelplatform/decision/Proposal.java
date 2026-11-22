package com.fuelplatform.decision;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A proposal the operator is asked to review.
 *
 * <p>A proposal is a <strong>reviewed envelope</strong>, not a frozen order
 * (ADR 004). It records what the operator was shown and against which snapshot,
 * so that at approval time the backend can revalidate the envelope against the
 * world as it is now, and send <strong>never more</strong> than was reviewed.
 *
 * <p>The status moves forward only:
 *
 * <pre>
 *   PENDING_REVIEW ──▶ APPROVED ──▶ APPROVED_WITH_REDUCTION
 *                   ├─▶ PARTIALLY_CHANGED   (some lines changed, some sent)
 *                   ├─▶ CHANGED             (nothing could be sent)
 *                   ├─▶ BLOCKED             (the world is not eligible)
 *                   ├─▶ EXPIRED             (too old to act on)
 *                   └─▶ CANCELLED
 * </pre>
 *
 * @param id                the proposal id
 * @param runId             the application run, so a reset isolates the records
 * @param snapshotId        the snapshot the proposal was computed from
 * @param policyVersion     the allocation policy that produced it
 * @param proposedAtTick    the tick the proposal was computed for
 * @param mode              the simulator mode when it was created
 * @param status            where the proposal is in its lifecycle
 * @param createdAt         wall clock time of creation
 * @param reviewedQuantity  the total quantity the operator was shown
 */
public record Proposal(
        UUID id,
        String runId,
        UUID snapshotId,
        String policyVersion,
        long proposedAtTick,
        String mode,
        Status status,
        Instant createdAt,
        double reviewedQuantity,
        List<ProposalLine> lines) {

    public Proposal {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /**
     * The lifecycle of a proposal.
     *
     * <p>The names are the operator-facing words. {@link #APPROVED_WITH_REDUCTION}
     * says something important that a plain "approved" would hide: the shipped
     * quantity was <strong>less</strong> than what was reviewed.
     */
    public enum Status {
        PENDING_REVIEW,
        APPROVED,
        APPROVED_WITH_REDUCTION,
        PARTIALLY_CHANGED,
        CHANGED,
        BLOCKED,
        EXPIRED,
        CANCELLED;

        /** Whether this proposal may still be acted on. */
        public boolean isOpen() {
            return this == PENDING_REVIEW;
        }

        /** Whether any shipment resulted from this proposal. */
        public boolean isSettled() {
            return this != PENDING_REVIEW;
        }
    }

    /** The total quantity the operator was shown, across all lines. */
    public double totalReviewedQuantity() {
        return lines.stream().mapToDouble(ProposalLine::reviewedQuantity).sum();
    }

    /** The total quantity that will actually be sent, across all lines. */
    public double totalFinalQuantity() {
        return lines.stream()
                .mapToDouble(line -> line.finalQuantity() == null ? 0 : line.finalQuantity())
                .sum();
    }

    /** Whether any line was reduced below what was reviewed. */
    public boolean hasReduction() {
        return lines.stream().anyMatch(ProposalLine::wasReduced);
    }

    /** The line with the given id, or null. */
    public ProposalLine line(String lineId) {
        for (ProposalLine line : lines) {
            if (line.lineId().equals(lineId)) {
                return line;
            }
        }
        return null;
    }
}
