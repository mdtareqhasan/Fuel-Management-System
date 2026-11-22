package com.fuelplatform.web;

import com.fuelplatform.command.Command;
import com.fuelplatform.command.CommandRepository;
import com.fuelplatform.command.CommandWorker;
import com.fuelplatform.decision.ApprovalException;
import com.fuelplatform.decision.ApprovalResult;
import com.fuelplatform.decision.ApprovalService;
import com.fuelplatform.decision.Proposal;
import com.fuelplatform.decision.ProposalLine;
import com.fuelplatform.decision.ProposalService;
import com.fuelplatform.ingestion.SnapshotIngestionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The operator's decision surface.
 *
 * <p>Three endpoints:
 *
 * <ul>
 *   <li>{@code GET /api/proposals} — the review list</li>
 *   <li>{@code POST /api/proposals/{id}/approve} — the approval</li>
 *   <li>{@code GET /api/shipments} — the ledger</li>
 * </ul>
 *
 * <p>The approve endpoint is the only one that can lead to a simulator write, and
 * even then the write is performed by the command worker afterwards. The
 * controller itself never talks to the simulator.
 *
 * <p>The approval errors are mapped to their own HTTP statuses with a machine
 * readable code, because the frontend has to tell "the world changed" apart from
 * "you already approved this" apart from "the quantity was too large". A single
 * generic failure would leave the operator guessing.
 */
@RestController
@RequestMapping("/api")
public class DecisionController {

    private final ApprovalService approvalService;
    private final CommandWorker commandWorker;
    private final CommandRepository commandRepository;
    private final ProposalService proposalService;
    private final SnapshotIngestionService ingestionService;
    private final ObjectMapper objectMapper;

    public DecisionController(ApprovalService approvalService,
                              CommandWorker commandWorker,
                              CommandRepository commandRepository,
                              ProposalService proposalService,
                              SnapshotIngestionService ingestionService,
                              ObjectMapper objectMapper) {
        this.approvalService = approvalService;
        this.commandWorker = commandWorker;
        this.commandRepository = commandRepository;
        this.proposalService = proposalService;
        this.ingestionService = ingestionService;
        this.objectMapper = objectMapper;
    }

    /** The current application run. */
    private String runId() {
        return ingestionService.runId();
    }

    /** The proposals of the current run, newest first. */
    @GetMapping("/proposals")
    public ResponseEntity<List<ProposalResponse>> proposals() {
        List<ProposalResponse> responses = new ArrayList<>();
        for (Proposal proposal : approvalService.listProposals()) {
            responses.add(ProposalResponse.from(proposal));
        }
        return ResponseEntity.ok(responses);
    }

    /**
     * Creates a proposal from the current plan.
     *
     * <p>No simulator write happens here. The proposal waits for a human, and
     * only the approve endpoint can lead to a shipment (ADR 003).
     */
    @PostMapping("/proposals")
    public ResponseEntity<?> createProposal() {
        Optional<Proposal> created = proposalService.createFromCurrentPlan();
        if (created.isPresent()) {
            return ResponseEntity.status(201).body(ProposalResponse.from(created.get()));
        }
        return ResponseEntity.status(409)
                .body(new ErrorResponse("NOTHING_TO_DECIDE",
                        "there is nothing to decide right now"));
    }

