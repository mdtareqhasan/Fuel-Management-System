package com.fuelplatform.decision;

import com.fuelplatform.allocation.AllocationPlan;
import com.fuelplatform.audit.AuditRepository;
import com.fuelplatform.command.Command;
import com.fuelplatform.command.CommandRepository;
import com.fuelplatform.domain.WorldSnapshot;
import com.fuelplatform.ingestion.SnapshotIngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The approval path.
 *
 * <p>This is where ADR 003 becomes real. The sequence is fixed and the order is
 * the whole design:
 *
 * <pre>
 *   1. load the proposal
 *   2. check the world is eligible      ──▶ block if not
 *   3. check the proposal is open       ──▶ conflict if already approved
 *   4. check the request is sane        ──▶ reject if the quantity is higher
 *   5. revalidate every line against a fresh read
 *   6. persist the decision and the commands   ◀── the body is frozen here
 *   7. the worker performs the POST
 * </pre>
 *
 * <p>Step 6 happens <strong>before</strong> any simulator call, and it freezes
 * the exact request body and the idempotency key (INV-10). A retry reuses both,
 * unchanged (INV-11). If the process dies after the POST but before the
 * response arrives, the command is {@code UNKNOWN} and is reconciled by matching
 * the key — never by sending a second shipment (INV-14).
 */
