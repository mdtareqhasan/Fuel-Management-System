package com.fuelplatform.simulator.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Typed mirrors of the official simulator's JSON payloads.
 *
 * <p>Every record here was checked against a live response from
 * {@code asifmahmoud414/bup-fuel-supply-simulator:1.0.0} on 2026-11-20.
 * See {@code docs/contract-notes.md} for the raw responses.
 *
 * <p>Unknown fields are ignored on purpose: the simulator accepted a POST body
 * carrying an extra field with 201, so it does not reject forward-compatible
 * payloads. Being strict here would break the day-1 client without cause.
 */
public final class SimulatorDtos {

    private SimulatorDtos() {
    }

    /** Fuel types the simulator knows. Anything else is a 422 from the simulator. */
    public enum FuelType {
        DIESEL, PETROL, OCTANE
    }

    /** {@code GET /v1/instance} — the clock and the mode. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Instance(
            @JsonProperty("id") Integer id,
            @JsonProperty("scenario_id") String scenarioId,
            @JsonProperty("scenario_version") String scenarioVersion,
            @JsonProperty("seed") Integer seed,
            @JsonProperty("sim_time") String simTime,
            @JsonProperty("tick") Long tick,
            @JsonProperty("tick_minutes") Integer tickMinutes,
            @JsonProperty("status") String status) {
    }

    /** {@code GET /v1/health} — liveness only. It bypasses fault injection. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Health(
            @JsonProperty("status") String status,
            @JsonProperty("database") String database,
            @JsonProperty("simulation") SimulationStatus simulation) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record SimulationStatus(
                @JsonProperty("status") String status,
                @JsonProperty("tick") Long tick) {
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Region(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("demand_factor") Double demandFactor) {
    }

    /** {@code GET /v1/depots} */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Depot(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("region_id") String regionId,
            @JsonProperty("status") String status,
            @JsonProperty("dispatch_capacity_per_tick") Double dispatchCapacityPerTick,
            @JsonProperty("capacity") Map<String, Double> capacity,
            @JsonProperty("inventory") Map<String, Double> inventory) {
    }

    /** {@code GET /v1/stations} */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Station(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("region_id") String regionId,
            @JsonProperty("status") String status,
            @JsonProperty("demand_profile") String demandProfile,
            @JsonProperty("demand_multiplier") Double demandMultiplier,
            @JsonProperty("capacity") Map<String, Double> capacity,
            @JsonProperty("inventory") Map<String, Double> inventory) {
    }

    /** {@code GET /v1/routes} */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Route(
            @JsonProperty("id") String id,
            @JsonProperty("source_depot_id") String sourceDepotId,
            @JsonProperty("destination_station_id") String destinationStationId,
            @JsonProperty("transit_ticks") Integer transitTicks,
            @JsonProperty("max_shipment") Double maxShipment,
            @JsonProperty("status") String status) {
    }

    /** {@code GET /v1/supply-arrivals} */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SupplyArrival(
            @JsonProperty("id") String id,
            @JsonProperty("depot_id") String depotId,
            @JsonProperty("fuel_type") String fuelType,
            @JsonProperty("quantity") Double quantity,
            @JsonProperty("planned_tick") Long plannedTick,
            @JsonProperty("actual_tick") Long actualTick,
            @JsonProperty("status") String status) {
    }

    /** {@code GET /v1/events} — injected crisis events and their parameters. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DomainEvent(
            @JsonProperty("id") Long id,
            @JsonProperty("type") String type,
            @JsonProperty("start_tick") Long startTick,
            @JsonProperty("end_tick") Long endTick,
            @JsonProperty("status") String status,
            @JsonProperty("parameters") Map<String, Object> parameters) {
    }

    /** {@code GET /v1/allocations} — the shipment ledger. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Allocation(
            @JsonProperty("id") Long id,
            @JsonProperty("idempotency_key") String idempotencyKey,
            @JsonProperty("source_depot_id") String sourceDepotId,
            @JsonProperty("destination_station_id") String destinationStationId,
            @JsonProperty("route_id") String routeId,
            @JsonProperty("fuel_type") String fuelType,
            @JsonProperty("quantity") Double quantity,
            @JsonProperty("created_tick") Long createdTick,
            @JsonProperty("departure_tick") Long departureTick,
            @JsonProperty("expected_arrival_tick") Long expectedArrivalTick,
            @JsonProperty("actual_arrival_tick") Long actualArrivalTick,
            @JsonProperty("status") String status,
            @JsonProperty("failure_reason") String failureReason) {
    }

    /**
     * {@code GET /v1/demand-history} — one row per (station, fuel, tick).
     *
     * <p>The API returns rows <strong>newest first</strong>. Forecast code must
     * re-sort ascending before computing anything (invariant INV-20).
     *
     * <p>One {@code limit} is shared across all three fuels, so the rows must
     * be split by fuel after fetching (invariant INV-21).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DemandObservation(
            @JsonProperty("id") Long id,
            @JsonProperty("station_id") String stationId,
            @JsonProperty("fuel_type") String fuelType,
            @JsonProperty("tick") Long tick,
            @JsonProperty("sim_time") String simTime,
            @JsonProperty("demand_liters") Double demandLiters,
            @JsonProperty("served_liters") Double servedLiters,
            @JsonProperty("unmet_liters") Double unmetLiters) {
    }

    /** {@code GET /v1/metrics} — ground truth since the last reset. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SimulatorMetrics(
            @JsonProperty("served_demand_liters") Double servedDemandLiters,
            @JsonProperty("unmet_demand_liters") Double unmetDemandLiters,
            @JsonProperty("service_level") Double serviceLevel,
            @JsonProperty("allocation_liters") Double allocationLiters,
            @JsonProperty("allocation_failures") Long allocationFailures) {
    }

    /**
     * Request body for {@code POST /v1/allocations} — the only domain write.
     *
     * <p>The idempotency key travels in the <strong>body</strong> as
     * {@code idempotency_key}, never as an HTTP header.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AllocationRequest(
            @JsonProperty("idempotency_key") String idempotencyKey,
            @JsonProperty("source_depot_id") String sourceDepotId,
            @JsonProperty("destination_station_id") String destinationStationId,
            @JsonProperty("route_id") String routeId,
            @JsonProperty("fuel_type") String fuelType,
            @JsonProperty("quantity") Double quantity) {
    }

    /** Convenience wrapper for endpoints returning a JSON array. */
    public record ListResponse<T>(List<T> items) {
    }
}
