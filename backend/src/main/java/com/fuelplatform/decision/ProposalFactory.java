package com.fuelplatform.decision;

import com.fuelplatform.domain.DataQuality;
import com.fuelplatform.domain.WorldSnapshot;
import com.fuelplatform.allocation.AllocationPlan;
import com.fuelplatform.allocation.AllocationProposal;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Turns the allocation plan into a reviewable proposal.
 *
 * <p>A proposal is created <strong>without any simulator write</strong>
 * (ADR 003). It is a description of what the application believes should happen,
 * together with everything the operator needs to check that belief: the reasons,
 * the constraints, the alternatives and the estimated effect.
 *
 * <p>Creating a proposal is a pure function of the snapshot and the plan. That
 * makes it reproducible: the same snapshot always produces the same proposal, so
 * a reviewer can be shown the same numbers twice.
 */
public class ProposalFactory {

    /**
     * Builds a proposal from the current plan.
     *
     * @param snapshot the snapshot the plan was computed from
     * @param plan     the allocation plan
     * @param runId    the application run
     */
    public Proposal create(WorldSnapshot snapshot, AllocationPlan plan, String runId) {
        UUID proposalId = UUID.randomUUID();
        java.time.Instant createdAt = java.time.Instant.now();

        List<ProposalLine> lines = new ArrayList<>();
        for (AllocationProposal proposal : plan.proposals()) {
            lines.add(new ProposalLine(
                    UUID.randomUUID(),
                    proposalId,
                    proposal.lineId(),
                    proposal.stationId(),
                    proposal.fuel(),
                    proposal.depotId(),
                    proposal.routeId(),
                    proposal.quantityLiters(),
                    // The final quantity is unknown until the operator decides.
                    null,
                    proposal.estimatedArrivalTick(),
                    proposal.coverBeforeHours(),
                    proposal.coverAfterHours(),
                    proposal.reasons(),
                    proposal.constraints(),
                    toAlternatives(proposal),
                    ProposalLine.Status.PENDING));
        }

        return new Proposal(
                proposalId,
                runId,
                UUID.fromString(snapshot.snapshotId()),
                plan.policyVersion(),
                snapshot.tick(),
                snapshot.mode().name(),
                Proposal.Status.PENDING_REVIEW,
                createdAt,
                lines.stream().mapToDouble(ProposalLine::reviewedQuantity).sum(),
                lines);
    }

    /** Maps the allocation alternatives onto the decision records. */
    private static List<ProposalLine.Alternative> toAlternatives(AllocationProposal proposal) {
        List<ProposalLine.Alternative> alternatives = new ArrayList<>();
        for (AllocationProposal.Alternative alternative : proposal.alternatives()) {
            alternatives.add(new ProposalLine.Alternative(
                    alternative.routeId(),
                    alternative.depotId(),
                    alternative.quantityLiters(),
                    alternative.estimatedArrivalTick(),
                    alternative.projectedCoverHours(),
                    alternative.reasonCode(),
                    alternative.reason()));
        }
        return alternatives;
    }

    /** Whether the snapshot may back an approval at all. */
    public static boolean isDecisionEligible(WorldSnapshot snapshot) {
        return snapshot != null && snapshot.quality().eligible();
    }

    /** The reason a snapshot may not back an approval, or null when it may. */
    public static String ineligibilityReason(WorldSnapshot snapshot) {
        if (snapshot == null) {
            return "no valid snapshot exists yet";
        }
        DataQuality quality = snapshot.quality();
        if (!quality.eligible()) {
            return String.join("; ", quality.reasons());
        }
        return null;
    }
}
