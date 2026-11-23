package com.fuelplatform.simulator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuelplatform.config.ResilienceProperties;
import com.fuelplatform.observability.CircuitBreaker;
import com.fuelplatform.observability.SimulatorCircuitBreakers;
import com.fuelplatform.simulator.dto.SimulatorDtos.Allocation;
import com.fuelplatform.simulator.dto.SimulatorDtos.DemandObservation;
import com.fuelplatform.simulator.dto.SimulatorDtos.Depot;
import com.fuelplatform.simulator.dto.SimulatorDtos.DomainEvent;
import com.fuelplatform.simulator.dto.SimulatorDtos.Health;
import com.fuelplatform.simulator.dto.SimulatorDtos.Instance;
import com.fuelplatform.simulator.dto.SimulatorDtos.Region;
import com.fuelplatform.simulator.dto.SimulatorDtos.Route;
import com.fuelplatform.simulator.dto.SimulatorDtos.Station;
import com.fuelplatform.simulator.dto.SimulatorDtos.SupplyArrival;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Optional;

/**
 * Typed HTTP access to the official fuel supply simulator.
 *
 * <p>This class is the <strong>only</strong> place that knows the simulator speaks HTTP.
 * Everything above it works with domain types, which is what makes the risk and
 * allocation logic testable without a running simulator.
 *
 * <p>Four behaviours here are load-bearing and easy to get wrong:
 *
 * <ul>
 *   <li><strong>The stale header.</strong> A {@code stale_data} fault returns HTTP 200
 *       with {@code X-Simulator-Stale: true}. The status code alone tells you nothing, so
 *       every read carries the flag through to the caller (invariant INV-5).</li>
 *   <li><strong>Three error shapes.</strong> See {@link SimulatorError}.</li>
 *   <li><strong>Retries apply to reads only.</strong> A write carries an idempotency
 *       key, so a replay is safe in principle — but the command worker already owns
 *       that decision, because only it knows whether the command body was persisted
 *       first. The client does not retry writes.</li>
 *   <li><strong>The circuit breaker counts transport failures only.</strong> A domain
 *       error is a correct answer from a reachable simulator and must not open it.</li>
 * </ul>
 */
@Component
public class SimulatorClient {

    /** Header the simulator sets when it is serving data from a stale snapshot. */
    public static final String STALE_HEADER = "X-Simulator-Stale";

    private final RestClient restClient;
    private final SimulatorErrorParser errorParser;
    private final ObjectMapper objectMapper;
    private final SimulatorMetrics metrics;
    private final SimulatorCircuitBreakers breakers;
    private final ResilienceProperties resilience;

    public SimulatorClient(RestClient simulatorRestClient,
                          ObjectMapper objectMapper,
                          SimulatorMetrics metrics,
                          SimulatorCircuitBreakers breakers,
                          ResilienceProperties resilience) {
        this.restClient = simulatorRestClient;
        this.objectMapper = objectMapper;
        this.errorParser = new SimulatorErrorParser(objectMapper);
        this.metrics = metrics;
        this.breakers = breakers;
        this.resilience = resilience;
    }

    // ---------------------------------------------------------------- reads

    public Result<Instance> getInstance() {
        return get("/v1/instance", Instance.class);
    }

    public Result<Health> getHealth() {
        return get("/v1/health", Health.class);
    }

    public Result<List<Region>> getRegions() {
        return getList("/v1/regions", Region.class);
    }

    public Result<List<Depot>> getDepots() {
        return getList("/v1/depots", Depot.class);
    }

    public Result<List<Station>> getStations() {
        return getList("/v1/stations", Station.class);
    }

    public Result<List<Route>> getRoutes() {
        return getList("/v1/routes", Route.class);
    }

    public Result<List<SupplyArrival>> getSupplyArrivals() {
        return getList("/v1/supply-arrivals", SupplyArrival.class);
    }

