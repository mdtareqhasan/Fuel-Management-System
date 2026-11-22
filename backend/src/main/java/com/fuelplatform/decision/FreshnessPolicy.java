package com.fuelplatform.decision;

import com.fuelplatform.config.DecisionProperties;
import com.fuelplatform.domain.WorldSnapshot;

import java.time.Duration;
import java.time.Instant;

/**
 * Decides whether a proposal is still worth acting on.
 *
 * <p>This is the mode-aware half of ADR 004, and the arithmetic in that ADR is
 * the reason it exists as a separate class.
 *
 * <p>The measured facts:
 *
 * <ul>
 *   <li>~5.46 ticks per wall second, 15 simulated minutes per tick, so ~82
 *       simulated minutes pass per wall second.</li>
 *   <li>A 30 second review in RUNNING mode spans ~41 simulated hours and ~164
 *       ticks.</li>
 * </ul>
 *
 * <p>So a fixed tick-staleness threshold can never be satisfied in RUNNING mode —
 * it would block every approval during a live demo. The rules therefore differ by
 * mode:
 *
 * <table>
 *   <caption>The mode-aware rules</caption>
 *   <tr><th>Mode</th><th>Rule</th><th>Effect</th></tr>
 *   <tr><td>PAUSED</td><td>the tick must not have moved</td>
 *       <td>blocks, but the operator can step back</td></tr>
 *   <tr><td>RUNNING</td><td>tick lag is <em>displayed</em>, not blocked on</td>
 *       <td>the fresh-read revalidation is the control</td></tr>
 *   <tr><td>both</td><td>the snapshot must be fresh in wall-clock terms</td>
 *       <td>blocks in both modes</td></tr>
 * </table>
 */
public class FreshnessPolicy {

    private final DecisionProperties properties;

    public FreshnessPolicy(DecisionProperties properties) {
        this.properties = properties;
    }

    /**
     * Checks whether the proposal may still be acted on.
     *
     * @param proposal the proposal the operator is approving
     * @param snapshot the current view
     * @param now      the wall clock
     * @return ok, or the reason it is not
     */
    public Verdict check(Proposal proposal, WorldSnapshot snapshot, Instant now) {
        if (snapshot == null) {
            return Verdict.blocked(ApprovalError.STALE_OR_INVALID_SNAPSHOT,
                    "there is no current view of the world");
        }

        // Blocking in both modes. A decision cannot be made against a world the
        // operator cannot see.
        double ageSeconds = Duration.between(snapshot.observedAt(), now).toMillis() / 1000.0;
        if (ageSeconds > properties.wallClockFreshnessSeconds()) {
            return Verdict.blocked(ApprovalError.STALE_OR_INVALID_SNAPSHOT,
                    "the current view is " + Math.round(ageSeconds)
                            + " seconds old, and the limit is "
                            + properties.wallClockFreshnessSeconds() + " seconds");
        }

        long tickLag = snapshot.tick() - proposal.proposedAtTick();

        if ("PAUSED".equalsIgnoreCase(proposal.mode())) {
            // The clock only moves when the operator steps it, so a moved tick
            // means the operator themselves changed the world mid-review.
            if (tickLag > properties.pausedMaxTickLag()) {
                return Verdict.blocked(ApprovalError.PROPOSAL_CHANGED,
                        "the simulator was stepped " + tickLag
                                + " ticks since this proposal was made, so it must be reviewed again");
            }
            return Verdict.ok(tickLag);
        }

        // RUNNING. The tick lag is shown, not blocked on. What protects the
        // operator here is the fresh-read revalidation of every line.
        if (isExpired(proposal, now)) {
            return Verdict.blocked(ApprovalError.PROPOSAL_EXPIRED,
                    "this proposal was made " + Math.round(ageSeconds)
                            + " seconds ago and the clock has moved on since");
        }
        return Verdict.ok(tickLag);
    }

    /** Whether a proposal has expired in RUNNING mode. */
    public boolean isExpired(Proposal proposal, Instant now) {
        if ("RUNNING".equalsIgnoreCase(proposal.mode())) {
            double ageSeconds = Duration.between(proposal.createdAt(), now).toMillis() / 1000.0;
            return ageSeconds > properties.runningProposalExpirySeconds();
        }
        return false;
    }

    /**
     * The verdict of a freshness check.
     *
     * @param allowed  whether the proposal may be acted on
     * @param error    why it may not, when it may not
     * @param reason   the operator-facing explanation
     * @param tickLag  how many ticks the world moved since the proposal
     */
    public record Verdict(
            boolean allowed,
            ApprovalError error,
            String reason,
            long tickLag) {

        static Verdict ok(long tickLag) {
            return new Verdict(true, null, null, tickLag);
        }

        static Verdict blocked(ApprovalError error, String reason) {
            return new Verdict(false, error, reason, 0);
        }
    }
}
