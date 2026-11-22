package com.fuelplatform.decision;

import com.fuelplatform.config.DecisionProperties;
import com.fuelplatform.domain.DataQuality;
import com.fuelplatform.domain.WorldSnapshot;
import com.fuelplatform.simulator.dto.SimulatorDtos.Instance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the mode-aware freshness rule.
 *
 * <p>ADR 004's verification section names three cases explicitly, and they are
 * the three that matter:
 *
 * <ol>
 *   <li>approve in PAUSED at the same tick — allowed</li>
 *   <li>approve after stepping eight ticks — blocked</li>
 *   <li>approve in RUNNING after the world moved — <strong>allowed</strong>, because
 *       a fixed tick-staleness threshold could never be satisfied in RUNNING mode
 *       and the fresh-read revalidation is the real control</li>
 * </ol>
 *
 * <p>Plus the rule that blocks in both modes: the wall-clock freshness limit.
 */
class FreshnessPolicyTest {

    private final DecisionProperties properties = new DecisionProperties(10, 120, 1, 50000);
    private final FreshnessPolicy policy = new FreshnessPolicy(properties);

    @Test
    @DisplayName("PAUSED: approving at the same tick is allowed")
    void pausedAtTheSameTickIsAllowed() {
        Instant now = Instant.now();
        Proposal proposal = proposal("PAUSED", 40L, now);
        WorldSnapshot snapshot = snapshot("PAUSED", 40L, now);

        FreshnessPolicy.Verdict verdict = policy.check(proposal, snapshot, now);

        assertThat(verdict.allowed()).isTrue();
        assertThat(verdict.tickLag()).isZero();
    }

    @Test
    @DisplayName("PAUSED: approving after the clock was stepped is blocked")
    void pausedAfterSteppingIsBlocked() {
        Instant now = Instant.now();
        Proposal proposal = proposal("PAUSED", 40L, now);
        // The operator stepped the simulator eight times while reviewing.
        WorldSnapshot snapshot = snapshot("PAUSED", 48L, now);

        FreshnessPolicy.Verdict verdict = policy.check(proposal, snapshot, now);

        assertThat(verdict.allowed()).isFalse();
        assertThat(verdict.error()).isEqualTo(ApprovalError.PROPOSAL_CHANGED);
        assertThat(verdict.reason()).contains("stepped");
    }

    @Test
    @DisplayName("RUNNING: a moved tick is displayed, not blocked on")
    void runningWithAMovedTickIsAllowed() {
        Instant now = Instant.now();
        Proposal proposal = proposal("RUNNING", 40L, now);
        // In RUNNING mode the tick moves on its own. ~5.46 ticks/sec means the
        // tick moves while the operator reads, and blocking on that would make
        // approval impossible.
        WorldSnapshot snapshot = snapshot("RUNNING", 204L, now);

        FreshnessPolicy.Verdict verdict = policy.check(proposal, snapshot, now);

        assertThat(verdict.allowed()).isTrue();
        assertThat(verdict.tickLag()).isEqualTo(164L);
    }

    @Test
    @DisplayName("both modes: an old snapshot blocks the approval")
    void oldSnapshotBlocksInBothModes() {
        Instant observed = Instant.now().minus(Duration.ofSeconds(30));
        Instant now = Instant.now();

        Proposal paused = proposal("PAUSED", 40L, now);
        FreshnessPolicy.Verdict pausedVerdict =
                policy.check(paused, snapshot("PAUSED", 40L, observed), now);

        Proposal running = proposal("RUNNING", 40L, now);
        FreshnessPolicy.Verdict runningVerdict =
                policy.check(running, snapshot("RUNNING", 40L, observed), now);

        assertThat(pausedVerdict.allowed()).isFalse();
        assertThat(pausedVerdict.error()).isEqualTo(ApprovalError.STALE_OR_INVALID_SNAPSHOT);
        assertThat(runningVerdict.allowed()).isFalse();
        assertThat(runningVerdict.error()).isEqualTo(ApprovalError.STALE_OR_INVALID_SNAPSHOT);
    }

    @Test
    @DisplayName("RUNNING: an old proposal expires")
    void runningProposalExpires() {
        Instant now = Instant.now();
        // The proposal was made 200 seconds ago, which is past the 120 second
        // expiry. At ~82 simulated minutes per wall second, that review spanned
        // ~2.7 simulated days.
        Proposal proposal = proposal("RUNNING", 40L, now.minus(Duration.ofSeconds(200)));
        WorldSnapshot snapshot = snapshot("RUNNING", 40L, now);

        FreshnessPolicy.Verdict verdict = policy.check(proposal, snapshot, now);

        assertThat(verdict.allowed()).isFalse();
        assertThat(verdict.error()).isEqualTo(ApprovalError.PROPOSAL_EXPIRED);
    }

    @Test
    @DisplayName("PAUSED: a proposal does not expire on wall-clock time alone")
    void pausedProposalDoesNotExpire() {
        Instant now = Instant.now();
        Proposal proposal = proposal("PAUSED", 40L, now.minus(Duration.ofSeconds(600)));
        WorldSnapshot snapshot = snapshot("PAUSED", 40L, now);

        FreshnessPolicy.Verdict verdict = policy.check(proposal, snapshot, now);

        assertThat(verdict.allowed()).isTrue();
    }

    @Test
    @DisplayName("no snapshot at all blocks the approval")
    void noSnapshotBlocks() {
        Instant now = Instant.now();
        Proposal proposal = proposal("RUNNING", 40L, now);

        FreshnessPolicy.Verdict verdict = policy.check(proposal, null, now);

        assertThat(verdict.allowed()).isFalse();
        assertThat(verdict.error()).isEqualTo(ApprovalError.STALE_OR_INVALID_SNAPSHOT);
    }

    // ------------------------------------------------------------- fixtures

    private static Proposal proposal(String mode, long tick, Instant createdAt) {
        return new Proposal(
                UUID.randomUUID(), UUID.randomUUID().toString(), UUID.randomUUID(),
                "policy-1", tick, mode, Proposal.Status.PENDING_REVIEW,
                createdAt, 0, List.of());
    }

    private static WorldSnapshot snapshot(String mode, long tick, Instant observedAt) {
        return new WorldSnapshot(
                UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                tick, tick, "2026-11-22T10:00:00", observedAt,
                DataQuality.fresh(), List.of(),
                new Instance(1, "scenario-fuel", "1.0", 42, "2026-11-22T10:00:00", tick, 15, mode),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
