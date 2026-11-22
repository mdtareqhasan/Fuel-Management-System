package com.fuelplatform.decision;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Stores and reads proposals and their lines.
 *
 * <p>Plain JDBC rather than JPA, for two reasons.
 *
 * <p>First, the invariants matter more than the convenience. The rule that a
 * final quantity may never exceed the reviewed quantity is enforced by a
 * {@code CHECK} constraint in the schema, and reading the code makes that
 * visible. An ORM mapping would hide it behind an entity definition.
 *
 * <p>Second, the domain records are immutable and the mapping is mechanical.
 * JPA would add a mutable shadow of every record without removing a single
 * line of mapping code.
 */
@Repository
public class ProposalRepository {

    private final JdbcTemplate jdbc;

    public ProposalRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ write

    /**
     * Stores a proposal and all of its lines in one transaction.
     *
     * <p>The caller runs this inside the approval transaction, so a proposal and
     * the decision made on it commit together or not at all.
     */
    public void insert(Proposal proposal) {
        jdbc.update("""
                        INSERT INTO proposals
                            (id, run_id, snapshot_id, policy_version, proposed_at_tick,
                             mode, status, created_at, reviewed_quantity)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                proposal.id(), proposal.runId(), proposal.snapshotId(),
                proposal.policyVersion(), proposal.proposedAtTick(),
                proposal.mode(), proposal.status().name(),
                java.sql.Timestamp.from(proposal.createdAt()),
                proposal.reviewedQuantity());

        for (ProposalLine line : proposal.lines()) {
            insertLine(line);
        }
    }

    /** Stores one line. */
    public void insertLine(ProposalLine line) {
        jdbc.update("""
                        INSERT INTO proposal_lines
                            (id, proposal_id, line_id, station_id, fuel_type, source_depot_id,
                             route_id, reviewed_quantity, final_quantity, estimated_arrival_tick,
                             cover_before_hours, cover_after_hours, reasons, constraints,
                             alternatives, status, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                        """,
                line.id(), line.proposalId(), line.lineId(), line.stationId(), line.fuelType(),
                line.sourceDepotId(), line.routeId(), line.reviewedQuantity(),
                line.finalQuantity(), line.estimatedArrivalTick(),
                line.coverBeforeHours(), line.coverAfterHours(),
                joinLines(line.reasons()), joinLines(line.constraints()),
                toJson(line.alternatives()), line.status().name(),
                java.sql.Timestamp.from(Instant.now()));
    }

    /** Records the decided quantity and status for one line. */
    public void updateLineDecision(ProposalLine line) {
        jdbc.update("""
                        UPDATE proposal_lines
                        SET final_quantity = ?, status = ?
                        WHERE id = ?
                        """,
                line.finalQuantity(), line.status().name(), line.id());
    }

    /** Moves the proposal to a new status. */
    public void updateStatus(UUID proposalId, Proposal.Status status) {
        jdbc.update("UPDATE proposals SET status = ? WHERE id = ?", status.name(), proposalId);
    }

    // ------------------------------------------------------------------- read

    /** The proposal with this id, or empty. */
    public Optional<Proposal> find(UUID id) {
        List<Proposal> found = jdbc.query("""
                        SELECT id, run_id, snapshot_id, policy_version, proposed_at_tick,
                               mode, status, created_at, reviewed_quantity
                        FROM proposals WHERE id = ?
                        """,
                (rs, rowNum) -> new Proposal(
                        rs.getObject("id", UUID.class),
                        rs.getString("run_id"),
                        rs.getObject("snapshot_id", UUID.class),
                        rs.getString("policy_version"),
                        rs.getLong("proposed_at_tick"),
                        rs.getString("mode"),
                        Proposal.Status.valueOf(rs.getString("status")),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getDouble("reviewed_quantity"),
                        List.of()),
                id);
        if (found.isEmpty()) {
            return Optional.empty();
        }

        Proposal head = found.get(0);
        List<ProposalLine> lines = jdbc.query("""
                        SELECT id, proposal_id, line_id, station_id, fuel_type, source_depot_id,
                               route_id, reviewed_quantity, final_quantity, estimated_arrival_tick,
                               cover_before_hours, cover_after_hours, reasons, constraints,
                               alternatives, status
                        FROM proposal_lines WHERE proposal_id = ? ORDER BY line_id
                        """,
                (rs, rowNum) -> readLine(rs),
                id);

        return Optional.of(new Proposal(
                head.id(), head.runId(), head.snapshotId(), head.policyVersion(),
                head.proposedAtTick(), head.mode(), head.status(), head.createdAt(),
                head.reviewedQuantity(), lines));
    }

    /** The proposals of a run, newest first. */
    public List<Proposal> findByRun(String runId) {
        List<Proposal> heads = jdbc.query("""
                        SELECT id, run_id, snapshot_id, policy_version, proposed_at_tick,
                               mode, status, created_at, reviewed_quantity
                        FROM proposals WHERE run_id = ? ORDER BY created_at DESC
                        """,
                (rs, rowNum) -> new Proposal(
                        rs.getObject("id", UUID.class),
                        rs.getString("run_id"),
                        rs.getObject("snapshot_id", UUID.class),
                        rs.getString("policy_version"),
                        rs.getLong("proposed_at_tick"),
                        rs.getString("mode"),
                        Proposal.Status.valueOf(rs.getString("status")),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getDouble("reviewed_quantity"),
                        List.of()),
                runId);

        List<Proposal> result = new ArrayList<>();
        for (Proposal head : heads) {
            List<ProposalLine> lines = jdbc.query("""
                            SELECT id, proposal_id, line_id, station_id, fuel_type, source_depot_id,
                                   route_id, reviewed_quantity, final_quantity, estimated_arrival_tick,
                                   cover_before_hours, cover_after_hours, reasons, constraints,
                                   alternatives, status
                            FROM proposal_lines WHERE proposal_id = ? ORDER BY line_id
                            """,
                    (rs, rowNum) -> readLine(rs),
                    head.id());
            result.add(new Proposal(
                    head.id(), head.runId(), head.snapshotId(), head.policyVersion(),
                    head.proposedAtTick(), head.mode(), head.status(), head.createdAt(),
                    head.reviewedQuantity(), lines));
        }
        return result;
    }

    /** The proposals still waiting for a decision. */
    public List<Proposal> findPending(String runId) {
        return findByRun(runId).stream()
                .filter(proposal -> proposal.status().isOpen())
                .toList();
    }

    // --------------------------------------------------------------- mapping

    private static ProposalLine readLine(ResultSet rs) throws SQLException {
        return new ProposalLine(
                rs.getObject("id", UUID.class),
                rs.getObject("proposal_id", UUID.class),
                rs.getString("line_id"),
                rs.getString("station_id"),
                rs.getString("fuel_type"),
                rs.getString("source_depot_id"),
                rs.getString("route_id"),
                rs.getDouble("reviewed_quantity"),
                nullableDouble(rs, "final_quantity"),
                nullableLong(rs, "estimated_arrival_tick"),
                nullableDouble(rs, "cover_before_hours"),
                nullableDouble(rs, "cover_after_hours"),
                splitLines(rs.getString("reasons")),
                splitLines(rs.getString("constraints")),
                List.of(),
                ProposalLine.Status.valueOf(rs.getString("status")));
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    /** Joins a list into one storable line. */
    static String joinLines(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return String.join("\n", values);
    }

    /** Splits a stored line back into its values. */
    static List<String> splitLines(String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        return List.of(stored.split("\n"));
    }

    /** Serialises the alternatives as JSON. */
    static String toJson(List<ProposalLine.Alternative> alternatives) {
        if (alternatives == null || alternatives.isEmpty()) {
            return "[]";
        }
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < alternatives.size(); i++) {
            ProposalLine.Alternative alternative = alternatives.get(i);
            if (i > 0) {
                json.append(',');
            }
            json.append('{')
                    .append("\"route_id\":").append(quote(alternative.routeId())).append(',')
                    .append("\"source_depot_id\":").append(quote(alternative.sourceDepotId())).append(',')
                    .append("\"quantity_liters\":").append(alternative.quantityLiters()).append(',')
                    .append("\"expected_arrival_tick\":").append(alternative.expectedArrivalTick()).append(',')
                    .append("\"cover_after_hours\":").append(alternative.coverAfterHours()).append(',')
                    .append("\"reason_code\":").append(quote(alternative.reasonCode())).append(',')
                    .append("\"reason\":").append(quote(alternative.reason()))
                    .append('}');
        }
        return json.append(']').toString();
    }

    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
