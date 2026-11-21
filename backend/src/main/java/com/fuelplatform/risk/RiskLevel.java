package com.fuelplatform.risk;

/**
 * The risk label for one entity and fuel.
 *
 * <p>The guide's proposed thresholds, with the reasoning kept explicit:
 *
 * <ul>
 *   <li><strong>CRITICAL</strong> — the central projection reaches zero before the
 *       earliest feasible replenishment, or within two hours.</li>
 *   <li><strong>WARNING</strong> — a projected central shortage inside the horizon.</li>
 *   <li><strong>WATCH</strong> — only the stress scenario reaches a shortage.</li>
 *   <li><strong>UNKNOWN</strong> — the data was inadequate or stale.</li>
 * </ul>
 *
 * <p>These are <strong>proposed, not tuned</strong>. A label is never a probability.
 */
public enum RiskLevel {

    /** Central projection runs dry before replenishment can arrive, or within two hours. */
    CRITICAL,

    /** A central shortage is projected inside the horizon. */
    WARNING,

    /** Only the conservative stress scenario runs dry. */
    WATCH,

    /** Not enough trustworthy data to say anything. */
    UNKNOWN;

    /**
     * Whether this level means the operator should act now.
     *
     * <p>Used to sort the risk table: the most urgent rows first.
     */
    public int severityRank() {
        return switch (this) {
            case CRITICAL -> 0;
            case WARNING -> 1;
            case WATCH -> 2;
            case UNKNOWN -> 3;
        };
    }
}
