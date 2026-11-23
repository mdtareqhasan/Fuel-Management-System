package com.fuelplatform.ingestion;

import com.fuelplatform.domain.SimulationMode;
import com.fuelplatform.domain.WorldSnapshot;
import com.fuelplatform.config.FreshnessProperties;
import com.fuelplatform.observability.DecisionMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Drives refreshes according to the simulator's mode.
 *
 * <p>The mode is read from the simulator on every refresh, not configured by us. The
 * behaviour differs by mode:
 *
 * <table border="1">
 *   <caption>Refresh strategy by mode</caption>
 *   <tr><th>Mode</th><th>Trigger</th></tr>
 *   <tr><td>PAUSED</td><td>2 second REST poll, plus a refresh after each {@code /admin/step}</td></tr>
 *   <tr><td>RUNNING</td><td>Coalesced {@code simulation.tick} events with a minimum
 *       interval, with the 2 second REST poll as fallback</td></tr>
 * </table>
 *
 * <p>PAUSED gets a plain poll because nothing changes on its own — the world only moves
 * when someone steps it. RUNNING prefers the event stream because at the measured rate
 * of ~5.46 ticks/sec the world moves far faster than a poll can track, but the poll
 * still runs so a dropped stream event cannot leave the view permanently stale.
 *
 * <p>Only one refresh runs at a time. A refresh that arrives while another is in flight
 * is coalesced into a single pending flag rather than queueing, so a burst of tick
 * events cannot build a backlog.
 */
@Component
public class RefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(RefreshScheduler.class);

    private final SnapshotIngestionService ingestionService;
    private final DecisionMetrics decisionMetrics;
    private final FreshnessProperties freshnessProperties;
    private final AtomicLong lastEventRefreshNanos = new AtomicLong();

    /** Set when a refresh is requested while another is running. */
    private volatile boolean refreshPending;

    /** Guard so only one refresh is ever in flight. */
    private final java.util.concurrent.atomic.AtomicBoolean refreshing =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public RefreshScheduler(SnapshotIngestionService ingestionService,
                            DecisionMetrics decisionMetrics,
                            FreshnessProperties freshnessProperties) {
        this.ingestionService = ingestionService;
        this.decisionMetrics = decisionMetrics;
        this.freshnessProperties = freshnessProperties;
    }

    /**
     * The fallback REST poll.
     *
     * <p>Runs in both modes. It is the primary trigger in PAUSED and the safety net in
     * RUNNING.
     */
    @Scheduled(fixedDelayString = "${app.freshness.poll-interval-ms:2000}")
    public void pollRefresh() {
        decisionMetrics.recordPollTriggeredRefresh();
        requestRefresh("poll");
    }

    /**
     * Called by the SSE reader when a relevant event arrives.
     *
     * <p>Coalesced: many tick events in quick succession produce one refresh, bounded
     * by the minimum refresh interval.
     */
    public void onSimulatorEvent() {
        long now = System.nanoTime();
        long interval = TimeUnit.MILLISECONDS.toNanos(freshnessProperties.minRefreshIntervalMs());
        long previous = lastEventRefreshNanos.get();
        if (now - previous < interval || !lastEventRefreshNanos.compareAndSet(previous, now)) {
            decisionMetrics.recordEventRefreshCoalesced();
            return;
        }
        decisionMetrics.recordEventTriggeredRefresh();
        requestRefresh("event");
    }

    /**
     * Requests a refresh. If one is already running, the request is remembered and
     * served once the current one finishes.
     */
    public void requestRefresh(String trigger) {
        if (!refreshing.compareAndSet(false, true)) {
            refreshPending = true;
            return;
        }
        try {
            do {
                refreshPending = false;
                ingestionService.refresh();
            } while (refreshPending);
        } catch (Exception e) {
            log.warn("refresh failed for trigger {}: {}", trigger, e.getMessage());
        } finally {
            refreshing.set(false);
        }
    }

    /**
     * Whether the simulator is currently paused.
     *
     * <p>Read from the last snapshot rather than cached separately, so the two can
     * never disagree.
     */
    public boolean isPaused() {
        WorldSnapshot snapshot = ingestionService.currentSnapshot();
        return snapshot != null && snapshot.mode() == SimulationMode.PAUSED;
    }
}
