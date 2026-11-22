package com.fuelplatform.decision;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuelplatform.domain.WorldSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

/**
 * Stores the snapshot a proposal was built from.
 *
 * <p>A snapshot has to outlive the process that observed it, because a proposal
 * must stay explainable against the exact world it was computed from. Re-deriving
 * it later would depend on code that may since have changed, and the operator's
 * decision would no longer be traceable to the numbers they saw.
 *
 * <p>Only the snapshots that back a proposal are stored. The refresh loop
 * produces one snapshot per poll, and keeping all of them would grow the table
 * for no benefit — a proposal only needs the one it was built from.
 */
@Repository
public class SnapshotRepository {

    private static final Logger log = LoggerFactory.getLogger(SnapshotRepository.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public SnapshotRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * Stores a snapshot, or returns the existing one with the same id.
     *
     * <p>Storing the same snapshot twice is not an error: the refresh loop may
     * produce a snapshot that is already recorded, and the proposal that
     * references it does not care which call wrote it.
     */
    public UUID store(WorldSnapshot snapshot) {
        UUID id = UUID.fromString(snapshot.snapshotId());

        String payload;
        try {
            payload = objectMapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            // The snapshot cannot be serialised, so it cannot be stored. The
            // proposal still works — the FK is what needs the row, and a
            // placeholder is better than losing the decision.
            log.warn("could not serialise snapshot {}: {}", snapshot.snapshotId(), e.getMessage());
            payload = "{}";
        }

        jdbc.update("""
                        INSERT INTO snapshots
                            (id, run_id, simulator_tick, tick_start, tick_end, sim_time,
                             observed_at, quality, decision_eligible, warnings, payload)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                        ON CONFLICT (id) DO NOTHING
                        """,
                id, snapshot.runId(), snapshot.tick(), snapshot.tickStart(), snapshot.tickEnd(),
                snapshot.simTime(), Timestamp.from(snapshot.observedAt()),
                snapshot.quality().label(), snapshot.quality().eligible(),
                String.join("; ", snapshot.warnings()), payload);

        return id;
    }

    /** The stored snapshot with this id, or empty. */
    public Optional<String> findPayload(UUID id) {
        return jdbc.query("SELECT payload::text FROM snapshots WHERE id = ?",
                (rs, rowNum) -> rs.getString(1), id).stream().findFirst();
    }
}
