package com.fuelplatform.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuelplatform.audit.AuditRepository;
import com.fuelplatform.simulator.SimulatorClient;
import com.fuelplatform.simulator.SimulatorClient.Result;
import com.fuelplatform.simulator.dto.SimulatorDtos.Allocation;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Performs the simulator write, once, and knows what to do when it does not
 * know what happened.
 *
 * <p>Three rules, in order of importance:
 *
 * <ol>
 *   <li><strong>The body never changes.</strong> A command stores the exact JSON
 *       that was built at approval time. The worker sends those bytes. A retry
 *       with a different body would be rejected by the simulator as an
 *       idempotency mismatch, and worse, would be a different shipment.</li>
 *   <li><strong>A timeout is UNKNOWN, never REJECTED.</strong> The shipment may
 *       exist. Saying "rejected" would let the operator retry and create a
 *       second one.</li>
 *   <li><strong>UNKNOWN is reconciled, not retried blindly.</strong> The
 *       reconciler matches the idempotency key against
 *       {@code GET /v1/allocations}. If the shipment is there, the command
 *       becomes SENT. If it is not, the command may be retried with the same
 *       key and the same body.</li>
 * </ol>
 */
@Service
public class CommandWorker {

    private static final Logger log = LoggerFactory.getLogger(CommandWorker.class);

    private final CommandRepository commandRepository;
    private final AuditRepository auditRepository;
    private final SimulatorClient simulatorClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    private final Counter sentCounter;
    private final Counter rejectedCounter;
    private final Counter unknownCounter;
    private final Counter reconciledCounter;

    public CommandWorker(CommandRepository commandRepository,
                         AuditRepository auditRepository,
                         SimulatorClient simulatorClient,
                         ObjectMapper objectMapper,
                         MeterRegistry meterRegistry,
                         Clock clock) {
        this.commandRepository = commandRepository;
        this.auditRepository = auditRepository;
        this.simulatorClient = simulatorClient;
        this.objectMapper = objectMapper;
        this.clock = clock;

        this.sentCounter = Counter.builder("fuel_commands_sent_total")
                .description("Shipments the simulator confirmed")
                .register(meterRegistry);
        this.rejectedCounter = Counter.builder("fuel_commands_rejected_total")
                .description("Shipments the simulator refused with a domain error")
                .register(meterRegistry);
        this.unknownCounter = Counter.builder("fuel_commands_unknown_total")
                .description("Shipments whose outcome is not known")
                .register(meterRegistry);
        this.reconciledCounter = Counter.builder("fuel_commands_reconciled_total")
                .description("Unknown shipments resolved by matching the idempotency key")
                .register(meterRegistry);
    }

    /**
     * Sends one command.
     *
     * <p>The command must already be persisted with its body and key. This method
     * only performs the write and records the outcome.
     */
    @Transactional
    public Command send(UUID commandId) {
        Optional<Command> found = commandRepository.findById(commandId);
        if (found.isEmpty()) {
            throw new IllegalArgumentException("no command with id " + commandId);
        }

        Command command = found.get();
        if (!command.status().isRetryable()) {
            // Already settled. Sending again would be the duplicate shipment the
            // whole design exists to prevent.
            log.info("command {} is already {}, not sending", commandId, command.status());
            return command;
        }

        Instant now = clock.instant();
        commandRepository.update(command.withStatus(
                Command.Status.SENDING, command.simulatorAllocationId(),
                command.simulatorStatus(), null, now));

        Object body;
        try {
            body = objectMapper.readTree(command.requestBody());
        } catch (Exception e) {
            // The stored body cannot be parsed, so it can never be sent correctly.
            Command failed = command.withStatus(Command.Status.FAILED, null, null,
                    "the stored request body is not valid JSON", now);
            commandRepository.update(failed);
            return failed;
        }

        Result<Allocation> result = simulatorClient.createAllocation(body);

        if (result.isSuccess()) {
            Allocation allocation = result.body();
            Command sent = command.withStatus(Command.Status.SENT,
                    allocation.id(), allocation.status(), null, now);
            commandRepository.update(sent);
            sentCounter.increment();

            auditRepository.record(command.runId(), "system", "COMMAND_SENT", "command",
                    command.id().toString(), "SENT", null,
                    AuditRepository.details(
                            "allocationId", allocation.id(),
                            "key", command.idempotencyKey()));
            return sent;
        }

        // The write failed. Whether the shipment exists is the question.
        SimulatorFailure failure = classify(result.error());

        Command updated = switch (failure.kind()) {
            case DOMAIN -> {
                rejectedCounter.increment();
                Command rejected = command.withStatus(Command.Status.REJECTED, null, null,
                        failure.detail(), now);
                commandRepository.update(rejected);
                auditRepository.record(command.runId(), "system", "COMMAND_REJECTED", "command",
                        command.id().toString(), "REJECTED", null,
                        AuditRepository.details(
                                "key", command.idempotencyKey(),
                                "error", failure.detail()));
                yield rejected;
            }
            case TRANSPORT -> {
                // UNKNOWN, not REJECTED. The shipment may exist.
                unknownCounter.increment();
                Command unknown = command.withStatus(Command.Status.UNKNOWN, null, null,
                        failure.detail(), now);
                commandRepository.update(unknown);
                auditRepository.record(command.runId(), "system", "COMMAND_UNKNOWN", "command",
                        command.id().toString(), "UNKNOWN", null,
                        AuditRepository.details(
                                "key", command.idempotencyKey(),
                                "error", failure.detail()));
                yield unknown;
            }
        };

        return updated;
    }

