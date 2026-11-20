package com.fuelplatform.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks component health for the status panel.
 *
 * <p>Kept separate from the snapshot quality on purpose. The two answer different
 * questions:
 *
 * <ul>
 *   <li><strong>Component health</strong> — can we reach the database, can we reach the
 *       simulator at all.</li>
 *   <li><strong>Data quality</strong> — is the view we hold fresh enough to act on.</li>
 * </ul>
 *
 * <p>They can disagree in both directions. A simulator that answers {@code /v1/health}
 * is reachable but may still be serving stale station data. A database hiccup leaves
 * the last valid read view intact but blocks writes.
 */
@Component
public class HealthRegistry {

    private final AtomicReference<String> databaseStatus = new AtomicReference<>("UNKNOWN");
    private final AtomicReference<String> simulatorStatus = new AtomicReference<>("UNKNOWN");

    public HealthRegistry(MeterRegistry meterRegistry) {
        Gauge.builder("fuel_database_up", databaseStatus, ref -> ref.get().equals("UP") ? 1.0 : 0.0)
                .description("1 when the database is reachable")
                .register(meterRegistry);
        Gauge.builder("fuel_simulator_up", simulatorStatus, ref -> ref.get().equals("UP") ? 1.0 : 0.0)
                .description("1 when the simulator is reachable")
                .register(meterRegistry);
    }

    public String databaseStatus() {
        return databaseStatus.get();
    }

    public String simulatorStatus() {
        return simulatorStatus.get();
    }

    public void recordDatabaseReachable(boolean reachable) {
        databaseStatus.set(reachable ? "UP" : "DOWN");
    }

    public void recordSimulatorReachable(boolean reachable) {
        simulatorStatus.set(reachable ? "UP" : "DOWN");
    }

    /**
     * The database reachability probe.
     *
     * <p>An interface so that the observability package does not depend on the
     * persistence implementation. The implementation lives in the config package,
     * where the DataSource bean already is.
     */
    @FunctionalInterface
    public interface DataSourceProbe {
        boolean isReachable();
    }
}
