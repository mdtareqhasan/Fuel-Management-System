package com.fuelplatform.simulator;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Measures the exceptions the client's **real transport** produces, with no
 * simulator involved.
 *
 * <p>This test exists because the classification of a transport failure is the
 * one thing in the resilience layer that cannot be reasoned about from the
 * source alone. The JDK client and Spring's {@code RestClient} wrap a failure
 * differently from the raw {@code HttpClient}: the chain that reaches
 * {@code SimulatorClient} is Spring's {@code ResourceAccessException} wrapping
 * the JDK's own exception. Which exception the breaker sees decides whether a
 * slow simulator is mistaken for a dead one — and getting that wrong takes the
 * whole integration offline for the breaker's open window, starving every
 * dependent feature of the reads it needs.
 *
 * <p>So the shape is <strong>measured</strong>, not assumed, and the
 * measurement is hermetic: a loopback server, no container, no external
 * network.
 *
 * <p>Two cases are measured, because they must be told apart:
 *
 * <ul>
 *   <li>a **read timeout** — the server accepted the connection and then took
 *       too long to answer. This is what the simulator's {@code latency} fault
 *       produces. The simulator is <em>slow</em>, and the breaker must stay
 *       closed.</li>
 *   <li>a **refused connection** — nothing is listening. This is what a dead
 *       simulator produces, and the breaker must open.</li>
 * </ul>
 */
class SimulatorTransportTimeoutTest {

    private HttpServer server;
    private int port;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // The handler sleeps for longer than the read timeout used below,
        // which is exactly what the simulator's latency fault does.
        server.createContext("/v1/slow", exchange -> {
            try {
                Thread.sleep(2_000);
                byte[] body = "{\"ok\":true}".getBytes();
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** Builds the same client shape the application uses. */
    private RestClient client(int port, Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(1000))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);
        return RestClient.builder()
                .baseUrl("http://127.0.0.1:" + port)
                .requestFactory(requestFactory)
                .build();
    }

    /** Captures the whole cause chain of a thrown exception, as text. */
    private static List<String> chainOf(Throwable error) {
        List<String> chain = new ArrayList<>();
        Throwable current = error;
        int depth = 0;
        while (current != null && depth < 10) {
            chain.add(current.getClass().getName() + ": " + current.getMessage());
            current = current.getCause();
            depth++;
        }
        return chain;
    }

    @Test
    @DisplayName("a read timeout must not be classified as the simulator being unavailable")
    void readTimeoutIsNotAnOutage() {
        Throwable captured = null;
        try {
            client(port, Duration.ofMillis(300))
                    .get()
                    .uri("/v1/slow")
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            captured = e;
        }

        assertThat(captured)
                .as("the read timeout must actually be raised")
                .isNotNull();

        // The chain is printed so the measured shape is visible in the test
        // output rather than only in a comment.
        System.out.println("READ TIMEOUT CHAIN");
        chainOf(captured).forEach(line -> System.out.println("  " + line));

        assertThat(TransportFailureClassifier.isUnavailable(captured))
                .as("a slow simulator must not open the breaker")
                .isFalse();
    }

    @Test
    @DisplayName("a refused connection must still be classified as the simulator being unavailable")
    void refusedConnectionIsAnOutage() throws IOException {
        // Reserve a port and release the socket, so a connection to it is
        // refused outright. A socket that is merely bound but not listening
        // would queue the connection instead, and the failure would arrive as
        // a timeout — which is the very distinction this test exists to make.
        int closedPort;
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            closedPort = socket.getLocalPort();
        }

        Throwable captured = null;
        try {
            client(closedPort, Duration.ofMillis(1000))
                    .get()
                    .uri("/v1/instance")
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            captured = e;
        }

        assertThat(captured)
                .as("the connection refusal must actually be raised")
                .isNotNull();

        System.out.println("CONNECTION REFUSED CHAIN");
        chainOf(captured).forEach(line -> System.out.println("  " + line));

        assertThat(TransportFailureClassifier.isUnavailable(captured))
                .as("a dead simulator must open the breaker")
                .isTrue();
    }
}
