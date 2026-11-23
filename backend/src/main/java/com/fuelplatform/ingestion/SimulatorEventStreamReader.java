package com.fuelplatform.ingestion;

import com.fuelplatform.config.StreamProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reads the simulator's server-sent event stream.
 *
 * <p><strong>The stream is a hint, never the truth.</strong> Every event only
 * asks for a re-read. Nothing is ever applied from the event payload itself.
 * That is the whole reason a dropped event is harmless: the next REST poll
 * brings the same truth, so the worst a lost event can do is delay the view by
 * one poll interval.
 *
 * <p>The measured frame format, captured from the running simulator on
 * 2026-11-22:
 *
 * <pre>
 * : connected
 * event: simulation.tick
 * data: {"tick": 13, "sim_time": "2026-01-01T03:15:00"}
 * </pre>
 *
 * <p>Comment lines (starting with {@code :}) are keepalives. They are counted
 * but never trigger a refresh — otherwise the simulator's keepalive cadence
 * would drive the refresh loop.
 *
 * <p><strong>Why REST polling still runs.</strong> The stream tells the
 * application that something changed; the poll tells it what changed. If the
 * stream is down, the poll alone keeps the view correct and the operator never
 * notices a difference other than a slightly less prompt update.
 *
 * <p>Reconnection uses a bounded exponential backoff, so a simulator that stays
 * down does not get hammered.
 */
@Component
public class SimulatorEventStreamReader {

    private static final Logger log = LoggerFactory.getLogger(SimulatorEventStreamReader.class);

    private final StreamProperties properties;
    private final HttpClient httpClient;
    private final URI streamUri;
    private final RefreshScheduler refreshScheduler;

    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong eventsReceived = new AtomicLong();
    private final AtomicLong reconnects = new AtomicLong();
    private final AtomicReference<String> lastEventName = new AtomicReference<>("none");
    private final AtomicLong lastEventAt = new AtomicLong();
    private final AtomicReference<String> lastError = new AtomicReference<>("none");

    private final Counter eventsCounter;
    private final Counter keepaliveCounter;
    private final Counter reconnectCounter;
    private final Counter failureCounter;

