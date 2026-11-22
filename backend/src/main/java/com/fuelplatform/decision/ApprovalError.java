package com.fuelplatform.decision;

import org.springframework.http.HttpStatus;

/**
 * The application's own error codes for approval failures.
 *
 * <p>The guide is explicit that a domain conflict must not be turned into a
 * generic 500 and must not be retried blindly. That means the UI has to be able
 * to <strong>tell the codes apart</strong> and decide what to do with each one.
 * A single generic "approval failed" message would force the operator to guess.
 *
 * <p>Each code carries the HTTP status it maps to and a sentence explaining what
 * the operator should do about it.
 */
public enum ApprovalError {

    /**
     * The snapshot the proposal was based on is stale, invalid, or the simulator
     * is unreachable.
     *
     * <p>Blocking in both modes. The operator cannot approve a decision against a
     * world they cannot see (INV-15).
     */
    STALE_OR_INVALID_SNAPSHOT(
            HttpStatus.CONFLICT,
            "The world changed while you were reviewing, so this proposal cannot be approved. "
                    + "Wait for a fresh refresh and review it again."),

    /**
     * The world is readable but a line no longer makes sense.
     *
     * <p>The route is gone, the station is in OUTAGE, the depot is closed, or the
     * best source has changed. Nothing is sent for that line (ADR 004).
     */
    PROPOSAL_CHANGED(
            HttpStatus.CONFLICT,
            "The world changed while you were reviewing, so this proposal needs to be reviewed again."),

    /**
     * The proposal is older than the operator can meaningfully act on.
     *
     * <p>In RUNNING mode the clock moves far faster than a review, so the proposal
     * expires. The operator is offered a Pause for review instead.
     */
    PROPOSAL_EXPIRED(
            HttpStatus.GONE,
            "This proposal expired because the clock moved on while you were reviewing it. "
                    + "Pause the simulator for review, or approve a fresh proposal."),

    /**
     * The proposal has already been approved.
     *
     * <p>A duplicate click, or a second browser tab. This is <strong>not</strong> an
     * error to retry: the shipment already exists and the operator should see it
     * (INV-12).
     */
    ALREADY_APPROVED(
            HttpStatus.CONFLICT,
            "This proposal was already approved. The shipment it created is in the ledger."),

    /**
     * The request itself was not acceptable.
     *
     * <p>A missing field, a negative quantity, a malformed body. The operator has
     * to correct the request; retrying it unchanged will fail identically.
     */
    INVALID_REQUEST(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "The approval request was not valid. Check the request and try again."),

    /**
     * A line asked for more than the operator reviewed.
     *
     * <p>The request may only lower the quantity, never raise it (INV-13). This is
     * rejected rather than clamped, because silently sending less than the
     * operator asked for is as bad as sending more.
     */
    QUANTITY_EXCEEDS_REVIEWED(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "The requested quantity is larger than the quantity that was reviewed. "
                    + "Only an equal or smaller quantity can be approved."),

    /**
     * The proposal does not exist.
     */
    PROPOSAL_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "That proposal does not exist. It may belong to an earlier run."),

    /**
     * The database could not record the decision.
     *
     * <p>Blocking. A decision that cannot be recorded cannot be audited, and an
     * unaudited shipment is exactly what the review requirement exists to prevent.
     */
    PERSISTENCE_FAILED(
            HttpStatus.SERVICE_UNAVAILABLE,
            "The decision could not be recorded, so nothing was sent. Try again.");

    private final HttpStatus status;
    private final String message;

    ApprovalError(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    /** The HTTP status this error maps to. */
    public HttpStatus httpStatus() {
        return status;
    }

    /** What the operator should do about it. */
    public String message() {
        return message;
    }

    /** The machine-readable code, which is what the UI switches on. */
    public String code() {
        return name();
    }
}
