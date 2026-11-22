package com.fuelplatform.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The audit trail.
 *
 * <p>Every decision an operator made and every write the application attempted
 * lands here. This is the record that answers "a shipment happened, who decided
 * it, and what did they see at the time".
 *
 * <p>Two properties matter more than convenience here.
 *
 * <p><strong>The audit write is deliberately not in the same transaction as the
 * decision.</strong> If the decision transaction rolls back, the failed attempt
 * still has to be visible — an audit trail that only records successes is not an
 * audit trail. So the write runs in its own transaction
 * ({@link Propagation#REQUIRES_NEW}). This is not decoration: without it, every
 * <em>refused</em> approval rolled its audit record back with it, and the trail
 * showed only the decisions that succeeded.
 *
 * <p><strong>The details are serialised, not concatenated.</strong> A simulator
 * error message can contain a double quote, and a body built by string
 * concatenation would then be invalid JSON. That is not cosmetic: the insert
 * fails, and because it shared a transaction with the command's status update,
 * the failure rolls back the status update too. The command would stay
 * {@code APPROVED} when the truth was {@code UNKNOWN} — the one outcome an
 * operator must not be misled about, because it is what leads to a duplicate
 * shipment. The details are therefore passed as a map and serialised by Jackson,
 * which cannot produce invalid JSON.
 */
@Repository
public class AuditRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AuditRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * Records one event.
     *
     * @param runId         the application run
     * @param actor         who did it
     * @param action        what they did
     * @param entityType    what they did it to
     * @param entityId      which one
     * @param outcome       how it ended
     * @param correlationId the id to quote when reporting a problem
     * @param details       the reasoning and the numbers involved
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(
            String runId,
            String actor,
            String action,
            String entityType,
            String entityId,
            String outcome,
            String correlationId,
            Map<String, Object> details) {

        jdbc.update("""
                        INSERT INTO audit_events
                            (id, run_id, occurred_at, actor, action, entity_type, entity_id,
                             outcome, correlation_id, details)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                        """,
                UUID.randomUUID(), runId, Timestamp.from(Instant.now()),
                actor, action, entityType, entityId, outcome, correlationId,
                toJson(details));
    }

    /**
     * Builds a details map.
     *
     * <p>Tolerates null values, which {@link Map#of} does not. A null is
     * recorded as a null rather than dropped, so "we do not know" stays visible
     * instead of quietly becoming "not applicable".
     *
     * @param pairs alternating keys and values
     * @return the details map
     */
    public static Map<String, Object> details(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }

    /**
     * Serialises the details.
     *
     * <p>Falls back to a valid JSON object rather than propagating, because a
     * detail that cannot be serialised must never cost us the record of the
     * decision itself.
     */
    private String toJson(Map<String, Object> details) {
        if (details == null || details.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("unserialisable", true);
            fallback.put("message", String.valueOf(e.getMessage()));
            try {
                return objectMapper.writeValueAsString(fallback);
            } catch (JsonProcessingException ignored) {
                return "{\"unserialisable\":true}";
            }
        }
    }

    /** The audit events of a run, newest first. */
    public List<AuditEvent> findByRun(String runId, int limit) {
        return jdbc.query("""
                        SELECT id, occurred_at, actor, action, entity_type, entity_id,
                               outcome, correlation_id, details
                        FROM audit_events WHERE run_id = ?
                        ORDER BY occurred_at DESC LIMIT ?
                        """,
                (rs, rowNum) -> new AuditEvent(
                        rs.getObject("id", UUID.class),
                        rs.getTimestamp("occurred_at").toInstant(),
                        rs.getString("actor"),
                        rs.getString("action"),
                        rs.getString("entity_type"),
                        rs.getString("entity_id"),
                        rs.getString("outcome"),
                        rs.getString("correlation_id"),
                        rs.getString("details")),
                runId, limit);
    }

    /** The audit events about one entity, newest first. */
    public List<AuditEvent> findByEntity(String entityType, String entityId, int limit) {
        return jdbc.query("""
                        SELECT id, occurred_at, actor, action, entity_type, entity_id,
                               outcome, correlation_id, details
                        FROM audit_events WHERE entity_type = ? AND entity_id = ?
                        ORDER BY occurred_at DESC LIMIT ?
                        """,
                (rs, rowNum) -> new AuditEvent(
                        rs.getObject("id", UUID.class),
                        rs.getTimestamp("occurred_at").toInstant(),
                        rs.getString("actor"),
                        rs.getString("action"),
                        rs.getString("entity_type"),
                        rs.getString("entity_id"),
                        rs.getString("outcome"),
                        rs.getString("correlation_id"),
                        rs.getString("details")),
                entityType, entityId, limit);
    }

    /**
     * One audit record.
     *
     * @param id            the record id
     * @param occurredAt    when it happened
     * @param actor         who did it
     * @param action        what they did
     * @param entityType    what they did it to
     * @param entityId      which one
     * @param outcome       how it ended
     * @param correlationId the id to quote when reporting a problem
     * @param details       the reasoning and the numbers involved
     */
    public record AuditEvent(
            UUID id,
            Instant occurredAt,
            String actor,
            String action,
            String entityType,
            String entityId,
            String outcome,
            String correlationId,
            String details) {
    }
}