    /**
     * Reconciles one unknown command.
     *
     * <p>The simulator's allocation list is searched for the idempotency key. If
     * the shipment is there, the command becomes SENT and no new shipment is
     * created. If it is not there, the command stays UNKNOWN and may be retried
     * later with the same key and the same body.
     */
    @Transactional
    public Command reconcile(UUID commandId) {
        Optional<Command> found = commandRepository.findById(commandId);
        if (found.isEmpty()) {
            throw new IllegalArgumentException("no command with id " + commandId);
        }

        Command command = found.get();
        if (command.status() != Command.Status.UNKNOWN) {
            return command;
        }

        Result<List<Allocation>> allocations = simulatorClient.getAllocations();
        if (!allocations.isSuccess()) {
            // Still cannot tell. Leave it UNKNOWN rather than guessing.
            return command;
        }

        for (Allocation allocation : allocations.body()) {
            if (command.idempotencyKey().equals(allocation.idempotencyKey())) {
                Command reconciled = command.withStatus(Command.Status.SENT,
                        allocation.id(), allocation.status(),
                        "reconciled: the shipment already existed", clock.instant());
                commandRepository.update(reconciled);
                reconciledCounter.increment();

                auditRepository.record(command.runId(), "system", "COMMAND_RECONCILED", "command",
                        command.id().toString(), "SENT", null,
                        AuditRepository.details(
                                "allocationId", allocation.id(),
                                "key", command.idempotencyKey()));
                return reconciled;
            }
        }

        // The shipment is not there. The command may be retried, and because the
        // key and body are unchanged, a retry cannot create a duplicate.
        Command stillUnknown = command.withStatus(Command.Status.UNKNOWN, null, null,
                "reconciled: no matching shipment exists yet, the command may be retried",
                clock.instant());
        commandRepository.update(stillUnknown);
        return stillUnknown;
    }

    /** Reconciles every unresolved command of a run. */
    public List<Command> reconcileAll(String runId) {
        List<Command> reconciled = new java.util.ArrayList<>();
        for (Command command : commandRepository.findUnresolved(runId)) {
            if (command.status() == Command.Status.UNKNOWN) {
                reconciled.add(reconcile(command.id()));
            } else if (command.status() == Command.Status.APPROVED) {
                reconciled.add(send(command.id()));
            }
        }
        return reconciled;
    }

    /** The commands of a run, for the shipment ledger. */
    public List<Command> ledger(String runId) {
        return commandRepository.findAll(runId);
    }

    /** Classifies a simulator failure into the three kinds that matter. */
    static SimulatorFailure classify(com.fuelplatform.simulator.SimulatorError error) {
        if (error == null) {
            return new SimulatorFailure(FailureKind.DOMAIN, "the simulator returned no error detail");
        }
        return switch (error) {
            case com.fuelplatform.simulator.SimulatorError.Domain domain ->
                    new SimulatorFailure(FailureKind.DOMAIN, domain.code() + ": " + domain.message());
            case com.fuelplatform.simulator.SimulatorError.Validation validation ->
                    new SimulatorFailure(FailureKind.DOMAIN, validation.summary());
            case com.fuelplatform.simulator.SimulatorError.Fault fault ->
                    new SimulatorFailure(FailureKind.TRANSPORT, fault.message());
            case com.fuelplatform.simulator.SimulatorError.Unparseable unparseable ->
                    new SimulatorFailure(FailureKind.TRANSPORT, "unparseable error: " + unparseable.reason());
        };
    }

    /** How a failure was classified. */
    enum FailureKind {
        /** The simulator understood the request and refused it. No shipment exists. */
        DOMAIN,
        /** The request may or may not have reached the simulator. */
        TRANSPORT
    }

    /** A classified failure. */
    record SimulatorFailure(FailureKind kind, String detail) {
    }
}
