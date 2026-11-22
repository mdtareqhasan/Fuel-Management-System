package com.fuelplatform.decision;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuelplatform.audit.AuditRepository;
import com.fuelplatform.command.Command;
import com.fuelplatform.command.CommandRepository;
import com.fuelplatform.decision.ApprovalResult.LineOutcome;
import com.fuelplatform.domain.DataQuality;
import com.fuelplatform.domain.WorldSnapshot;
import com.fuelplatform.ingestion.SnapshotIngestionService;
import com.fuelplatform.simulator.dto.SimulatorDtos.Depot;
import com.fuelplatform.simulator.dto.SimulatorDtos.Instance;
import com.fuelplatform.simulator.dto.SimulatorDtos.Route;
import com.fuelplatform.simulator.dto.SimulatorDtos.Station;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the approval service.
 *
 * <p>These are the tests behind the Day 3 acceptance criteria:
 *
 * <ul>
 *   <li>a duplicate approval produces one simulator allocation</li>
 *   <li>the approved quantity never exceeds the reviewed quantity</li>
 *   <li>a stale or invalid snapshot blocks the approval</li>
 *   <li>a changed route produces {@code PROPOSAL_CHANGED} and no write</li>
 * </ul>
 *
 * <p>The repositories are in-memory fakes rather than mocks. The behaviour under
 * test <strong>is</strong> the interaction with the repository — a duplicate
 * approval is only a duplicate because the command repository refuses to hold
 * two commands for one line — so mocking that away would test nothing.
 */
class ApprovalServiceTest {

    private static final String FUEL = "DIESEL";
    private static final String RUN_ID = "11111111-1111-1111-1111-111111111111";

    private FakeProposalRepository proposals;
    private FakeCommandRepository commands;
    private FakeAuditRepository audit;
    private FakeIngestion ingestion;
    private ApprovalService service;
    private Clock clock;

    @BeforeEach
    void setUp() {
        proposals = new FakeProposalRepository();
        commands = new FakeCommandRepository();
        audit = new FakeAuditRepository();
        ingestion = new FakeIngestion();
        clock = Clock.fixed(Instant.parse("2026-11-22T10:00:00Z"), ZoneOffset.UTC);
        service = new ApprovalService(
                proposals, new FakeSnapshotRepository(), commands, audit, ingestion,
                new ProposalFactory(),
                new FreshnessPolicy(new com.fuelplatform.config.DecisionProperties(10, 120, 1, 50000)),
                clock);
    }

    @Test
    @DisplayName("a fresh proposal is approved and creates one command per line")
    void freshProposalIsApproved() {
        Proposal proposal = pendingProposal(5000);
        proposals.insert(proposal);
        ingestion.setSnapshot(world("RUNNING", 40L, "OPEN", "OPEN", "AVAILABLE", 50000, 10000));

        ApprovalResult result = service.approve(proposal.id(), "operator-1", Map.of());

        assertThat(result.status()).isEqualTo(Proposal.Status.APPROVED);
        assertThat(result.shippedAnything()).isTrue();
        assertThat(result.totalShippedQuantity()).isEqualTo(5000);
        assertThat(commands.commands).hasSize(1);
        assertThat(result.correlationId()).isNotBlank();
    }

    @Test
    @DisplayName("a duplicate approval produces exactly one command and one shipment")
    void duplicateApprovalProducesOneShipment() {
        Proposal proposal = pendingProposal(5000);
        proposals.insert(proposal);
        ingestion.setSnapshot(world("RUNNING", 40L, "OPEN", "OPEN", "AVAILABLE", 50000, 10000));

        ApprovalResult first = service.approve(proposal.id(), "operator-1", Map.of());

        // The operator clicks approve again — a double click, or a second tab.
        assertThatThrownBy(() -> service.approve(proposal.id(), "operator-1", Map.of()))
                .isInstanceOf(ApprovalException.class)
                .satisfies(thrown -> assertThat(((ApprovalException) thrown).error())
                        .isEqualTo(ApprovalError.ALREADY_APPROVED));

        // One proposal line means one command means one shipment.
        assertThat(commands.commands).hasSize(1);
        assertThat(first.commandIds()).hasSize(1);
    }

