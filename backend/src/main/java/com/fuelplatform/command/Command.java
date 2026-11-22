package com.fuelplatform.command;

import java.time.Instant;
import java.util.UUID;

/**
 * The application's intent to make one simulator write.
 *
 * <p>This record exists to make one thing impossible: sending the same shipment
 * twice. The {@code idempotencyKey} and the {@code requestBody} are written to the
 * database <strong>before</strong> any POST is attempted (INV-10). A retry reuses
 * both, unchanged (INV-11).
 *
 * <p>The status distinguishes an outcome we do not know from an outcome that
 * failed. That distinction is the whole point of INV-14: a timeout means
 * {@link Status#UNKNOWN}, never {@link Status#REJECTED}. The shipment may exist in
 * the simulator, and a human has to be able to see that it might.
 *
 * @param id                    the command id
 * @param runId                 the application run
 * @param proposalId            the proposal this came from
 * @param proposalLineId        the line this came from
 * @param idempotencyKey        the key sent to the simulator
 * @param requestBody           the exact body that will be POSTed
 * @param status                where the command is in its lifecycle
 * @param simulatorAllocationId the simulator's allocation id, once known
 * @param simulatorStatus       the simulator's own status string, once known
 * @param attempts              how many POSTs have been attempted
 * @param lastError             the last failure, in operator language
 * @param createdAt             when the intent was recorded
 * @param updatedAt             when the command last changed
 * @param resolvedAt            when the outcome became known
 */
public record Command(
        UUID id,
        String runId,
        UUID proposalId,
        UUID proposalLineId,
        String idempotencyKey,
        String requestBody,
        Status status,
        Long simulatorAllocationId,
        String simulatorStatus,
        int attempts,
        String lastError,
        Instant createdAt,
        Instant updatedAt,
        Instant resolvedAt) {

    /**
     * The lifecycle of a command.
     *
     * <p>{@link Status#UNKNOWN} is the important one. It means the POST may or may
     * not have reached the simulator. The command is reconciled by matching the
     * idempotency key against {@code GET /v1/allocations}, never by blindly
     * retrying with a new key.
     */
    public enum Status {
        /** Approved and persisted, not yet sent. */
        APPROVED,
        /** A POST is in flight. */
        SENDING,
        /** The simulator confirmed it. */
        SENT,
        /** The outcome is not known. A shipment may or may not exist. */
        UNKNOWN,
        /** The simulator refused it with a domain error. */
        REJECTED,
        /** The write failed in a way that is definitely not a shipment. */
        FAILED,
        /** The shipment was cancelled. */
        CANCELLED;

        /** Whether the outcome still needs resolving. */
        public boolean isUnresolved() {
            return this == APPROVED || this == SENDING || this == UNKNOWN;
        }

        /** Whether the command may still be retried. */
        public boolean isRetryable() {
            return this == APPROVED || this == UNKNOWN;
        }
    }

    /** A copy with a new status and the fields that go with it. */
    public Command withStatus(
            Status newStatus,
            Long allocationId,
            String simulatorStatusValue,
            String error,
            Instant now) {

        return new Command(
                id, runId, proposalId, proposalLineId, idempotencyKey, requestBody,
                newStatus, allocationId, simulatorStatusValue,
                attempts + 1, error, createdAt, now,
                newStatus == Status.SENT || newStatus == Status.REJECTED
                        || newStatus == Status.FAILED ? now : resolvedAt);
    }
}
