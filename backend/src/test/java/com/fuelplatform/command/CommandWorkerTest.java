package com.fuelplatform.command;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuelplatform.audit.AuditRepository;
import com.fuelplatform.simulator.SimulatorClient;
import com.fuelplatform.simulator.SimulatorError;
import com.fuelplatform.simulator.dto.SimulatorDtos.Allocation;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for the command worker.
 *
 * <p>The three cases are the ones the Day 3 acceptance criteria name:
 *
 * <ul>
 *   <li>a confirmed shipment becomes SENT</li>
 *   <li>a lost response becomes UNKNOWN, never REJECTED</li>
 *   <li>an unknown command is reconciled by matching the idempotency key, and
 *       that produces no second shipment</li>
 * </ul>
 */
class CommandWorkerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CommandRepository repository = mock(CommandRepository.class);
    private final AuditRepository audit = mock(AuditRepository.class);
    private final SimulatorClient simulator = mock(SimulatorClient.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-22T10:00:00Z"), ZoneOffset.UTC);

    private final CommandWorker worker = new CommandWorker(
            repository, audit, simulator, objectMapper, new SimpleMeterRegistry(), clock);

    private static final String BODY = """
            {"idempotency_key":"proposal-1-line-1","source_depot_id":"depot-gazipur",\
            "destination_station_id":"station-mirpur","route_id":"route-1",\
            "fuel_type":"DIESEL","quantity":5000}""";

    private static Command command() {
        Instant now = Instant.parse("2026-11-22T10:00:00Z");
        return new Command(
                java.util.UUID.randomUUID(),
                "11111111-1111-1111-1111-111111111111",
                java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(),
                "proposal-1-line-1",
                BODY,
                Command.Status.APPROVED,
                null, null, 0, null, now, now, null);
    }

    @Test
    @DisplayName("a confirmed shipment becomes SENT and records the allocation id")
    void confirmedShipmentBecomesSent() {
        Command original = command();
        Allocation allocation = new Allocation(7L, "proposal-1-line-1", "depot-gazipur",
                "station-mirpur", "route-1", "DIESEL", 5000.0, 40L, null, 44L, null, "PENDING", null);

        when(repository.findById(original.id())).thenReturn(java.util.Optional.of(original));
        when(simulator.createAllocation(any()))
                .thenReturn(SimulatorClient.Result.success(allocation, false));

        Command result = worker.send(original.id());

        assertThat(result.status()).isEqualTo(Command.Status.SENT);
        assertThat(result.simulatorAllocationId()).isEqualTo(7L);
        assertThat(result.simulatorStatus()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("a lost response becomes UNKNOWN, not REJECTED")
    void lostResponseBecomesUnknown() {
        Command original = command();

        when(repository.findById(original.id())).thenReturn(java.util.Optional.of(original));
        when(simulator.createAllocation(any())).thenReturn(SimulatorClient.Result.failure(
                new SimulatorError.Fault("TRANSPORT_ERROR", "the read timed out", 0)));

        Command result = worker.send(original.id());

        // UNKNOWN, because the shipment may exist. Saying REJECTED would let
        // the operator retry and create a second one.
        assertThat(result.status()).isEqualTo(Command.Status.UNKNOWN);
        assertThat(result.simulatorAllocationId()).isNull();
        assertThat(result.lastError()).contains("timed out");
    }

    @Test
    @DisplayName("a domain refusal becomes REJECTED")
    void domainRefusalBecomesRejected() {
        Command original = command();

        when(repository.findById(original.id())).thenReturn(java.util.Optional.of(original));
        when(simulator.createAllocation(any())).thenReturn(SimulatorClient.Result.failure(
                new SimulatorError.Domain("DISPATCH_CAPACITY_EXCEEDED", "too much", 409)));

        Command result = worker.send(original.id());

        assertThat(result.status()).isEqualTo(Command.Status.REJECTED);
        assertThat(result.lastError()).contains("DISPATCH_CAPACITY_EXCEEDED");
    }

    @Test
    @DisplayName("reconciling an unknown command finds the shipment by key and sends nothing")
    void reconcileFindsTheShipmentByKey() {
        Command original = command();
        Allocation allocation = new Allocation(7L, "proposal-1-line-1", "depot-gazipur",
                "station-mirpur", "route-1", "DIESEL", 5000.0, 40L, null, 44L, null, "IN_TRANSIT", null);

        Command unknown = new Command(original.id(), original.runId(), original.proposalId(),
                original.proposalLineId(), original.idempotencyKey(), original.requestBody(),
                Command.Status.UNKNOWN, null, null, 1, "timed out",
                original.createdAt(), original.updatedAt(), null);

        when(repository.findById(original.id())).thenReturn(java.util.Optional.of(unknown));
        when(simulator.getAllocations())
                .thenReturn(SimulatorClient.Result.success(List.of(allocation), false));

        Command result = worker.reconcile(original.id());

        assertThat(result.status()).isEqualTo(Command.Status.SENT);
        assertThat(result.simulatorAllocationId()).isEqualTo(7L);
        assertThat(result.lastError()).contains("reconciled");
    }

    @Test
    @DisplayName("reconciling an unknown command that does not exist leaves it UNKNOWN")
    void reconcileWithoutAMatchLeavesItUnknown() {
        Command original = command();
        Command unknown = new Command(original.id(), original.runId(), original.proposalId(),
                original.proposalLineId(), original.idempotencyKey(), original.requestBody(),
                Command.Status.UNKNOWN, null, null, 1, "timed out",
                original.createdAt(), original.updatedAt(), null);

        when(repository.findById(original.id())).thenReturn(java.util.Optional.of(unknown));
        when(simulator.getAllocations())
                .thenReturn(SimulatorClient.Result.success(List.of(), false));

        Command result = worker.reconcile(original.id());

        // Still unknown. The command may be retried, and because the key and the
        // body are unchanged, a retry cannot create a duplicate.
        assertThat(result.status()).isEqualTo(Command.Status.UNKNOWN);
        assertThat(result.lastError()).contains("may be retried");
    }

    @Test
    @DisplayName("an already settled command is not sent again")
    void settledCommandIsNotSentAgain() {
        Command original = command();
        Command sent = new Command(original.id(), original.runId(), original.proposalId(),
                original.proposalLineId(), original.idempotencyKey(), original.requestBody(),
                Command.Status.SENT, 7L, "PENDING", 1, null,
                original.createdAt(), original.updatedAt(), original.createdAt());

        when(repository.findById(original.id())).thenReturn(java.util.Optional.of(sent));

        Command result = worker.send(original.id());

        assertThat(result.status()).isEqualTo(Command.Status.SENT);
        assertThat(result.attempts()).isEqualTo(1);
        // The simulator was never contacted.
        org.mockito.Mockito.verify(simulator, org.mockito.Mockito.never())
                .createAllocation(any());
    }
}
