package com.fuelplatform.decision;

import com.fuelplatform.domain.DataQuality;
import com.fuelplatform.domain.WorldSnapshot;
import com.fuelplatform.simulator.dto.SimulatorDtos.Allocation;
import com.fuelplatform.simulator.dto.SimulatorDtos.Depot;
import com.fuelplatform.simulator.dto.SimulatorDtos.Instance;
import com.fuelplatform.simulator.dto.SimulatorDtos.Route;
import com.fuelplatform.simulator.dto.SimulatorDtos.Station;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the revalidation rules.
 *
 * <p>These are the tests that prove ADR 004's table is real. Each row of that
 * table gets a test:
 *
 * <ul>
 *   <li>still feasible → send the reviewed quantity</li>
 *   <li>feasible for less → send the smaller quantity, never more</li>
 *   <li>route gone / station in OUTAGE / depot closed → send nothing</li>
 * </ul>
 */
class RevalidatorTest {

    private static final String FUEL = "DIESEL";

    @Test
    @DisplayName("a line that is still feasible is sent as reviewed")
    void feasibleLineIsSentAsReviewed() {
        WorldSnapshot world = world(
                station("station-mirpur", "OPEN", 2000, 10000),
                depot("depot-gazipur", "OPEN", 50000),
                route("route-1", "AVAILABLE", 8000));

        Revalidator.Outcome outcome = Revalidator.revalidate(line(5000), world, 5000);

        assertThat(outcome.decision()).isEqualTo(Revalidator.Outcome.Decision.AS_REVIEWED);
        assertThat(outcome.quantity()).isEqualTo(5000);
        assertThat(outcome.canShip()).isTrue();
    }

    @Test
    @DisplayName("a line that can only carry less is reduced, never raised")
    void feasibleForLessIsReduced() {
        // The depot only has 3000 litres left, but the operator reviewed 5000.
        WorldSnapshot world = world(
                station("station-mirpur", "OPEN", 2000, 10000),
                depot("depot-gazipur", "OPEN", 3000),
                route("route-1", "AVAILABLE", 8000));

        Revalidator.Outcome outcome = Revalidator.revalidate(line(5000), world, 5000);

        assertThat(outcome.decision()).isEqualTo(Revalidator.Outcome.Decision.REDUCED);
        assertThat(outcome.quantity()).isEqualTo(3000);
        assertThat(outcome.quantity()).isLessThan(5000);
    }

    @Test
    @DisplayName("a route that is no longer available sends nothing")
    void unavailableRouteSendsNothing() {
        WorldSnapshot world = world(
                station("station-mirpur", "OPEN", 2000, 10000),
                depot("depot-gazipur", "OPEN", 50000),
                route("route-1", "DISRUPTED", 8000));

        Revalidator.Outcome outcome = Revalidator.revalidate(line(5000), world, 5000);

        assertThat(outcome.decision()).isEqualTo(Revalidator.Outcome.Decision.CHANGED);
        assertThat(outcome.quantity()).isZero();
        assertThat(outcome.canShip()).isFalse();
        assertThat(outcome.reason()).contains("DISRUPTED");
    }

    @Test
    @DisplayName("a station in OUTAGE sends nothing")
    void stationInOutageSendsNothing() {
        WorldSnapshot world = world(
                station("station-mirpur", "OUTAGE", 2000, 10000),
                depot("depot-gazipur", "OPEN", 50000),
                route("route-1", "AVAILABLE", 8000));

        Revalidator.Outcome outcome = Revalidator.revalidate(line(5000), world, 5000);

        assertThat(outcome.decision()).isEqualTo(Revalidator.Outcome.Decision.CHANGED);
        assertThat(outcome.reason()).contains("OUTAGE");
    }

    @Test
    @DisplayName("a closed depot sends nothing")
    void closedDepotSendsNothing() {
        WorldSnapshot world = world(
                station("station-mirpur", "OPEN", 2000, 10000),
                depot("depot-gazipur", "CLOSED", 50000),
                route("route-1", "AVAILABLE", 8000));

        Revalidator.Outcome outcome = Revalidator.revalidate(line(5000), world, 5000);

        assertThat(outcome.decision()).isEqualTo(Revalidator.Outcome.Decision.CHANGED);
        assertThat(outcome.reason()).contains("CLOSED");
    }

    @Test
    @DisplayName("a route that no longer connects the same depot and station sends nothing")
    void reroutedLineSendsNothing() {
        WorldSnapshot world = world(
                station("station-mirpur", "OPEN", 2000, 10000),
                depot("depot-gazipur", "OPEN", 50000),
                new Route("route-1", "depot-gazipur", "station-other", 2, 8000.0, "AVAILABLE"));

        Revalidator.Outcome outcome = Revalidator.revalidate(line(5000), world, 5000);

        assertThat(outcome.decision()).isEqualTo(Revalidator.Outcome.Decision.CHANGED);
        assertThat(outcome.reason()).contains("no longer connects");
    }

    @Test
    @DisplayName("fuel already on its way is reserved at the station")
    void inboundFuelIsReserved() {
        // The station has 2000 litres and a capacity of 10000, but 3000 more are
        // already on the way. So the headroom is 10000 - 2000 - 3000 = 5000.
        WorldSnapshot world = world(
                station("station-mirpur", "OPEN", 2000, 10000),
                depot("depot-gazipur", "OPEN", 50000),
                route("route-1", "AVAILABLE", 8000),
                new Allocation(1L, "other-key", "depot-gazipur", "station-mirpur", "route-2",
                        FUEL, 3000.0, 40L, 41L, 44L, null, "IN_TRANSIT", null));

        Revalidator.Outcome outcome = Revalidator.revalidate(line(6000), world, 6000);

        assertThat(outcome.decision()).isEqualTo(Revalidator.Outcome.Decision.REDUCED);
        assertThat(outcome.quantity()).isEqualTo(5000);
    }

