package com.fuelplatform.command;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Stores and reads commands.
 *
 * <p>The write path is the important part. A command is inserted with its
 * idempotency key and its exact request body <strong>before</strong> any POST is
 * attempted (INV-10). That ordering is what makes the retry safe: if the process
 * dies between the POST and the response, the body to resend is already on disk.
 *
 * <p>The two unique constraints do the real work:
 *
 * <ul>
 *   <li>{@code (proposal_line_id)} — one proposal line produces at most one
 *       command, so a duplicate approval cannot create a second shipment (INV-12).</li>
 *   <li>{@code (idempotency_key)} — the same key can never be reused for a
 *       different shipment.</li>
 * </ul>
 */
@Repository
public class CommandRepository {

    private final JdbcTemplate jdbc;

    public CommandRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ write

    /**
     * Records the intent to write.
     *
     * <p>This must be called <strong>before</strong> the simulator is contacted.
     * A duplicate insert for the same proposal line is caught by the unique
     * constraint and reported as an already-existing command, which is how a
     * duplicate approval becomes a no-op rather than a second shipment.
     */
    public Command insert(Command command) {
        jdbc.update("""
                        INSERT INTO commands
                            (id, run_id, proposal_id, proposal_line_id, idempotency_key,
                             request_body, status, simulator_allocation_id, simulator_status,
                             attempts, last_error, created_at, updated_at, resolved_at)
                        VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                command.id(), command.runId(), command.proposalId(), command.proposalLineId(),
                command.idempotencyKey(), command.requestBody(),
                command.status().name(), command.simulatorAllocationId(), command.simulatorStatus(),
                command.attempts(), command.lastError(),
                Timestamp.from(command.createdAt()), Timestamp.from(command.updatedAt()),
                command.resolvedAt() == null ? null : Timestamp.from(command.resolvedAt()));

        return command;
    }

    /** Updates a command's status and outcome. */
    public void update(Command command) {
        jdbc.update("""
                        UPDATE commands
                        SET status = ?, simulator_allocation_id = ?, simulator_status = ?,
                            attempts = ?, last_error = ?, updated_at = ?, resolved_at = ?
                        WHERE id = ?
                        """,
                command.status().name(), command.simulatorAllocationId(), command.simulatorStatus(),
                command.attempts(), command.lastError(),
                Timestamp.from(command.updatedAt()),
                command.resolvedAt() == null ? null : Timestamp.from(command.resolvedAt()),
                command.id());
    }

    /**
     * Records the simulator's own view of a shipment.
     *
     * <p>This updates only the simulator's columns. The command's status is
     * what the application did — it was sent, and it stays sent. What changes
     * as the shipment travels is the <strong>simulator's</strong> status, and
     * the ledger keeps the two apart on purpose.
     */
    public void updateSimulatorStatus(UUID commandId, Long allocationId, String simulatorStatus) {
        jdbc.update("""
                        UPDATE commands
                        SET simulator_allocation_id = COALESCE(?, simulator_allocation_id),
                            simulator_status = ?, updated_at = ?
                        WHERE id = ?
                        """,
                allocationId, simulatorStatus, Timestamp.from(java.time.Instant.now()), commandId);
    }

    // ------------------------------------------------------------------- read

    /** The command for a proposal line, or empty. */
    public Optional<Command> findByLine(UUID proposalLineId) {
        List<Command> found = query("SELECT * FROM commands WHERE proposal_line_id = ?", proposalLineId);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    /** The command with this idempotency key, or empty. */
    public Optional<Command> findByKey(String idempotencyKey) {
        List<Command> found = query("SELECT * FROM commands WHERE idempotency_key = ?", idempotencyKey);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    /** The command with this id, or empty. */
    public Optional<Command> findById(UUID id) {
        List<Command> found = query("SELECT * FROM commands WHERE id = ?", id);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    /** The commands of a run, newest first. */
    public List<Command> findByRun(String runId) {
        return query("SELECT * FROM commands WHERE run_id = ? ORDER BY created_at DESC", runId);
    }

    /** The commands whose outcome is not yet known. */
    public List<Command> findUnresolved(String runId) {
        return query("""
                SELECT * FROM commands
                WHERE run_id = ? AND status IN ('APPROVED', 'SENDING', 'UNKNOWN')
                ORDER BY created_at
                """, runId);
    }

    /** Every command of a run, for the shipment ledger. */
    public List<Command> findAll(String runId) {
        return findByRun(runId);
    }

    // --------------------------------------------------------------- mapping

    private List<Command> query(String sql, Object... args) {
        return jdbc.query(sql, (rs, rowNum) -> read(rs), args);
    }

    private static Command read(ResultSet rs) throws SQLException {
        Timestamp resolvedAt = rs.getTimestamp("resolved_at");
        return new Command(
                rs.getObject("id", UUID.class),
                rs.getString("run_id"),
                rs.getObject("proposal_id", UUID.class),
                rs.getObject("proposal_line_id", UUID.class),
                rs.getString("idempotency_key"),
                rs.getString("request_body"),
                Command.Status.valueOf(rs.getString("status")),
                nullableLong(rs, "simulator_allocation_id"),
                rs.getString("simulator_status"),
                rs.getInt("attempts"),
                rs.getString("last_error"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                resolvedAt == null ? null : resolvedAt.toInstant());
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