    @Test
    @DisplayName("the idempotency key and body are fixed before the simulator is contacted")
    void keyAndBodyAreFixedBeforeTheWrite() {
        Proposal proposal = pendingProposal(5000);
        proposals.insert(proposal);
        ingestion.setSnapshot(world("RUNNING", 40L, "OPEN", "OPEN", "AVAILABLE", 50000, 10000));

        service.approve(proposal.id(), "operator-1", Map.of());

        Command command = commands.commands.get(0);
        assertThat(command.idempotencyKey()).isNotBlank();
        assertThat(command.requestBody()).contains("\"idempotency_key\":\"" + command.idempotencyKey() + "\"");
        assertThat(command.requestBody()).contains("\"quantity\":5000");
        assertThat(command.requestBody()).contains("\"destination_station_id\":\"station-mirpur\"");
        assertThat(command.requestBody()).contains("\"fuel_type\":\"DIESEL\"");
        // The command is recorded as APPROVED, waiting for the worker.
        assertThat(command.status()).isEqualTo(Command.Status.APPROVED);
    }

    @Test
    @DisplayName("a quantity larger than the reviewed one is rejected")
    void quantityAboveReviewedIsRejected() {
        Proposal proposal = pendingProposal(5000);
        proposals.insert(proposal);
        ingestion.setSnapshot(world("RUNNING", 40L, "OPEN", "OPEN", "AVAILABLE", 50000, 10000));

        assertThatThrownBy(() -> service.approve(
                proposal.id(), "operator-1", Map.<String, Double>of("line-1", 5001.0)))
                .isInstanceOf(ApprovalException.class)
                .satisfies(thrown -> assertThat(((ApprovalException) thrown).error())
                        .isEqualTo(ApprovalError.QUANTITY_EXCEEDS_REVIEWED));

        assertThat(commands.commands).isEmpty();
    }

    @Test
    @DisplayName("a quantity lower than the reviewed one is accepted")
    void quantityBelowReviewedIsAccepted() {
        Proposal proposal = pendingProposal(5000);
        proposals.insert(proposal);
        ingestion.setSnapshot(world("RUNNING", 40L, "OPEN", "OPEN", "AVAILABLE", 50000, 10000));

        ApprovalResult result = service.approve(
                proposal.id(), "operator-1", Map.of("line-1", 2500.0));

        assertThat(result.totalShippedQuantity()).isEqualTo(2500);
        assertThat(result.lines().get(0).wasReduced()).isTrue();
        assertThat(commands.commands.get(0).requestBody()).contains("\"quantity\":2500");
    }

    @Test
    @DisplayName("a stale snapshot blocks the approval and writes nothing")
    void staleSnapshotBlocksApproval() {
        Proposal proposal = pendingProposal(5000);
        proposals.insert(proposal);
        ingestion.setSnapshot(world("RUNNING", 40L, "OPEN", "OPEN", "AVAILABLE", 50000, 10000));
        // The world goes stale between the proposal and the approval.
        ingestion.markIneligible("simulator reported stale data");

        assertThatThrownBy(() -> service.approve(proposal.id(), "operator-1", Map.of()))
                .isInstanceOf(ApprovalException.class)
                .satisfies(thrown -> assertThat(((ApprovalException) thrown).error())
                        .isEqualTo(ApprovalError.STALE_OR_INVALID_SNAPSHOT));

        assertThat(commands.commands).isEmpty();
    }

    @Test
    @DisplayName("a route that is no longer available changes the line and writes nothing")
    void changedRouteProducesNoWrite() {
        Proposal proposal = pendingProposal(5000);
        proposals.insert(proposal);
        // The route was disrupted while the operator was reading.
        ingestion.setSnapshot(world("RUNNING", 40L, "OPEN", "OPEN", "DISRUPTED", 50000, 10000));

        ApprovalResult result = service.approve(proposal.id(), "operator-1", Map.of());

        assertThat(result.status()).isEqualTo(Proposal.Status.CHANGED);
        assertThat(result.shippedAnything()).isFalse();
        assertThat(result.lines().get(0).status()).isEqualTo(ProposalLine.Status.CHANGED);
        assertThat(result.lines().get(0).reason()).contains("DISRUPTED");
        assertThat(commands.commands).isEmpty();
    }