@Service
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

    private final ProposalRepository proposalRepository;
    private final SnapshotRepository snapshotRepository;
    private final CommandRepository commandRepository;
    private final AuditRepository auditRepository;
    private final SnapshotIngestionService ingestionService;
    private final ProposalFactory proposalFactory;
    private final FreshnessPolicy freshnessPolicy;
    private final Clock clock;

    public ApprovalService(ProposalRepository proposalRepository,
                           SnapshotRepository snapshotRepository,
                           CommandRepository commandRepository,
                           AuditRepository auditRepository,
                           SnapshotIngestionService ingestionService,
                           ProposalFactory proposalFactory,
                           FreshnessPolicy freshnessPolicy,
                           Clock clock) {
        this.proposalRepository = proposalRepository;
        this.snapshotRepository = snapshotRepository;
        this.commandRepository = commandRepository;
        this.auditRepository = auditRepository;
        this.ingestionService = ingestionService;
        this.proposalFactory = proposalFactory;
        this.freshnessPolicy = freshnessPolicy;
        this.clock = clock;
    }

    // ------------------------------------------------------------- proposals

    /**
     * Creates a proposal from the current plan and stores it.
     *
     * <p>No simulator write happens here. The proposal is a description, and it
     * waits for a human.
     */
    @Transactional
    public Proposal createProposal(AllocationPlan plan) {
        WorldSnapshot snapshot = ingestionService.currentSnapshot();
        if (snapshot == null) {
            throw new ApprovalException(ApprovalError.STALE_OR_INVALID_SNAPSHOT,
                    "no valid snapshot exists yet");
        }
        if (!ProposalFactory.isDecisionEligible(snapshot)) {
            throw new ApprovalException(ApprovalError.STALE_OR_INVALID_SNAPSHOT,
                    ProposalFactory.ineligibilityReason(snapshot));
        }

        // The snapshot is stored first, because the proposal references it. A
        // proposal that cannot be traced back to the world it was computed
        // from is not explainable, and an unexplainable decision is not
        // reviewable.
        snapshotRepository.store(snapshot);

        Proposal proposal = proposalFactory.create(snapshot, plan, ingestionService.runId());
        proposalRepository.insert(proposal);

        auditRepository.record(
                ingestionService.runId(),
                "system",
                "PROPOSAL_CREATED",
                "proposal",
                proposal.id().toString(),
                "CREATED",
                null,
                AuditRepository.details(
                        "lines", proposal.lines().size(),
                        "tick", proposal.proposedAtTick(),
                        "policy", proposal.policyVersion()));

        return proposal;
    }

    /** The proposals of the current run, newest first. */
    public List<Proposal> listProposals() {
        return proposalRepository.findByRun(ingestionService.runId());
    }

    /** One proposal, or empty. */
    public Optional<Proposal> find(UUID id) {
        return proposalRepository.find(id);
    }

    // -------------------------------------------------------------- approval

    /**
     * Approves a proposal.
     *
     * @param proposalId the proposal to approve
     * @param actor      who approved it
     * @param approved   the per-line quantities the operator confirmed. A missing
     *                   entry means the reviewed quantity. An entry may only be
     *                   <strong>lower</strong> than the reviewed quantity.
     */
    @Transactional
    public ApprovalResult approve(UUID proposalId, String actor, Map<String, Double> approved) {
        String runId = ingestionService.runId();
        String correlationId = UUID.randomUUID().toString();
        Instant now = clock.instant();

        Proposal proposal = proposalRepository.find(proposalId)
                .orElseThrow(() -> new ApprovalException(ApprovalError.PROPOSAL_NOT_FOUND,
                        "no proposal with id " + proposalId));

        // 2. An already approved proposal is a conflict, not a new shipment.
        // This is checked before the freshness rule on purpose: if the operator
        // clicks approve twice, "you already approved this" is the answer they
        // need, not "the world moved on".
        if (!proposal.status().isOpen()) {
            auditRepository.record(runId, actor, "APPROVAL_REJECTED", "proposal",
                    proposalId.toString(), "ALREADY_APPROVED", correlationId,
                    AuditRepository.details("status", proposal.status().name()));
            throw new ApprovalException(ApprovalError.ALREADY_APPROVED,
                    "the proposal is already " + proposal.status());
        }

        // 3. The world must be eligible, and the proposal must still be worth
        // acting on. This is the INV-15 guard, and it is mode-aware (ADR 004).
        WorldSnapshot fresh = ingestionService.currentSnapshot();
        String ineligible = ProposalFactory.ineligibilityReason(fresh);
        if (ineligible != null) {
            auditRepository.record(runId, actor, "APPROVAL_REJECTED", "proposal",
                    proposalId.toString(), "BLOCKED", correlationId,
                    AuditRepository.details("reason", ineligible));
            throw new ApprovalException(ApprovalError.STALE_OR_INVALID_SNAPSHOT, ineligible);
        }

        FreshnessPolicy.Verdict verdict = freshnessPolicy.check(proposal, fresh, now);
        if (!verdict.allowed()) {
            auditRepository.record(runId, actor, "APPROVAL_REJECTED", "proposal",
                    proposalId.toString(), verdict.error().code(), correlationId,
                    AuditRepository.details(
                            "reason", verdict.reason(),
                            "mode", proposal.mode()));
            throw new ApprovalException(verdict.error(), verdict.reason());
        }

        // 4. The request may only lower the quantity, never raise it.
        Map<String, Double> requested = approved == null ? Map.of() : approved;
        for (ProposalLine line : proposal.lines()) {
            Double asked = requested.get(line.lineId());
            if (asked == null) {
                continue;
            }
            if (asked > line.reviewedQuantity()) {
                auditRepository.record(runId, actor, "APPROVAL_REJECTED", "proposal",
                        proposalId.toString(), "QUANTITY_EXCEEDS_REVIEWED", correlationId,
                        AuditRepository.details(
                                "line", line.lineId(),
                                "asked", asked,
                                "reviewed", line.reviewedQuantity()));
                throw new ApprovalException(ApprovalError.QUANTITY_EXCEEDS_REVIEWED,
                        "line " + line.lineId() + " asked for " + asked
                                + " litres but only " + line.reviewedQuantity() + " was reviewed");
            }
            if (asked < 0) {
                throw new ApprovalException(ApprovalError.INVALID_REQUEST,
                        "line " + line.lineId() + " asked for a negative quantity");
            }
        }

        // 5. Revalidate every line against the fresh world.
        //
        // The dispatch budget is shared across the lines. The depot's allowance
        // is per depot and shared across every fuel and every destination
        // (INV-17), so each line has to be checked against what the earlier
        // lines left, not against the full allowance on its own.
        List<ApprovalResult.LineOutcome> outcomes = new ArrayList<>();
        List<Command> commands = new ArrayList<>();
        boolean anyChanged = false;
        boolean anyReduced = false;

        Revalidator.DispatchBudget budget = Revalidator.DispatchBudget.from(fresh);

        for (ProposalLine line : proposal.lines()) {
            Double asked = requested.get(line.lineId());
            double allowed = asked == null ? line.reviewedQuantity() : asked;

            Revalidator.Outcome outcome = Revalidator.revalidate(line, fresh, allowed, budget);

            if (!outcome.canShip()) {
                anyChanged = true;
                log.info("line {} of proposal {} sends nothing: {}",
                        line.lineId(), proposalId, outcome.reason());
                outcomes.add(new ApprovalResult.LineOutcome(
                        line.lineId(), ProposalLine.Status.CHANGED,
                        line.reviewedQuantity(), 0, outcome.reason(), null));
                proposalRepository.updateLineDecision(
                        line.withDecision(0.0, ProposalLine.Status.CHANGED));
                continue;
            }

            boolean reduced = outcome.decision() == Revalidator.Outcome.Decision.REDUCED;
            if (reduced) {
                anyReduced = true;
            }

            Command command = createCommand(proposal, line, outcome.quantity(), now);
            commands.add(command);

            outcomes.add(new ApprovalResult.LineOutcome(
                    line.lineId(),
                    reduced ? ProposalLine.Status.APPROVED_REDUCED : ProposalLine.Status.APPROVED_AS_REVIEWED,
                    line.reviewedQuantity(),
                    outcome.quantity(),
                    outcome.reason(),
                    command.id()));

            proposalRepository.updateLineDecision(line.withDecision(
                    outcome.quantity(),
                    reduced ? ProposalLine.Status.APPROVED_REDUCED : ProposalLine.Status.APPROVED_AS_REVIEWED));
        }

        // 6. The proposal's status reflects what actually happened.
        Proposal.Status newStatus = resolveStatus(outcomes, anyChanged, anyReduced);
        proposalRepository.updateStatus(proposalId, newStatus);

        auditRepository.record(runId, actor, "APPROVAL", "proposal", proposalId.toString(),
                newStatus.name(), correlationId,
                AuditRepository.details(
                        "status", newStatus.name(),
                        "commands", commands.size(),
                        "reviewedQuantity", proposal.reviewedQuantity(),
                        "shippedQuantity", outcomes.stream()
                                .mapToDouble(ApprovalResult.LineOutcome::finalQuantity).sum()));

        return new ApprovalResult(
                proposalId,
                newStatus,
                outcomes,
                commands.stream().map(Command::id).toList(),
                correlationId);
    }

    /**
     * Builds and persists a command for one line.
     *
     * <p>The idempotency key and the body are written <strong>here</strong>, before
     * the simulator is contacted. That ordering is INV-10.
     */
    private Command createCommand(Proposal proposal, ProposalLine line, double quantity, Instant now) {
        // One command per proposal line. The unique constraint on
        // (proposal_line_id) is what makes a duplicate approval a no-op.
        Optional<Command> existing = commandRepository.findByLine(line.id());
        if (existing.isPresent()) {
            // The line already has a command, so a shipment is already on its way.
            // Reusing it is what prevents the duplicate shipment (INV-12).
            return existing.get();
        }

        String key = "proposal-" + proposal.id() + "-" + line.lineId();
        String body = buildRequestBody(line, quantity, key);

        Command command = new Command(
                UUID.randomUUID(),
                proposal.runId(),
                proposal.id(),
                line.id(),
                key,
                body,
                Command.Status.APPROVED,
                null,
                null,
                0,
                null,
                now,
                now,
                null);

        try {
            return commandRepository.insert(command);
        } catch (DataIntegrityViolationException e) {
            // Two approvals raced. The unique constraint on (proposal_line_id)
            // means only one of them can create a command, so the other one
            // adopts the winner rather than creating a second shipment.
            return commandRepository.findByLine(line.id())
                    .orElseThrow(() -> e);
        }
    }

    /**
     * The exact body that will be POSTed.
     *
     * <p>It is built once and stored as JSON. A retry sends these exact bytes,
     * because the simulator rejects a replay whose body differs from the first
     * one (INV-11).
     */
    static String buildRequestBody(ProposalLine line, double quantity, String key) {
        return "{"
                + "\"idempotency_key\":\"" + key + "\","
                + "\"source_depot_id\":\"" + line.sourceDepotId() + "\","
                + "\"destination_station_id\":\"" + line.stationId() + "\","
                + "\"route_id\":\"" + line.routeId() + "\","
                + "\"fuel_type\":\"" + line.fuelType() + "\","
                + "\"quantity\":" + quantity
                + "}";
    }

    /** Works out the proposal's status from the per-line outcomes. */
    private static Proposal.Status resolveStatus(
            List<ApprovalResult.LineOutcome> outcomes, boolean anyChanged, boolean anyReduced) {

        long shipping = outcomes.stream().filter(ApprovalResult.LineOutcome::willShip).count();

        if (shipping == 0) {
            return Proposal.Status.CHANGED;
        }
        if (anyChanged) {
            return Proposal.Status.PARTIALLY_CHANGED;
        }
        if (anyReduced) {
            return Proposal.Status.APPROVED_WITH_REDUCTION;
        }
        return Proposal.Status.APPROVED;
    }
}
