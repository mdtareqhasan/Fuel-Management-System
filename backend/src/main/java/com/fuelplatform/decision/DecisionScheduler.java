package com.fuelplatform.decision;

import com.fuelplatform.command.Command;
import com.fuelplatform.command.CommandRepository;
import com.fuelplatform.command.CommandWorker;
import com.fuelplatform.ingestion.SnapshotIngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * The background half of the decision loop.
 *
 * <p>Two jobs, and both are about <strong>not losing work</strong> rather than
 * about doing anything clever.
 *
 * <ol>
 *   <li><strong>Send the approved commands.</strong> The approval endpoint
 *       creates the command and the worker sends it, but if the process died
 *       between the two, the command would sit as APPROVED forever. This picks
 *       it up.</li>
 *   <li><strong>Reconcile the unknown commands.</strong> A command whose outcome
 *       is not known is matched against the simulator's allocation list by its
 *       idempotency key. If the shipment is there, the command becomes SENT and
 *       <strong>no new shipment is created</strong>.</li>
 * </ol>
 *
 * <p>Neither job creates a shipment on its own. A shipment only exists because
 * an operator approved it.
 */
@Component
public class DecisionScheduler {

    private static final Logger log = LoggerFactory.getLogger(DecisionScheduler.class);

    private final CommandRepository commandRepository;
    private final CommandWorker commandWorker;
    private final ProposalService proposalService;
    private final SnapshotIngestionService ingestionService;

    public DecisionScheduler(CommandRepository commandRepository,
                             CommandWorker commandWorker,
                             ProposalService proposalService,
                             SnapshotIngestionService ingestionService) {
        this.commandRepository = commandRepository;
        this.commandWorker = commandWorker;
        this.proposalService = proposalService;
        this.ingestionService = ingestionService;
    }

    /**
     * Sends any command that was approved but never sent.
     *
     * <p>Runs every five seconds. The command's idempotency key and body were
     * frozen at approval time, so sending it later is exactly as safe as sending
     * it immediately — and the unique constraint on the proposal line means it
     * can only ever be sent once.
     */
    @Scheduled(fixedDelay = 5000, initialDelay = 8000)
    public void sendApprovedCommands() {
        String runId = ingestionService.runId();
        for (Command command : commandRepository.findUnresolved(runId)) {
            if (command.status() == Command.Status.APPROVED) {
                try {
                    commandWorker.send(command.id());
                } catch (Exception e) {
                    log.warn("could not send command {}: {}", command.id(), e.getMessage());
                }
            }
        }
    }

    /**
     * Reconciles the commands whose outcome is not known.
     *
     * <p>Runs every ten seconds. The key point is that this never creates a
     * shipment: it only <em>looks</em> for one.
     */
    @Scheduled(fixedDelay = 10000, initialDelay = 12000)
    public void reconcileUnknownCommands() {
        String runId = ingestionService.runId();
        List<Command> unresolved = commandRepository.findUnresolved(runId);
        for (Command command : unresolved) {
            if (command.status() == Command.Status.UNKNOWN) {
                try {
                    commandWorker.reconcile(command.id());
                } catch (Exception e) {
                    log.warn("could not reconcile command {}: {}", command.id(), e.getMessage());
                }
            }
        }
    }

    /**
     * Creates a proposal when the world needs a decision and nothing is pending.
     *
     * <p>This is what makes the demo flow work without the operator pressing a
     * button first: a proposal appears in the review list, the operator reviews
     * it, and the approval is what actually ships.
     */
    @Scheduled(fixedDelay = 15000, initialDelay = 10000)
    public void createProposalIfNeeded() {
        try {
            Optional<Proposal> created = proposalService.createIfNothingPending();
            created.ifPresent(proposal -> log.info(
                    "created proposal {} with {} lines at tick {}",
                    proposal.id(), proposal.lines().size(), proposal.proposedAtTick()));
        } catch (Exception e) {
            log.warn("could not create a proposal: {}", e.getMessage());
        }
    }
}