    @Test
    @DisplayName("a station in OUTAGE changes the line and writes nothing")
    void stationInOutageProducesNoWrite() {
        Proposal proposal = pendingProposal(5000);
        proposals.insert(proposal);
        ingestion.setSnapshot(world("RUNNING", 40L, "OUTAGE", "OPEN", "AVAILABLE", 50000, 10000));

        ApprovalResult result = service.approve(proposal.id(), "operator-1", Map.of());

        assertThat(result.status()).isEqualTo(Proposal.Status.CHANGED);
        assertThat(commands.commands).isEmpty();
    }

    @Test
    @DisplayName("a world that can only carry less ships less and says so")
    void reducedWorldShipsLess() {
        Proposal proposal = pendingProposal(5000);
        proposals.insert(proposal);
        // The depot only has 3000 litres left.
        ingestion.setSnapshot(world("RUNNING", 40L, "OPEN", "OPEN", "AVAILABLE", 3000, 10000));

        ApprovalResult result = service.approve(proposal.id(), "operator-1", Map.of());

        assertThat(result.status()).isEqualTo(Proposal.Status.APPROVED_WITH_REDUCTION);
        assertThat(result.totalShippedQuantity()).isEqualTo(3000);
        assertThat(result.totalShippedQuantity()).isLessThan(5000);

        LineOutcome outcome = result.lines().get(0);
        assertThat(outcome.status()).isEqualTo(ProposalLine.Status.APPROVED_REDUCED);
        assertThat(outcome.wasReduced()).isTrue();
        assertThat(outcome.reason()).contains("3000");
    }

    @Test
    @DisplayName("approving in PAUSED after the clock was stepped is blocked")
    void pausedAfterStepIsBlocked() {
        Proposal proposal = pendingProposal(5000, "PAUSED");
        proposals.insert(proposal);
        // The proposal was made at tick 40 and the operator stepped to tick 48.
        ingestion.setSnapshot(world("PAUSED", 48L, "OPEN", "OPEN", "AVAILABLE", 50000, 10000));

        assertThatThrownBy(() -> service.approve(proposal.id(), "operator-1", Map.of()))
                .isInstanceOf(ApprovalException.class)
                .satisfies(thrown -> assertThat(((ApprovalException) thrown).error())
                        .isEqualTo(ApprovalError.PROPOSAL_CHANGED));

        assertThat(commands.commands).isEmpty();
    }

    @Test
    @DisplayName("approving in PAUSED at the same tick works")
    void pausedAtSameTickWorks() {
        Proposal proposal = pendingProposal(5000, "PAUSED");
        proposals.insert(proposal);
        ingestion.setSnapshot(world("PAUSED", 40L, "OPEN", "OPEN", "AVAILABLE", 50000, 10000));

        ApprovalResult result = service.approve(proposal.id(), "operator-1", Map.of());

        assertThat(result.status()).isEqualTo(Proposal.Status.APPROVED);
        assertThat(commands.commands).hasSize(1);
    }

    @Test
    @DisplayName("approving in RUNNING after the world moved works, because the revalidation is the control")
    void runningWithAMovedTickStillWorks() {
        Proposal proposal = pendingProposal(5000);
        proposals.insert(proposal);
        // The tick moved 164 ticks while the operator was reading.
        ingestion.setSnapshot(world("RUNNING", 204L, "OPEN", "OPEN", "AVAILABLE", 50000, 10000));

        ApprovalResult result = service.approve(proposal.id(), "operator-1", Map.of());

        assertThat(result.status()).isEqualTo(Proposal.Status.APPROVED);
        assertThat(commands.commands).hasSize(1);
    }

