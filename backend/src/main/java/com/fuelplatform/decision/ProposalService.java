package com.fuelplatform.decision;

import com.fuelplatform.allocation.AllocationPlan;
import com.fuelplatform.domain.WorldSnapshot;
import com.fuelplatform.ingestion.SnapshotIngestionService;
import com.fuelplatform.risk.RiskEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Creates proposals from the current plan.
 *
 * <p>A proposal is created <strong>without any simulator write</strong> (ADR 003).
 * It is a description of what the application believes should happen, waiting for
 * a human to confirm it.
 *
 * <p>Two ways a proposal comes into existence:
 *
 * <ul>
 *   <li>the operator asks for one — {@code POST /api/proposals}</li>
 *   <li>the scheduler creates one when the world has moved enough to need a fresh
 *       decision</li>
 * </ul>
 *
 * <p>The scheduler is deliberately conservative. It creates a proposal only when
 * there is something to decide, and it does not create a second proposal while
 * one is still waiting for review — otherwise the review list would fill with
 * duplicates of the same decision.
 */
@Service
public class ProposalService {

    private static final Logger log = LoggerFactory.getLogger(ProposalService.class);

    private final ApprovalService approvalService;
    private final RiskEngine riskEngine;
    private final SnapshotIngestionService ingestionService;

    public ProposalService(ApprovalService approvalService,
                           RiskEngine riskEngine,
                           SnapshotIngestionService ingestionService) {
        this.approvalService = approvalService;
        this.riskEngine = riskEngine;
        this.ingestionService = ingestionService;
    }

    /**
     * Creates a proposal from the current world.
     *
     * @return the proposal, or empty when there is nothing to decide
     */
    public Optional<Proposal> createFromCurrentPlan() {
        WorldSnapshot snapshot = ingestionService.currentSnapshot();
        if (snapshot == null || !ProposalFactory.isDecisionEligible(snapshot)) {
            return Optional.empty();
        }

        RiskEngine.RiskAssessment assessment = riskEngine.assess(snapshot);
        AllocationPlan plan = new AllocationPlan(
                assessment.proposals(), assessment.unmetNeeds(), assessment.policyVersion());

        if (plan.proposals().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(approvalService.createProposal(plan));
    }

    /**
     * Creates a proposal from a given plan.
     *
     * <p>Returns empty when the plan has no lines — an empty proposal is not a
     * decision, it is noise in the review list.
     */
    public Optional<Proposal> createFrom(AllocationPlan plan) {
        if (plan.proposals().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(approvalService.createProposal(plan));
    }

    /**
     * Creates a proposal only when nothing is already waiting for review.
     *
     * <p>This is what the scheduler calls. One pending proposal is enough to
     * represent the current situation; a second one describing the same world
     * would be a duplicate decision for the operator to resolve.
     */
    public Optional<Proposal> createIfNothingPending() {
        List<Proposal> pending = approvalService.listProposals().stream()
                .filter(proposal -> proposal.status().isOpen())
                .toList();

        if (!pending.isEmpty()) {
            log.debug("a proposal is already waiting for review, not creating another");
            return Optional.empty();
        }
        return createFromCurrentPlan();
    }
}