    public Result<List<DomainEvent>> getEvents() {
        return getList("/v1/events", DomainEvent.class);
    }

    public Result<List<Allocation>> getAllocations() {
        return getList("/v1/allocations", Allocation.class);
    }

    public Result<List<DemandObservation>> getDemandHistory(String stationId, int limit) {
        String path = "/v1/demand-history?station_id=" + stationId + "&limit=" + limit;
        return getList(path, DemandObservation.class);
    }

    // --------------------------------------------------------------- write

    /**
     * Creates one allocation. This is the only domain-mutating call in the whole system.
     *
     * <p>A replay of the same key with the same body returns <strong>201</strong> (not 200)
     * and the existing allocation. A replay with a different body returns 409
     * {@code IDEMPOTENCY_KEY_MISMATCH}. Both are handled as success-with-existing-resource
     * or as a domain error respectively — see {@code docs/contract-notes.md} §5.3.
     */
    public Result<Allocation> createAllocation(Object request) {
        return exchange("POST", "/v1/allocations", request, Allocation.class, false);
    }

    // ------------------------------------------------------------- plumbing

    private <T> Result<T> get(String path, Class<T> type) {
        return exchange("GET", path, null, type, true);
    }

    private <T> Result<List<T>> getList(String path, Class<T> elementType) {
        return exchangeList("GET", path, null, elementType, true);
    }

    /**
     * Performs one call, with a bounded retry for reads.
     *
     * <p>The retry is deliberately narrow:
     *
     * <ul>
     *   <li>Only transport failures are retried. A domain error is a correct
     *       answer and retrying it changes nothing.</li>
     *   <li>The attempt count is bounded by configuration, and the bound is capped
     *       so a retry policy cannot outlive the freshness window. The simulator's
     *       own baseline response is ~2.05 s, so three attempts at a 2 s deadline
     *       already span longer than the 10 s freshness limit.</li>
     *   <li>The circuit breaker is consulted before every attempt, so a simulator
     *       that is known to be down is not contacted at all.</li>
     * </ul>
     */
    private <T> Result<T> exchange(String method, String path, Object body, Class<T> type, boolean retryable) {
        int maxAttempts = retryable ? resilience.maxRetries() + 1 : 1;
        CircuitBreaker breaker = retryable ? breakers.reads() : breakers.writes();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (!breaker.allowCall()) {
                SimulatorError error = new SimulatorError.Fault(
                        "CIRCUIT_OPEN",
                        "the simulator circuit breaker is open; the call was not attempted",
                        0);
                metrics.recordFailure(path, "circuit_open", 0);
                return Result.failure(error);
            }

            long startedAt = System.nanoTime();
            try {
                ResponseHeadersAndBody<T> response = execute(method, path, body, type);
                recordSuccess(path, response.stale(), startedAt);
                breaker.recordSuccess();
                return Result.success(response.body(), response.stale());
            } catch (RestClientResponseException e) {
                SimulatorError error = errorParser.parse(e.getResponseBodyAsString(), e.getStatusCode().value());
                metrics.recordFailure(path, outcomeOf(error), System.nanoTime() - startedAt);
                // The breaker counts AVAILABILITY, not agreement.
                //
                // A domain error means the simulator understood the request and
                // refused it — the integration is healthy, so the breaker records
                // a success. An injected fault means the simulator is broken or
                // unavailable — that IS a failure, and it must count, or the
                // breaker would never open during a real outage.
                if (isAvailabilityFailure(error)) {
                    breaker.recordFailure();
                    return Result.failure(error);
                }
                breaker.recordSuccess();
                return Result.failure(error);
            } catch (Exception e) {
                // Timeout, connection refused, or a body that does not map to the target type.
                long elapsed = System.nanoTime() - startedAt;
                metrics.recordFailure(path, "transport_error", elapsed);

                // A timeout is NOT proof that the simulator is down. It is proof
                // that the simulator is slow. Opening the breaker would stop the
                // whole integration for 30 s to avoid one timeout — and every
                // dependent feature would go dark with it. Only a
                // connection-level failure counts.
                if (TransportFailureClassifier.isUnavailable(e)) {
                    breaker.recordFailure();
                }

                if (attempt < maxAttempts) {
                    sleepBackoff(attempt);
                    continue;
                }
                SimulatorError error = new SimulatorError.Fault("TRANSPORT_ERROR", e.getMessage(), 0);
                return Result.failure(error);
            }
        }