    @Test
    @DisplayName("the audit trail records the approval and the decision")
    void auditTrailRecordsTheDecision() {
        Proposal proposal = pendingProposal(5000);
        proposals.insert(proposal);
        ingestion.setSnapshot(world("RUNNING", 40L, "OPEN", "OPEN", "AVAILABLE", 50000, 10000));

        service.approve(proposal.id(), "operator-1", Map.of());

        assertThat(audit.events).isNotEmpty();
        assertThat(audit.events.stream().anyMatch(e -> e.action().equals("APPROVAL"))).isTrue();
        assertThat(audit.events.stream().anyMatch(e -> e.actor().equals("operator-1"))).isTrue();
    }

    // ------------------------------------------------------------- fixtures

    private static Proposal pendingProposal(double quantity) {
        return pendingProposal(quantity, "RUNNING");
    }

    private static Proposal pendingProposal(double quantity, String mode) {
        UUID proposalId = UUID.randomUUID();
        ProposalLine line = new ProposalLine(
                UUID.randomUUID(), proposalId, "line-1",
                "station-mirpur", FUEL, "depot-gazipur", "route-1",
                quantity, null, 44L, 2.0, 8.0,
                List.of("station is running low"), List.of("depot stock"),
                List.of(), ProposalLine.Status.PENDING);

        return new Proposal(
                proposalId, RUN_ID, UUID.randomUUID(), "policy-1", 40L, mode,
                Proposal.Status.PENDING_REVIEW, Instant.parse("2026-11-22T10:00:00Z"),
                quantity, List.of(line));
    }

    private static WorldSnapshot world(String mode, long tick, String stationStatus,
                                       String depotStatus, String routeStatus,
                                       double depotStock, double stationCapacity) {
        Station station = new Station("station-mirpur", "Mirpur", "region-dhaka",
                stationStatus, "urban_high", 1.0,
                Map.of(FUEL, stationCapacity), Map.of(FUEL, 2000.0));
        Depot depot = new Depot("depot-gazipur", "Gazipur", "region-dhaka",
                depotStatus, 12000.0, Map.of(FUEL, 60000.0), Map.of(FUEL, depotStock));
        Route route = new Route("route-1", "depot-gazipur", "station-mirpur", 2, 8000.0, routeStatus);

        return new WorldSnapshot(
                RUN_ID, UUID.randomUUID().toString(),
                tick, tick, "2026-11-22T10:00:00", Instant.parse("2026-11-22T10:00:00Z"),
                DataQuality.fresh(), List.of(),
                new Instance(1, "scenario-fuel", "1.0", 42, "2026-11-22T10:00:00", tick, 15, mode),
                List.of(), List.of(depot), List.of(station), List.of(route),
                List.of(), List.of(), List.of());
    }

    // -------------------------------------------------------- fake adapters

    /** A proposal repository held in memory. */
    private static final class FakeProposalRepository extends ProposalRepository {
        private final Map<UUID, Proposal> store = new LinkedHashMap<>();

        FakeProposalRepository() {
            super(null);
        }

        @Override
        public void insert(Proposal proposal) {
            store.put(proposal.id(), proposal);
        }

        @Override
        public void insertLine(ProposalLine line) {
            // The lines travel with the proposal in this fake.
        }

        @Override
        public void updateLineDecision(ProposalLine line) {
            Proposal proposal = store.get(line.proposalId());
            List<ProposalLine> lines = new ArrayList<>();
            for (ProposalLine existing : proposal.lines()) {
                lines.add(existing.lineId().equals(line.lineId()) ? line : existing);
            }
            store.put(proposal.id(), new Proposal(
                    proposal.id(), proposal.runId(), proposal.snapshotId(), proposal.policyVersion(),
                    proposal.proposedAtTick(), proposal.mode(), proposal.status(), proposal.createdAt(),
                    proposal.reviewedQuantity(), lines));
        }

        @Override
        public void updateStatus(UUID proposalId, Proposal.Status status) {
            Proposal proposal = store.get(proposalId);
            store.put(proposalId, new Proposal(
                    proposal.id(), proposal.runId(), proposal.snapshotId(), proposal.policyVersion(),
                    proposal.proposedAtTick(), proposal.mode(), status, proposal.createdAt(),
                    proposal.reviewedQuantity(), proposal.lines()));
        }

