package com.fuelplatform.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Metrics for the approval path and the resilience layer.
 *
 * <p>Every label here comes from a <strong>fixed, small set</strong>. No
 * proposal id, station id or correlation id ever becomes a label
 * (invariant INV-27) — one label per proposal would create an unbounded
 * number of time series in Prometheus, and a database that slowly grows
 * forever is a database that eventually falls over.
 *
 * <p>The counters answer three questions an operator asks during an incident:
 *
 * <ul>
 *   <li><strong>Were approvals blocked, and why?</strong> The refusal code is
 *       the label, so a dashboard can show "blocked because the world changed"
 *       separately from "blocked because the snapshot was stale".</li>
 *   <li><strong>How long does an approval take?</strong> The timer separates the
 *       server-side work from the simulator round trip.</li>
 *   <li><strong>Is the integration healthy?</strong> The breaker and stream
 *       counters say whether the platform is coping or is refusing to act.</li>
 * </ul>
 */
@Component
public class DecisionMetrics {

    private final MeterRegistry meterRegistry;

    public DecisionMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /** Records a refused approval, labelled with the reason code. */
    public void recordApprovalRefused(String reasonCode) {
        Counter.builder("fuel_approvals_refused_total")
                .description("Approvals refused, by reason code")
                .tag("reason", reasonCode)
                .register(meterRegistry)
                .increment();
    }

    /** Records a granted approval, labelled with its outcome. */
    public void recordApprovalGranted(String outcome) {
        Counter.builder("fuel_approvals_granted_total")
                .description("Approvals granted, by outcome")
                .tag("outcome", outcome)
                .register(meterRegistry)
                .increment();
    }

    /** Records the duration of an approval attempt. */
    public void recordApprovalDuration(long nanos, String outcome) {
        Timer.builder("fuel_approval_seconds")
                .description("Approval attempt duration, by outcome")
                .tag("outcome", outcome)
                .publishPercentiles(0.5, 0.95)
                .register(meterRegistry)
                .record(nanos, TimeUnit.NANOSECONDS);
    }

    /** Records a refresh that could not complete. */
    public void recordRefreshFailed(String reason) {
        Counter.builder("fuel_refresh_failures_total")
                .description("Snapshot refreshes that failed, by reason")
                .tag("reason", reason)
                .register(meterRegistry)
                .increment();
    }

    /** Records a refresh that completed. */
    public void recordRefreshSucceeded() {
        Counter.builder("fuel_refresh_success_total")
                .description("Snapshot refreshes that completed and were published")
                .register(meterRegistry)
                .increment();
    }

    /** Records a refresh that was triggered by a stream event. */
    public void recordEventTriggeredRefresh() {
        Counter.builder("fuel_refresh_triggers_total")
                .description("Refresh triggers, by source")
                .tag("trigger", "event")
                .register(meterRegistry)
                .increment();
    }

    /** Records a stream notification coalesced by the minimum refresh interval. */
    public void recordEventRefreshCoalesced() {
        Counter.builder("fuel_refresh_events_coalesced_total")
                .description("SSE refresh notifications skipped inside the minimum refresh interval")
                .register(meterRegistry)
                .increment();
    }

    /** Records a refresh that was triggered by the REST poll. */
    public void recordPollTriggeredRefresh() {
        Counter.builder("fuel_refresh_triggers_total")
                .description("Refresh triggers, by source")
                .tag("trigger", "poll")
                .register(meterRegistry)
                .increment();
    }
}
