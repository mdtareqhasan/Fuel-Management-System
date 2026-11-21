package com.fuelplatform.risk;

/**
 * How much to trust a computed number.
 *
 * <p>The distinction the guide keeps insisting on: an <strong>unknown</strong> value is
 * not a zero and not a confident number. {@code LOW_DATA} means the estimate rests on a
 * documented profile assumption; {@code UNKNOWN} means the data was insufficient to
 * compute anything at all, and the field stays null (invariant INV-24).
 */
public enum DataConfidence {

    /** Enough observations to trust the number. */
    GOOD,

    /** Computed, but from a fallback path with few or no observations. */
    LOW_DATA,

    /** Could not be computed. The value is unknown, not zero. */
    UNKNOWN
}
