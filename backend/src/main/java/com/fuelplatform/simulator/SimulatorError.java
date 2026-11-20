package com.fuelplatform.simulator;

/**
 * The simulator returns <strong>three different error shapes</strong>. All three were
 * observed on 2026-11-20; see {@code docs/contract-notes.md} §5.4.
 *
 * <pre>
 * 1. Domain error        {"detail": {"code": "ROUTE_MISMATCH", "message": "..."}}
 * 2. Pydantic 422        {"detail": [{"type": "literal_error", "loc": [...], "msg": "..."}]}
 * 3. Injected fault      {"error":  {"code": "FAULT_INJECTED", "message": "..."}}
 * </pre>
 *
 * <p>Shapes 1 and 2 share the same field name but disagree on its type: {@code detail}
 * is an object in one and an array in the other. A Jackson record bound to
 * {@code detail} therefore deserialises one and throws on the other. That is why the
 * adapter keeps {@code detail} as a raw {@code JsonNode} and discriminates here
 * instead of letting a deserializer guess.
 *
 * <p>Shape 3 uses a top-level {@code error} key, so it is invisible to anything
 * that only looks at {@code detail}.
 */
public sealed interface SimulatorError {

    /** A domain rule rejected the request. Carries the simulator's own code. */
    record Domain(String code, String message, int httpStatus) implements SimulatorError {
        @Override
        public String toString() {
            return "Domain[" + code + " http=" + httpStatus + "] " + message;
        }
    }

    /** The request body failed the simulator's schema validation (HTTP 422). */
    record Validation(String summary, int httpStatus) implements SimulatorError {
        @Override
        public String toString() {
            return "Validation[http=" + httpStatus + "] " + summary;
        }
    }

    /**
     * A fault was injected, or the transport failed (timeout, reset, refused).
     *
     * <p>This is the only category that is safe to retry with the same body and key.
     */
    record Fault(String code, String message, int httpStatus) implements SimulatorError {
        @Override
        public String toString() {
            return "Fault[" + code + " http=" + httpStatus + "] " + message;
        }
    }

    /** The response body could not be understood at all. */
    record Unparseable(String reason, int httpStatus) implements SimulatorError {
        @Override
        public String toString() {
            return "Unparseable[http=" + httpStatus + "] " + reason;
        }
    }

    /**
     * Whether this failure is transient, i.e. whether retrying the <strong>same</strong>
     * body with the <strong>same</strong> idempotency key could still succeed.
     *
     * <p>Deliberately conservative. A domain conflict (409) is never retryable: the
     * world has to be re-read and the decision recomputed first.
     */
    default boolean isRetryable() {
        return this instanceof Fault;
    }
}