        // Unreachable: the loop always returns on its last attempt.
        return Result.failure(new SimulatorError.Fault("TRANSPORT_ERROR", "no attempt was made", 0));
    }

    private <T> Result<List<T>> exchangeList(String method, String path, Object body,
                                              Class<T> elementType, boolean retryable) {
        int maxAttempts = retryable ? resilience.maxRetries() + 1 : 1;
        CircuitBreaker breaker = retryable ? breakers.reads() : breakers.writes();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (!breaker.allowCall()) {
                SimulatorError error = new SimulatorError.Fault(
                        "CIRCUIT_OPEN",
                        "the simulator circuit breaker is open; the call was not attempted",
                        0);
                metrics.recordFailure(path, "circuit_open", 0);
                return Result.failure(error);
            }

            long startedAt = System.nanoTime();
            try {
                ResponseHeadersAndBody<List<T>> response = executeList(method, path, body, elementType);
                recordSuccess(path, response.stale(), startedAt);
                breaker.recordSuccess();
                return Result.success(response.body(), response.stale());
            } catch (RestClientResponseException e) {
                SimulatorError error = errorParser.parse(e.getResponseBodyAsString(), e.getStatusCode().value());
                metrics.recordFailure(path, outcomeOf(error), System.nanoTime() - startedAt);
                // Availability, not agreement. See the note in exchange().
                if (isAvailabilityFailure(error)) {
                    breaker.recordFailure();
                    return Result.failure(error);
                }
                breaker.recordSuccess();
                return Result.failure(error);
            } catch (Exception e) {
                long elapsed = System.nanoTime() - startedAt;
                metrics.recordFailure(path, "transport_error", elapsed);
                // Availability, not agreement — and not slowness. See exchange().
                if (TransportFailureClassifier.isUnavailable(e)) {
                    breaker.recordFailure();
                }

                if (attempt < maxAttempts) {
                    sleepBackoff(attempt);
                    continue;
                }
                SimulatorError error = new SimulatorError.Fault("TRANSPORT_ERROR", e.getMessage(), 0);
                return Result.failure(error);
            }
        }

        return Result.failure(new SimulatorError.Fault("TRANSPORT_ERROR", "no attempt was made", 0));
    }

    /**
     * The backoff between read retries.
     *
     * <p>Linear, and short. The simulator's own baseline response is ~2.05 s, so the
     * budget for retries is already tight against the 10 s freshness limit.
     */
    private void sleepBackoff(int attempt) {
        long backoff = resilience.retryBackoffMs() * attempt;
        if (backoff <= 0) {
            return;
        }
        try {
            Thread.sleep(backoff);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void recordSuccess(String path, boolean stale, long startedAt) {
        long elapsed = System.nanoTime() - startedAt;
        if (stale) {
            metrics.recordStale(path, elapsed);
        } else {
            metrics.recordSuccess(path, elapsed);
        }
    }

    /** Maps an error to the fixed outcome label used in the metric. */
    private static String outcomeOf(SimulatorError error) {
        return switch (error) {
            case SimulatorError.Domain ignored -> "domain_error";
            case SimulatorError.Validation ignored -> "domain_error";
            case SimulatorError.Fault ignored -> "fault";
            case SimulatorError.Unparseable ignored -> "transport_error";
        };
    }

    /**
     * Whether a failure means the simulator is <strong>unavailable</strong> rather than
     * merely disagreeing.
     *
     * <p>This is the distinction the circuit breaker turns on:
     *
     * <ul>
     *   <li>A <strong>domain</strong> error (409, 422) means the simulator read the
     *       request and refused it. The integration is healthy.</li>
     *   <li>An <strong>injected fault</strong> (503, and any other HTTP error the
     *       simulator reports as a fault) means the simulator is broken or down.
     *       That is an availability failure and it counts against the breaker.</li>
     * </ul>
     *
     * <p>Getting this wrong is not academic. If a 503 counts as a success, the
     * breaker never opens during a real outage, and the platform keeps paying the
     * full timeout on every refresh — which is exactly the situation the breaker
     * exists to prevent.
     */
    private static boolean isAvailabilityFailure(SimulatorError error) {
        return error instanceof SimulatorError.Fault;
    }

    private <T> ResponseHeadersAndBody<T> execute(String method, String path, Object body, Class<T> type) {
        ResponseEntity<String> response = rawExchange(method, path, body);
        String raw = response.getBody();
        if (raw == null) {
            throw new IllegalStateException("empty response body for " + path);
        }
        try {
            return new ResponseHeadersAndBody<>(objectMapper.readValue(raw, type), isStale(response.getHeaders()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "cannot map simulator response for " + path + " to " + type.getSimpleName(), e);
        }
    }

    private <T> ResponseHeadersAndBody<List<T>> executeList(String method, String path, Object body, Class<T> elementType) {
        ResponseEntity<String> response = rawExchange(method, path, body);
        String raw = response.getBody();
        if (raw == null) {
            throw new IllegalStateException("empty response body for " + path);
        }
        try {
            // The element type is applied explicitly. A generic reference loses the
            // type information and yields LinkedHashMaps instead of the records.
            JavaType listType = objectMapper.getTypeFactory().constructCollectionType(List.class, elementType);
            List<T> parsed = objectMapper.readValue(raw, listType);
            return new ResponseHeadersAndBody<>(parsed, isStale(response.getHeaders()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "cannot map simulator response for " + path + " to List<" + elementType.getSimpleName() + ">", e);
        }
    }


    /**
     * Performs the HTTP call and returns the body as raw text.
     *
     * <p>The body is deliberately left unparsed here. The stale header is read from
     * the same response, so parsing and header extraction must happen against one
     * response object rather than two.
     */
    private ResponseEntity<String> rawExchange(String method, String path, Object body) {
        RestClient.RequestBodySpec spec = restClient.method(HttpMethod.valueOf(method)).uri(path);
        if (body != null) {
            spec = spec.body(body);
        }
        return spec.retrieve().toEntity(String.class);
    }

    /**
     * Reads the stale flag off the response headers.
     *
     * <p>HTTP header names are case-insensitive, so {@code x-simulator-stale} and
     * {@code X-Simulator-Stale} are the same header. The comparison is deliberately
     * case-insensitive on the value too.
     */
    static boolean isStale(HttpHeaders headers) {
        String value = headers.getFirst(STALE_HEADER);
        return value != null && value.trim().equalsIgnoreCase("true");
    }

    private record ResponseHeadersAndBody<T>(T body, boolean stale) {
    }

    /**
     * The outcome of a simulator call.
     *
     * <p>Never throws for an expected failure. Callers decide what a failure means:
     * a read failure falls back to the cached view, a write failure leaves the command
     * UNKNOWN rather than REJECTED.
     */
    public record Result<T>(T body, boolean stale, SimulatorError error) {

        public static <T> Result<T> success(T body, boolean stale) {
            return new Result<>(body, stale, null);
        }

        public static <T> Result<T> failure(SimulatorError error) {
            return new Result<>(null, false, error);
        }

        public boolean isSuccess() {
            return error == null;
        }

        public Optional<SimulatorError> failureDetail() {
            return Optional.ofNullable(error);
        }
    }
}
