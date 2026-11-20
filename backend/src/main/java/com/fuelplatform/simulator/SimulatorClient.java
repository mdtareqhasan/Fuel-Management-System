package com.fuelplatform.simulator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * <p>Two behaviours here are load-bearing and easy to get wrong:
 *
 * <ul>
 *   <li><strong>The stale header.</strong> A {@code stale_data} fault returns HTTP 200
 *       with {@code X-Simulator-Stale: true}. The status code alone tells you nothing, so
 *       every read carries the flag through to the caller (invariant INV-5).</li>
 *   <li><strong>Three error shapes.</strong> See {@link SimulatorError}.</li>
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

    public SimulatorClient(RestClient simulatorRestClient, ObjectMapper objectMapper, SimulatorMetrics metrics) {
        this.restClient = simulatorRestClient;
        this.objectMapper = objectMapper;
        this.errorParser = new SimulatorErrorParser(objectMapper);
        this.metrics = metrics;
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
        return exchange("POST", "/v1/allocations", request, Allocation.class);
    }

    // ------------------------------------------------------------- plumbing

    private <T> Result<T> get(String path, Class<T> type) {
        return exchange("GET", path, null, type);
    }

    private <T> Result<List<T>> getList(String path, Class<T> elementType) {
        return exchangeList("GET", path, null, elementType);
    }

    private <T> Result<T> exchange(String method, String path, Object body, Class<T> type) {
        long startedAt = System.nanoTime();
        try {
            ResponseHeadersAndBody<T> response = execute(method, path, body, type);
            recordSuccess(path, response.stale(), startedAt);
            return Result.success(response.body(), response.stale());
        } catch (RestClientResponseException e) {
            SimulatorError error = errorParser.parse(e.getResponseBodyAsString(), e.getStatusCode().value());
            metrics.recordFailure(path, outcomeOf(error), System.nanoTime() - startedAt);
            return Result.failure(error);
        } catch (Exception e) {
            // Timeout, connection refused, or a body that does not map to the target type.
            SimulatorError error = new SimulatorError.Fault("TRANSPORT_ERROR", e.getMessage(), 0);
            metrics.recordFailure(path, "transport_error", System.nanoTime() - startedAt);
            return Result.failure(error);
        }
    }

    private <T> Result<List<T>> exchangeList(String method, String path, Object body, Class<T> elementType) {
        long startedAt = System.nanoTime();
        try {
            ResponseHeadersAndBody<List<T>> response = executeList(method, path, body, elementType);
            recordSuccess(path, response.stale(), startedAt);
            return Result.success(response.body(), response.stale());
        } catch (RestClientResponseException e) {
            SimulatorError error = errorParser.parse(e.getResponseBodyAsString(), e.getStatusCode().value());
            metrics.recordFailure(path, outcomeOf(error), System.nanoTime() - startedAt);
            return Result.failure(error);
        } catch (Exception e) {
            SimulatorError error = new SimulatorError.Fault("TRANSPORT_ERROR", e.getMessage(), 0);
            metrics.recordFailure(path, "transport_error", System.nanoTime() - startedAt);
            return Result.failure(error);
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