    /** One proposal, or 404. */
    @GetMapping("/proposals/{id}")
    public ResponseEntity<ProposalResponse> proposal(@PathVariable UUID id) {
        return approvalService.find(id)
                .map(ProposalResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Approves a proposal.
     *
     * <p>The body carries the actor and the per-line quantities. The quantities
     * may only be <strong>lower</strong> than what was reviewed (INV-13).
     */
    @PostMapping("/proposals/{id}/approve")
    public ResponseEntity<?> approve(@PathVariable UUID id, @RequestBody ApproveRequest request) {
        ApprovalResult result = approvalService.approve(id, request.actor(), request.quantities());

        // The commands are sent after the transaction that recorded them has
        // committed. The intent is already durable at this point, so a failure
        // here leaves an APPROVED command that the worker will pick up.
        for (UUID commandId : result.commandIds()) {
            commandWorker.send(commandId);
        }

        return ResponseEntity.ok(ApprovalResponse.from(result));
    }

    /** The shipment ledger: the application's commands and the simulator's shipments. */
    @GetMapping("/shipments")
    public ResponseEntity<List<ShipmentResponse>> shipments() {
        String runId = commandWorker.ledger(runId()).stream()
                .map(Command::runId)
                .findFirst()
                .orElse(runId());

        List<ShipmentResponse> responses = new ArrayList<>();
        for (Command command : commandRepository.findAll(runId)) {
            responses.add(ShipmentResponse.from(command, objectMapper));
        }
        return ResponseEntity.ok(responses);
    }

    /** Reconciles every command whose outcome is not known. */
    @PostMapping("/shipments/reconcile")
    public ResponseEntity<List<ShipmentResponse>> reconcile() {
        for (Command command : commandWorker.reconcileAll(runId())) {
            // The worker records the outcome; nothing more to do here.
            if (command.status() == com.fuelplatform.command.Command.Status.APPROVED) {
                commandWorker.send(command.id());
            }
        }
        List<ShipmentResponse> responses = new ArrayList<>();
        for (Command command : commandRepository.findAll(runId())) {
            responses.add(ShipmentResponse.from(command, objectMapper));
        }
        return ResponseEntity.ok(responses);
    }

    /**
     * The approval request.
     *
     * @param actor     who approved
     * @param quantities the per-line quantities, keyed by line id. A missing entry
     *                  means the reviewed quantity.
     */
    public record ApproveRequest(String actor, Map<String, Double> quantities) {

        public ApproveRequest {
            quantities = quantities == null ? Map.of() : Map.copyOf(quantities);
        }
    }

    /**
     * A proposal, as the UI sees it.
     *
     * @param id                the proposal id
     * @param status            the lifecycle status
     * @param mode              the simulator mode when it was made
     * @param proposedAtTick    the tick it was computed for
     * @param reviewedQuantity  the total the operator was shown
     * @param finalQuantity     the total that will be shipped, zero when none
     * @param lines             the lines
     * @param createdAt         when it was made
     */
    public record ProposalResponse(
            UUID id,
            String status,
            String mode,
            long proposedAtTick,
            double reviewedQuantity,
            double finalQuantity,
            InstantField createdAt,
            List<LineResponse> lines) {

        static ProposalResponse from(Proposal proposal) {
            List<LineResponse> lines = new ArrayList<>();
            for (ProposalLine line : proposal.lines()) {
                lines.add(LineResponse.from(line));
            }
            return new ProposalResponse(
                    proposal.id(),
                    proposal.status().name(),
                    proposal.mode(),
                    proposal.proposedAtTick(),
                    proposal.totalReviewedQuantity(),
                    proposal.totalFinalQuantity(),
                    new InstantField(proposal.createdAt().toString()),
                    lines);
        }
    }

    /**
     * A proposal line, as the UI sees it.
     *
     * @param lineId          the line id
     * @param stationId       the destination
     * @param fuelType        the fuel
     * @param sourceDepotId   the source
     * @param routeId         the route
     * @param reviewedQuantity the upper bound the operator reviewed
     * @param finalQuantity   what will be shipped
     * @param status          the line status
     * @param reasons         why the shipment was proposed
     * @param constraints     which constraints shaped it
     * @param alternatives    the alternatives
     */
    public record LineResponse(
            String lineId,
            String stationId,
            String fuelType,
            String sourceDepotId,
            String routeId,
            double reviewedQuantity,
            Double finalQuantity,
            String status,
            List<String> reasons,
            List<String> constraints,
            List<ProposalLine.Alternative> alternatives) {

        static LineResponse from(ProposalLine line) {
            return new LineResponse(
                    line.lineId(), line.stationId(), line.fuelType(), line.sourceDepotId(),
                    line.routeId(), line.reviewedQuantity(), line.finalQuantity(),
                    line.status().name(), line.reasons(), line.constraints(), line.alternatives());
        }
    }

    /**
     * The result of an approval.
     *
     * @param proposalId the proposal
     * @param status     the resulting status
     * @param lines      the per-line outcome
     * @param shippedQuantity the total that will be shipped
     * @param reviewedQuantity the total the operator reviewed
     * @param correlationId the id to quote when reporting a problem
     */
    public record ApprovalResponse(
            UUID proposalId,
            String status,
            List<ApprovalResult.LineOutcome> lines,
            double shippedQuantity,
            double reviewedQuantity,
            String correlationId) {

        static ApprovalResponse from(ApprovalResult result) {
            return new ApprovalResponse(
                    result.proposalId(),
                    result.status().name(),
                    result.lines(),
                    result.totalShippedQuantity(),
                    result.lines().stream().mapToDouble(ApprovalResult.LineOutcome::reviewedQuantity).sum(),
                    result.correlationId());
        }
    }

    /**
     * One shipment in the ledger.
     *
     * <p>The application's own command status and the simulator's shipment status
     * are <strong>separate columns</strong>. They are different things: the
     * command says what this application did, the simulator status says what the
     * world did with it. Showing them in one column would hide exactly the
     * distinction that matters — a command stuck in UNKNOWN while the simulator
     * says the shipment is IN_TRANSIT.
     *
     * @param commandId         the command id
     * @param proposalId        the proposal it came from
     * @param lineId            the proposal line
     * @param idempotencyKey    the key sent to the simulator
     * @param commandStatus     the application's own status
     * @param simulatorAllocationId the simulator's allocation id, once known
     * @param simulatorStatus   the simulator's own status, once known
     * @param attempts          how many times the write was attempted
     * @param lastError         the last failure
     * @param quantity          the quantity shipped
     * @param stationId         the destination
     * @param fuelType          the fuel
     */
    public record ShipmentResponse(
            UUID commandId,
            UUID proposalId,
            UUID proposalLineId,
            String idempotencyKey,
            String commandStatus,
            Long simulatorAllocationId,
            String simulatorStatus,
            int attempts,
            String lastError,
            Double quantity,
            String stationId,
            String fuelType,
            InstantField createdAt) {

        static ShipmentResponse from(Command command, ObjectMapper objectMapper) {
            Double quantity = null;
            String stationId = null;
            String fuelType = null;
            try {
                var node = objectMapper.readTree(command.requestBody());
                if (node.has("quantity")) {
                    quantity = node.get("quantity").asDouble();
                }
                if (node.has("destination_station_id")) {
                    stationId = node.get("destination_station_id").asText();
                }
                if (node.has("fuel_type")) {
                    fuelType = node.get("fuel_type").asText();
                }
            } catch (Exception ignored) {
                // The body was written by us, so this should not happen. If it
                // does, the ledger still shows the command and its status.
            }
            return new ShipmentResponse(
                    command.id(), command.proposalId(), command.proposalLineId(),
                    command.idempotencyKey(), command.status().name(),
                    command.simulatorAllocationId(), command.simulatorStatus(),
                    command.attempts(), command.lastError(),
                    quantity, stationId, fuelType,
                    new InstantField(command.createdAt().toString()));
        }
    }

    /** A timestamp, as a string. */
    public record InstantField(String value) {
    }

    /**
     * Maps an approval failure onto its own status and a machine-readable code.
     *
     * <p>The frontend switches on the code, so the codes are part of the contract.
     */
    @ExceptionHandler(ApprovalException.class)
    public ResponseEntity<ErrorResponse> handleApproval(ApprovalException e) {
        return ResponseEntity.status(e.error().httpStatus())
                .body(new ErrorResponse(e.error().code(), e.error().message()));
    }

    /**
     * An error the UI can act on.
     *
     * @param code    the machine-readable code
     * @param message what the operator should do about it
     */
    public record ErrorResponse(String code, String message) {
    }
}