    public SimulatorEventStreamReader(StreamProperties properties,
                                      MeterRegistry meterRegistry,
                                      RefreshScheduler refreshScheduler,
                                      @Value("${app.simulator.base-url:http://localhost:8000}") String baseUrl) {
        this.properties = properties;
        this.refreshScheduler = refreshScheduler;
        this.streamUri = URI.create(baseUrl + "/v1/stream");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.connectTimeoutMs()))
                .build();

        this.eventsCounter = Counter.builder("fuel_stream_events_total")
                .description("Simulator stream events received, by type")
                .register(meterRegistry);
        this.keepaliveCounter = Counter.builder("fuel_stream_keepalives_total")
                .description("Keepalive comments received on the simulator stream")
                .register(meterRegistry);
        this.reconnectCounter = Counter.builder("fuel_stream_reconnects_total")
                .description("Reconnect attempts on the simulator stream")
                .register(meterRegistry);
        this.failureCounter = Counter.builder("fuel_stream_failures_total")
                .description("Stream failures, by reason")
                .register(meterRegistry);

        Gauge.builder("fuel_stream_connected", connected, ref -> ref.get() ? 1.0 : 0.0)
                .description("1 when the simulator event stream is connected")
                .register(meterRegistry);
        Gauge.builder("fuel_stream_events_received", eventsReceived, AtomicLong::get)
                .description("Total simulator stream events received")
                .register(meterRegistry);
        Gauge.builder("fuel_stream_reconnect_attempts", reconnects, AtomicLong::get)
                .description("Total simulator stream reconnect attempts")
                .register(meterRegistry);
    }

    /**
     * Starts the reader on its own thread.
     *
     * <p>The thread runs for the lifetime of the application. It is a daemon, so
     * it never keeps the JVM alive.
     */
    @jakarta.annotation.PostConstruct
    public void start() {
        if (!properties.enabled()) {
            log.info("simulator event stream is disabled; the REST poll is the only refresh trigger");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        Thread thread = new Thread(this::readLoop, "simulator-stream-reader");
        thread.setDaemon(true);
        thread.start();
        log.info("simulator event stream reader started on {}", streamUri);
    }

    /**
     * The read loop.
     *
     * <p>Every exit from this method — clean end, exception, or a deliberate
     * stop — leads to a reconnect with a bounded backoff.
     */
    private void readLoop() {
        long delay = properties.reconnectDelayMs();

        while (running.get()) {
            try {
                readOnce();
                // A clean end of stream is still a disconnect. Reset the backoff
                // only when the connection was actually up.
                delay = properties.reconnectDelayMs();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                failureCounter.increment();
                lastError.set(e.getClass().getSimpleName() + ": " + e.getMessage());
                log.warn("simulator stream failed: {}", e.getMessage());
            } finally {
                connected.set(false);
            }

            if (!running.get()) {
                return;
            }

            reconnects.incrementAndGet();
            reconnectCounter.increment();
            log.info("reconnecting to the simulator stream in {} ms", delay);
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            // Bounded exponential backoff, so a simulator that stays down is
            // not hammered.
            delay = Math.min(delay * 2, properties.maxReconnectDelayMs());
        }
    }

    /** One connection attempt. Reads frames until the stream ends. */
    private void readOnce() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(streamUri)
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofMinutes(10))
                .GET()
                .build();

        HttpResponse<java.io.InputStream> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 200) {
            throw new IOException("the simulator stream answered HTTP " + response.statusCode());
        }

        connected.set(true);
        lastError.set("none");
        log.info("simulator stream connected");

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {

            String eventName = "message";
            StringBuilder data = new StringBuilder();

            String line;
            while (running.get() && (line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    // A blank line ends a frame.
                    if (data.length() > 0) {
                        handleEvent(eventName, data.toString());
                    }
                    eventName = "message";
                    data.setLength(0);
                    continue;
                }

                if (line.startsWith(":")) {
                    // A keepalive comment. It proves the connection is alive
                    // and nothing else.
                    keepaliveCounter.increment();
                    continue;
                }

                if (line.startsWith("event:")) {
                    eventName = line.substring("event:".length()).trim();
                    continue;
                }

                if (line.startsWith("data:")) {
                    String value = line.substring("data:".length()).trim();
                    if (data.length() > 0) {
                        data.append('\n');
                    }
                    data.append(value);
                    continue;
                }

                // Any other field (id:, retry:) is ignored on purpose.
            }
        }
    }

    /**
     * Handles one complete frame.
     *
     * <p>The payload is not interpreted. The event only asks for a re-read,
     * because REST is the source of truth.
     */
    private void handleEvent(String eventName, String payload) {
        eventsReceived.incrementAndGet();
        eventsCounter.increment();
        lastEventName.set(eventName);
        lastEventAt.set(System.currentTimeMillis());

        log.debug("simulator stream event: {} {}", eventName, payload);

        // Coalesced. Many tick events in quick succession produce one refresh,
        // bounded by the minimum refresh interval.
        refreshScheduler.onSimulatorEvent();
    }

    /** Stops the reader. */
    @jakarta.annotation.PreDestroy
    public void stop() {
        running.set(false);
        connected.set(false);
    }

    /** Whether the stream is connected right now. */
    public boolean isConnected() {
        return connected.get();
    }

    /** The stream's state for the status panel. */
    public String state() {
        if (!properties.enabled()) {
            return "DISABLED";
        }
        return connected.get() ? "CONNECTED" : "DISCONNECTED";
    }

    /** The number of events received. */
    public long eventsReceived() {
        return eventsReceived.get();
    }

    /** The number of reconnect attempts. */
    public long reconnects() {
        return reconnects.get();
    }

    /** The name of the last event received. */
    public String lastEventName() {
        return lastEventName.get();
    }

    /** The last error, or "none". */
    public String lastError() {
        return lastError.get();
    }
}