        @Override
        public Optional<Proposal> find(UUID id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public List<Proposal> findByRun(String runId) {
            return new ArrayList<>(store.values());
        }

        @Override
        public List<Proposal> findPending(String runId) {
            return findByRun(runId).stream().filter(p -> p.status().isOpen()).toList();
        }
    }

    /** A snapshot repository held in memory. */
    private static final class FakeSnapshotRepository extends SnapshotRepository {
        FakeSnapshotRepository() {
            super(null, null);
        }

        @Override
        public java.util.UUID store(com.fuelplatform.domain.WorldSnapshot snapshot) {
            return java.util.UUID.fromString(snapshot.snapshotId());
        }
    }

    /** A command repository held in memory. */
    private static final class FakeCommandRepository extends CommandRepository {
        private final List<Command> commands = new ArrayList<>();

        FakeCommandRepository() {
            super(null);
        }

        @Override
        public Command insert(Command command) {
            // The real repository refuses a second command for one line. The
            // fake does the same, because that refusal IS the duplicate
            // protection.
            boolean exists = commands.stream()
                    .anyMatch(c -> c.proposalLineId().equals(command.proposalLineId()));
            if (exists) {
                return commands.stream()
                        .filter(c -> c.proposalLineId().equals(command.proposalLineId()))
                        .findFirst()
                        .orElseThrow();
            }
            commands.add(command);
            return command;
        }

        @Override
        public void update(Command command) {
            commands.removeIf(c -> c.id().equals(command.id()));
            commands.add(command);
        }

        @Override
        public Optional<Command> findByLine(UUID proposalLineId) {
            return commands.stream()
                    .filter(c -> c.proposalLineId().equals(proposalLineId))
                    .findFirst();
        }

        @Override
        public Optional<Command> findByKey(String idempotencyKey) {
            return commands.stream()
                    .filter(c -> c.idempotencyKey().equals(idempotencyKey))
                    .findFirst();
        }

        @Override
        public Optional<Command> findById(UUID id) {
            return commands.stream().filter(c -> c.id().equals(id)).findFirst();
        }

        @Override
        public List<Command> findByRun(String runId) {
            return new ArrayList<>(commands);
        }

        @Override
        public List<Command> findUnresolved(String runId) {
            return commands.stream().filter(c -> c.status().isUnresolved()).toList();
        }

        @Override
        public List<Command> findAll(String runId) {
            return new ArrayList<>(commands);
        }
    }

    /** An audit repository held in memory. */
    private static final class FakeAuditRepository extends AuditRepository {
        private final List<AuditRepository.AuditEvent> events = new ArrayList<>();

        FakeAuditRepository() {
            super(null, new ObjectMapper());
        }

        @Override
        public void record(String runId, String actor, String action, String entityType,
                           String entityId, String outcome, String correlationId,
                           Map<String, Object> details) {
            events.add(new AuditRepository.AuditEvent(
                    UUID.randomUUID(), Instant.now(), actor, action, entityType,
                    entityId, outcome, correlationId, String.valueOf(details)));
        }
    }

    /** An ingestion service that returns a fixed world. */
    private static final class FakeIngestion extends SnapshotIngestionService {
        private WorldSnapshot snapshot;
        private boolean ineligible;

        FakeIngestion() {
            super(null,
                    new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                    Clock.systemUTC(),
                    new com.fuelplatform.observability.HealthRegistry(
                            new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
        }

        void setSnapshot(WorldSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        void markIneligible(String reason) {
            this.snapshot = new WorldSnapshot(
                    snapshot.runId(), snapshot.snapshotId(), snapshot.tickStart(), snapshot.tickEnd(),
                    snapshot.simTime(), snapshot.observedAt(),
                    DataQuality.ineligible(reason), List.of(reason),
                    snapshot.instance(), snapshot.regions(), snapshot.depots(), snapshot.stations(),
                    snapshot.routes(), snapshot.supplyArrivals(), snapshot.events(), snapshot.allocations());
        }

        @Override
        public WorldSnapshot currentSnapshot() {
            return snapshot;
        }

        @Override
        public String runId() {
            return RUN_ID;
        }
    }
}
