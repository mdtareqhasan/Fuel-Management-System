package com.fuelplatform.config;

import com.fuelplatform.observability.HealthRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Probes the database and reports the result to the health registry.
 *
 * <p>The probe runs on a slow schedule rather than on the request path. A health
 * check must never make the system it is measuring slower.
 *
 * <p>The query is {@code SELECT 1}: the cheapest statement that still proves the
 * connection works. It does not touch any application table, so it cannot
 * interfere with a migration or a long-running transaction.
 */
@Configuration
public class DatabaseHealthConfig {

    private static final Logger log = LoggerFactory.getLogger(DatabaseHealthConfig.class);

    private final HealthRegistry healthRegistry;
    private final DataSource dataSource;

    public DatabaseHealthConfig(HealthRegistry healthRegistry, DataSource dataSource) {
        this.healthRegistry = healthRegistry;
        this.dataSource = dataSource;
    }

    /**
     * Runs the probe every five seconds.
     *
     * <p>Five seconds is slow enough that the probe is invisible in the request
     * latency figures and fast enough that the status panel is never meaningfully
     * wrong about the database.
     *
     * <p>A {@code @Scheduled} method must take no arguments, so the probe is a
     * field here rather than an injected bean.
     */
    @Scheduled(fixedDelay = 5000, initialDelay = 5000)
    public void pollDatabase() {
        probeDatabase();
    }

    /**
     * Checks the database with the cheapest possible statement.
     *
     * <p>{@code SELECT 1} proves the connection works without touching any
     * application table, so it cannot interfere with a migration or a long
     * running transaction.
     */
    boolean probeDatabase() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT 1")) {
            boolean reachable = resultSet.next();
            healthRegistry.recordDatabaseReachable(reachable);
            return reachable;
        } catch (Exception e) {
            log.debug("database probe failed: {}", e.getMessage());
            healthRegistry.recordDatabaseReachable(false);
            return false;
        }
    }
}
