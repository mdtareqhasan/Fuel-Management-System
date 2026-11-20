package com.fuelplatform.domain;

import com.fuelplatform.simulator.dto.SimulatorDtos.Allocation;
import com.fuelplatform.simulator.dto.SimulatorDtos.Depot;
import com.fuelplatform.simulator.dto.SimulatorDtos.DomainEvent;
import com.fuelplatform.simulator.dto.SimulatorDtos.Instance;
import com.fuelplatform.simulator.dto.SimulatorDtos.Region;
import com.fuelplatform.simulator.dto.SimulatorDtos.Route;
import com.fuelplatform.simulator.dto.SimulatorDtos.Station;
import com.fuelplatform.simulator.dto.SimulatorDtos.SupplyArrival;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One validated observation of the simulated world.
 *
 * <p>Immutable by construction. Everything above this layer — risk, allocation, the
 * dashboard — reads a snapshot and never mutates it. That is what makes the pure
 * decision functions testable: a test builds a snapshot and calls a function.
 *
 * <p>This is <strong>evidence of a past observation</strong>, never an alternative
 * operational world (ADR 002). The simulator owns the truth.
 *
 * @param runId        application run UUID. The simulator's instance id is always 1 and
 *                     is useless for isolation, so every record carries its own.
 * @param snapshotId   unique id of this snapshot
 * @param tickStart    the tick observed at the start of the refresh
 * @param tickEnd      the tick observed at the end of the refresh. If it differs from
 *                     {@code tickStart}, the world moved during the read and the view is
 *                     labelled with bounds rather than claiming to be atomic.
 * @param observedAt   wall clock time of the refresh (not the simulator's clock)
 * @param quality      whether this snapshot may back a decision
 * @param warnings     human-readable reasons, one per degraded aspect
 */
public record WorldSnapshot(
        String runId,
        String snapshotId,
        Long tickStart,
        Long tickEnd,
        String simTime,
        Instant observedAt,
        DataQuality quality,
        List<String> warnings,
        Instance instance,
        List<Region> regions,
        List<Depot> depots,
        List<Station> stations,
        List<Route> routes,
        List<SupplyArrival> supplyArrivals,
        List<DomainEvent> events,
        List<Allocation> allocations) {

    /** The tick this snapshot is anchored to. */
    public long tick() {
        return tickEnd != null ? tickEnd : (tickStart != null ? tickStart : 0L);
    }

    public SimulationMode mode() {
        return SimulationMode.from(instance == null ? null : instance.status());
    }

    /** Simulated minutes per tick, from the simulator itself. Never assumed. */
    public int tickMinutes() {
        return instance == null || instance.tickMinutes() == null ? 15 : instance.tickMinutes();
    }

    /**
     * Converts a duration in ticks to simulated hours.
     *
     * <p>This is the single place tick-to-time conversion happens. Everything else
     * passes ticks or hours explicitly so a wrong conversion cannot hide in a formula.
     */
    public double ticksToHours(double ticks) {
        return ticks * tickMinutes() / 60.0;
    }

    public double ticksToMinutes(double ticks) {
        return ticks * tickMinutes();
    }

    public Map<String, Depot> depotsById() {
        return depots.stream().collect(java.util.stream.Collectors.toMap(Depot::id, d -> d));
    }

    public Map<String, Station> stationsById() {
        return stations.stream().collect(java.util.stream.Collectors.toMap(Station::id, s -> s));
    }
}
