package com.fuelplatform.command;

import com.fuelplatform.ingestion.SnapshotIngestionService;
import com.fuelplatform.simulator.SimulatorClient;
import com.fuelplatform.simulator.dto.SimulatorDtos.Allocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Keeps the ledger's view of each shipment in step with the simulator.
 *
 * <p>A shipment is not finished when the application gets a 201. It then moves
 * through PENDING, IN_TRANSIT and finally ARRIVED, and the operator has to see
 * that. The application is not told when this happens — there is no callback —
 * so the only honest way to know is to read the simulator's allocation list and
 * match on the allocation id.
 *
 * <p>This is a <strong>read</strong> of simulator state. It never creates,
 * modifies or cancels anything. The write path is the command worker and
 * nothing else.
 *
 * <p>Matching is by allocation id, which the simulator gave us when it
 * confirmed the shipment. The idempotency key is the fallback for a command
 * whose allocation id is not yet known.
 */
@Component
public class ShipmentTracker {

    private static final Logger log = LoggerFactory.getLogger(ShipmentTracker.class);

    private final CommandRepository commandRepository;
    private final SimulatorClient simulatorClient;
    private final SnapshotIngestionService ingestionService;

    public ShipmentTracker(CommandRepository commandRepository,
                           SimulatorClient simulatorClient,
                           SnapshotIngestionService ingestionService) {
        this.commandRepository = commandRepository;
        this.simulatorClient = simulatorClient;
        this.ingestionService = ingestionService;
    }

    /**
     * Refreshes the simulator status of every sent command of the current run.
     *
     * <p>Runs every three seconds. The shipment lifecycle moves on the
     * simulator's clock, and in RUNNING mode that is fast — a shipment can
     * arrive between two operator glances.
     */
    @Scheduled(fixedDelay = 3000, initialDelay = 15000)
    public void trackShipments() {
        String runId = ingestionService.runId();
        track(commandRepository.findByRun(runId));
    }

    /**
     * Refreshes the simulator status of the given commands.
     *
     * <p>A command whose simulator status has already reached a terminal state
     * is left alone. Once a shipment has arrived, nothing about it can change,
     * and re-reading it would only add noise to the database.
     */
    public void track(List<Command> commands) {
        if (commands.isEmpty()) {
            return;
        }

        SimulatorClient.Result<List<Allocation>> result = simulatorClient.getAllocations();
        if (!result.isSuccess()) {
            // The simulator is unreachable. The ledger keeps the last known
            // status, which is the honest thing to show.
            return;
        }

        Map<Long, Allocation> byId = result.body().stream()
                .filter(allocation -> allocation.id() != null)
                .collect(Collectors.toMap(Allocation::id, allocation -> allocation));

        Map<String, Allocation> byKey = result.body().stream()
                .filter(allocation -> allocation.idempotencyKey() != null)
                .collect(Collectors.toMap(
                        Allocation::idempotencyKey,
                        allocation -> allocation,
                        (first, second) -> first));

        for (Command command : commands) {
            if (command.status() != Command.Status.SENT) {
                continue;
            }

            Allocation allocation = null;
            if (command.simulatorAllocationId() != null) {
                allocation = byId.get(command.simulatorAllocationId());
            }
            if (allocation == null) {
                allocation = byKey.get(command.idempotencyKey());
            }
            if (allocation == null) {
                continue;
            }

            String current = command.simulatorStatus();
            String observed = allocation.status();
            if (observed == null || observed.equals(current)) {
                continue;
            }

            // The command's own status does not change — it was sent, and it
            // stays sent. What changes is the simulator's view of it, and the
            // ledger shows that in its own column.
            commandRepository.updateSimulatorStatus(
                    command.id(),
                    allocation.id(),
                    observed);
            log.debug("shipment {} is now {}", allocation.id(), observed);
        }
    }
}
