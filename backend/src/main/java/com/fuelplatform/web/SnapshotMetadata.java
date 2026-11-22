package com.fuelplatform.web;

import java.time.Instant;
import java.util.List;

/**
 * Response metadata carried alongside every read.
 *
 * <p>The frontend needs to know three things before it shows anything:
 * which snapshot it is looking at, which ticks that snapshot covers, and whether the
 * data may back a decision. Hiding any of those makes a stale view look fresh.
 *
 * @param snapshotId  which snapshot this is
 * @param runId       application run id
 * @param tickStart   tick at the start of the refresh
 * @param tickEnd     tick at the end of the refresh. When it differs from tickStart the
 *                    world moved during the read, and the view is labelled with bounds
 *                    rather than claiming to be atomic.
 * @param simTime     the simulator's own clock, as returned (naive, no invented zone)
 * @param observedAt  wall clock time of the refresh
 * @param quality     FRESH, STALE or INVALID
 * @param warnings    why the data may not be trusted
 */
public record SnapshotMetadata(
        String snapshotId,
        String runId,
        Long tickStart,
        Long tickEnd,
        String simTime,
        Instant observedAt,
        String quality,
        boolean decisionEligible,
        List<String> warnings) {

    public static SnapshotMetadata from(com.fuelplatform.domain.WorldSnapshot snapshot) {
        return new SnapshotMetadata(
                snapshot.snapshotId(),
                snapshot.runId(),
                snapshot.tickStart(),
                snapshot.tickEnd(),
                snapshot.simTime(),
                snapshot.observedAt(),
                snapshot.quality().label(),
                snapshot.quality().eligible(),
                snapshot.quality().reasons());
    }

    public static SnapshotMetadata empty(String reason) {
        return new SnapshotMetadata(null, null, null, null, null, null, "UNAVAILABLE", false, List.of(reason));
    }
}