    @Test
    @DisplayName("a full station sends nothing")
    void fullStationSendsNothing() {
        WorldSnapshot world = world(
                station("station-mirpur", "OPEN", 10000, 10000),
                depot("depot-gazipur", "OPEN", 50000),
                route("route-1", "AVAILABLE", 8000));

        Revalidator.Outcome outcome = Revalidator.revalidate(line(5000), world, 5000);

        assertThat(outcome.decision()).isEqualTo(Revalidator.Outcome.Decision.CHANGED);
        assertThat(outcome.reason()).contains("no room");
    }

    @Test
    @DisplayName("an empty depot sends nothing")
    void emptyDepotSendsNothing() {
        WorldSnapshot world = world(
                station("station-mirpur", "OPEN", 2000, 10000),
                depot("depot-gazipur", "OPEN", 0),
                route("route-1", "AVAILABLE", 8000));

        Revalidator.Outcome outcome = Revalidator.revalidate(line(5000), world, 5000);

        assertThat(outcome.decision()).isEqualTo(Revalidator.Outcome.Decision.CHANGED);
        assertThat(outcome.reason()).contains("no DIESEL left");
    }

    @Test
    @DisplayName("the quantity is never more than the operator allowed")
    void quantityNeverExceedsTheAllowedAmount() {
        WorldSnapshot world = world(
                station("station-mirpur", "OPEN", 2000, 10000),
                depot("depot-gazipur", "OPEN", 50000),
                route("route-1", "AVAILABLE", 8000));

        // The operator reduced the line to 2500. Even though the world could
        // carry 5000, only 2500 may be sent.
        Revalidator.Outcome outcome = Revalidator.revalidate(line(5000), world, 2500);

        assertThat(outcome.quantity()).isEqualTo(2500);
        assertThat(outcome.quantity()).isLessThanOrEqualTo(2500);
    }

    @Test
    @DisplayName("the depot's dispatch allowance is shared across the lines")
    void dispatchAllowanceIsSharedAcrossLines() {
        // The depot allows 12,000 litres per tick, shared across every fuel
        // and every destination. Three lines of 5,000 cannot all be sent.
        WorldSnapshot world = world(
                station("station-mirpur", "OPEN", 2000, 10000),
                depot("depot-gazipur", "OPEN", 50000),
                route("route-1", "AVAILABLE", 8000));

        Revalidator.DispatchBudget budget = Revalidator.DispatchBudget.from(world);

        Revalidator.Outcome first = Revalidator.revalidate(line(5000), world, 5000, budget);
        Revalidator.Outcome second = Revalidator.revalidate(line(5000), world, 5000, budget);
        Revalidator.Outcome third = Revalidator.revalidate(line(5000), world, 5000, budget);

        assertThat(first.quantity()).isEqualTo(5000);
        assertThat(second.quantity()).isEqualTo(5000);
        // Only 2,000 of the 12,000 allowance is left.
        assertThat(third.quantity()).isEqualTo(2000);
        assertThat(third.decision()).isEqualTo(Revalidator.Outcome.Decision.REDUCED);
    }

    @Test
    @DisplayName("a depot with no allowance left sends nothing")
    void exhaustedAllowanceSendsNothing() {
        WorldSnapshot world = world(
                station("station-mirpur", "OPEN", 2000, 10000),
                depot("depot-gazipur", "OPEN", 50000),
                route("route-1", "AVAILABLE", 8000));

        Revalidator.DispatchBudget budget = Revalidator.DispatchBudget.from(world);

        // 5,000 + 5,000 + 2,000 uses the whole 12,000 allowance.
        Revalidator.revalidate(line(5000), world, 5000, budget);
        Revalidator.revalidate(line(5000), world, 5000, budget);
        Revalidator.revalidate(line(5000), world, 5000, budget);
        Revalidator.Outcome fourth = Revalidator.revalidate(line(5000), world, 5000, budget);

        assertThat(fourth.decision()).isEqualTo(Revalidator.Outcome.Decision.CHANGED);
        assertThat(fourth.reason()).contains("dispatch allowance");
    }

    // ------------------------------------------------------------- fixtures

    private static ProposalLine line(double reviewed) {
        return new ProposalLine(
                UUID.randomUUID(), UUID.randomUUID(), "line-1",
                "station-mirpur", FUEL, "depot-gazipur", "route-1",
                reviewed, null, 44L, 2.0, 8.0,
                List.of("station is running low"), List.of("depot stock"),
                List.of(), ProposalLine.Status.PENDING);
    }

    private static Station station(String id, String status, double inventory, double capacity) {
        return new Station(id, id, "region-dhaka", status, "urban_high", 1.0,
                Map.of(FUEL, capacity), Map.of(FUEL, inventory));
    }

    private static Depot depot(String id, String status, double inventory) {
        return new Depot(id, id, "region-dhaka", status, 12000.0,
                Map.of(FUEL, 60000.0), Map.of(FUEL, inventory));
    }

    private static Route route(String id, String status, double maxShipment) {
        return new Route(id, "depot-gazipur", "station-mirpur", 2, maxShipment, status);
    }

    private static WorldSnapshot world(Station station, Depot depot, Route route,
                                       Allocation... allocations) {
        return new WorldSnapshot(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                40L, 40L, "2026-11-22T10:00:00", Instant.now(),
                DataQuality.fresh(), List.of(),
                new Instance(1, "scenario-fuel", "1.0", 42, "2026-11-22T10:00:00", 40L, 15, "RUNNING"),
                List.of(),
                List.of(depot),
                List.of(station),
                List.of(route),
                List.of(),
                List.of(),
                List.of(allocations));
    }
}
