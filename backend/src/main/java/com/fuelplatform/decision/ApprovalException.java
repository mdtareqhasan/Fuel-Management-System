package com.fuelplatform.decision;

/**
 * A refusal to approve.
 *
 * <p>Carries an {@link ApprovalError} so the caller can turn it into the right
 * HTTP status and the right UI behaviour, and the specific reason so the
 * operator is told what actually went wrong rather than a generic failure.
 */
public class ApprovalException extends RuntimeException {

    private final ApprovalError error;

    public ApprovalException(ApprovalError error, String detail) {
        super(error.message() + " (" + detail + ")");
        this.error = error;
    }

    /** The code the UI switches on. */
    public ApprovalError error() {
        return error;
    }
}
