package com.fuelplatform.domain;

/**
 * The simulator's operating mode, read from {@code status} on {@code GET /v1/instance}.
 *
 * <p>This is not decoration. It changes how approval works (ADR 004):
 *
 * <ul>
 *   <li><strong>PAUSED</strong> — the clock is frozen and driven by {@code /admin/step}.
 *       A proposal must belong to the current tick; if the tick moved, regenerate it.</li>
 *   <li><strong>RUNNING</strong> — the clock advances while the operator is reviewing. At
 *       the measured rate of ~5.46 ticks/sec a 30 second review spans ~41 simulated
 *       hours, so tick lag is displayed rather than blocked on, and approval revalidates
 *       the reviewed envelope against a fresh read instead.</li>
 * </ul>
 */
public enum SimulationMode {

    PAUSED,
    RUNNING,

    /** The simulator reported a status this build does not know. */
    UNKNOWN;

    public static SimulationMode from(String rawStatus) {
        if (rawStatus == null) {
            return UNKNOWN;
        }
        return switch (rawStatus.trim().toUpperCase()) {
            case "PAUSED" -> PAUSED;
            case "RUNNING" -> RUNNING;
            default -> UNKNOWN;
        };
    }
}
