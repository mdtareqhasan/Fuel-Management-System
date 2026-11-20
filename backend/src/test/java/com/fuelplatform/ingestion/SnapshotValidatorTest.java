package com.fuelplatform.ingestion;

import com.fuelplatform.simulator.dto.SimulatorDtos.Allocation;
import com.fuelplatform.simulator.dto.SimulatorDtos.Depot;
import com.fuelplatform.simulator.dto.SimulatorDtos.Instance;
import com.fuelplatform.simulator.dto.SimulatorDtos.Route;
import com.fuelplatform.simulator.dto.SimulatorDtos.Station;
import com.fuelplatform.simulator.dto.SimulatorDtos.SupplyArrival;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the validation gate.
 *
 * <p>The rule under test throughout: <strong>a missing value is unknown, never zero</strong>
 * (invariant INV-6). A snapshot that silently fills a missing fuel inventory with 0
 * would tell the operator a station is empty, produce a wrong recommendation, and
 * dispatch fuel that was never needed.
 */
class SnapshotValidatorTest {

    private final SnapshotValidator validator = new SnapshotValidator();

    // ------------------------------------------------------------- fixtures

    private Instance validInstance() {
        return new Instance(1, "baseline", "1.0", 12345, "2026-01-01T00:00:00", 0L, 15, "PAUSED");
    }

    private Depot validDepot() {
        return new Depot("depot-gazipur", "Gazipur Depot", "region-dhaka", "OPEN", 12000.0,
                Map.of("DIESEL", 90000.0, "PETROL", 70000.0, "OCTANE", 45000.0),
                Map.of("DIESEL", 60000.0, "PETROL", 45000.0, "OCTANE", 26000.0));
    }

    private Station validStation() {
        return new Station("station-mirpur", "Mirpur", "region-dhaka", "OPEN", "urban_high", 1.0,
                Map.of("DIESEL", 15000.0, "PETROL", 14000.0, "OCTANE", 9000.0),
                Map.of("DIESEL", 9000.0, "PETROL", 9000.0, "OCTANE", 5000.0));
    }

    private Route validRoute() {
        return new Route("route-gazipur-mirpur", "depot-gazipur", "station-mirpur", 2, 7000.0, "AVAILABLE");
    }

    private SupplyArrival validSupply() {
        return new SupplyArrival("supply-001", "depot-gazipur", "DIESEL", 18000.0, 12L, 12L, "ARRIVED");
    }

    private List<String> validateWith(Instance instance, List<Depot> depots, List<Station> stations,
                                      List<Route> routes, List<SupplyArrival> supplies, List<Allocation> allocations) {
        return validator.validate(instance, depots, stations, routes, supplies, List.of(), allocations);
    }

    // --------------------------------------------------------------- happy

    @Test
    @DisplayName("a complete, consistent world passes validation")
    void acceptsValidSnapshot() {
        List<String> errors = validateWith(validInstance(),
                List.of(validDepot()), List.of(validStation()), List.of(validRoute()),
                List.of(validSupply()), List.of());

        assertThat(errors).isEmpty();
    }

    // ---------------------------------------------------------- missing is unknown

    @Test
    @DisplayName("a missing fuel in a station inventory is rejected, not read as zero")
    void rejectsMissingFuelInStationInventory() {
        Station station = new Station("station-mirpur", "Mirpur", "region-dhaka", "OPEN", "urban_high", 1.0,
                Map.of("DIESEL", 15000.0, "PETROL", 14000.0, "OCTANE", 9000.0),
                Map.of("DIESEL", 9000.0, "PETROL", 9000.0)); // OCTANE missing

        List<String> errors = validateWith(validInstance(),
                List.of(validDepot()), List.of(station), List.of(validRoute()),
                List.of(validSupply()), List.of());

        assertThat(errors).anyMatch(e -> e.contains("station-mirpur")
                && e.contains("inventory") && e.contains("OCTANE"));
    }

    @Test
    @DisplayName("a missing dispatch capacity is rejected")
    void rejectsMissingDispatchCapacity() {
        Depot depot = new Depot("depot-gazipur", "Gazipur", "region-dhaka", "OPEN", null,
                Map.of("DIESEL", 90000.0, "PETROL", 70000.0, "OCTANE", 45000.0),
                Map.of("DIESEL", 60000.0, "PETROL", 45000.0, "OCTANE", 26000.0));

        List<String> errors = validateWith(validInstance(),
                List.of(depot), List.of(validStation()), List.of(validRoute()),
                List.of(validSupply()), List.of());

        assertThat(errors).anyMatch(e -> e.contains("dispatch_capacity_per_tick"));
    }

    @Test
    @DisplayName("a missing instance is rejected")
    void rejectsMissingInstance() {
        List<String> errors = validateWith(null,
                List.of(validDepot()), List.of(validStation()), List.of(validRoute()),
                List.of(validSupply()), List.of());

        assertThat(errors).anyMatch(e -> e.contains("instance"));
    }

    @Test
    @DisplayName("a missing tick_minutes is rejected, because every time conversion depends on it")
    void rejectsMissingTickMinutes() {
        Instance instance = new Instance(1, "baseline", "1.0", 12345, "2026-01-01T00:00:00", 0L, null, "PAUSED");

        List<String> errors = validateWith(instance,
                List.of(validDepot()), List.of(validStation()), List.of(validRoute()),
                List.of(validSupply()), List.of());

        assertThat(errors).anyMatch(e -> e.contains("tick_minutes"));
    }

    // ------------------------------------------------------------- negatives

    @Test
    @DisplayName("a negative inventory is rejected as impossible")
    void rejectsNegativeInventory() {
        Depot depot = new Depot("depot-gazipur", "Gazipur", "region-dhaka", "OPEN", 12000.0,
                Map.of("DIESEL", 90000.0, "PETROL", 70000.0, "OCTANE", 45000.0),
                Map.of("DIESEL", -1.0, "PETROL", 45000.0, "OCTANE", 26000.0));

        List<String> errors = validateWith(validInstance(),
                List.of(depot), List.of(validStation()), List.of(validRoute()),
                List.of(validSupply()), List.of());

        assertThat(errors).anyMatch(e -> e.contains("negative"));
    }

    @Test
    @DisplayName("a non-finite quantity is rejected")
    void rejectsNonFiniteQuantity() {
        Depot depot = new Depot("depot-gazipur", "Gazipur", "region-dhaka", "OPEN", 12000.0,
                Map.of("DIESEL", 90000.0, "PETROL", 70000.0, "OCTANE", 45000.0),
                Map.of("DIESEL", Double.NaN, "PETROL", 45000.0, "OCTANE", 26000.0));

        List<String> errors = validateWith(validInstance(),
                List.of(depot), List.of(validStation()), List.of(validRoute()),
                List.of(validSupply()), List.of());

        assertThat(errors).anyMatch(e -> e.contains("finite"));
    }

    // ---------------------------------------------------------- broken links

    @Test
    @DisplayName("a route pointing at an unknown station is rejected as a broken link")
    void rejectsRouteWithUnknownStation() {
        Route route = new Route("route-gazipur-mirpur", "depot-gazipur", "station-ghost", 2, 7000.0, "AVAILABLE");

        List<String> errors = validateWith(validInstance(),
                List.of(validDepot()), List.of(validStation()), List.of(route),
                List.of(validSupply()), List.of());

        assertThat(errors).anyMatch(e -> e.contains("unknown station"));
    }

    @Test
    @DisplayName("a route pointing at an unknown depot is rejected as a broken link")
    void rejectsRouteWithUnknownDepot() {
        Route route = new Route("route-ghost-mirpur", "depot-ghost", "station-mirpur", 2, 7000.0, "AVAILABLE");

        List<String> errors = validateWith(validInstance(),
                List.of(validDepot()), List.of(validStation()), List.of(route),
                List.of(validSupply()), List.of());

        assertThat(errors).anyMatch(e -> e.contains("unknown depot"));
    }

    @Test
    @DisplayName("duplicate ids are rejected")
    void rejectsDuplicateIds() {
        List<String> errors = validateWith(validInstance(),
                List.of(validDepot(), validDepot()), List.of(validStation()), List.of(validRoute()),
                List.of(validSupply()), List.of());

        assertThat(errors).anyMatch(e -> e.contains("duplicate depot id"));
    }

    // ------------------------------------------------------- allocations

    @Test
    @DisplayName("two allocations sharing an idempotency key are rejected")
    void rejectsDuplicateIdempotencyKey() {
        Allocation first = new Allocation(1L, "key-1", "depot-gazipur", "station-mirpur",
                "route-gazipur-mirpur", "DIESEL", 3000.0, 0L, 0L, 2L, null, "PENDING", null);
        Allocation second = new Allocation(2L, "key-1", "depot-gazipur", "station-mirpur",
                "route-gazipur-mirpur", "DIESEL", 3000.0, 0L, 0L, 2L, null, "PENDING", null);

        List<String> errors = validateWith(validInstance(),
                List.of(validDepot()), List.of(validStation()), List.of(validRoute()),
                List.of(validSupply()), List.of(first, second));

        assertThat(errors).anyMatch(e -> e.contains("duplicate idempotency_key"));
    }

    @Test
    @DisplayName("an allocation without an idempotency key is rejected")
    void rejectsAllocationWithoutKey() {
        Allocation allocation = new Allocation(1L, null, "depot-gazipur", "station-mirpur",
                "route-gazipur-mirpur", "DIESEL", 3000.0, 0L, 0L, 2L, null, "PENDING", null);

        List<String> errors = validateWith(validInstance(),
                List.of(validDepot()), List.of(validStation()), List.of(validRoute()),
                List.of(validSupply()), List.of(allocation));

        assertThat(errors).anyMatch(e -> e.contains("idempotency_key"));
    }

    @Test
    @DisplayName("an allocation with a non-positive quantity is rejected")
    void rejectsAllocationWithNonPositiveQuantity() {
        Allocation allocation = new Allocation(1L, "key-1", "depot-gazipur", "station-mirpur",
                "route-gazipur-mirpur", "DIESEL", 0.0, 0L, 0L, 2L, null, "PENDING", null);

        List<String> errors = validateWith(validInstance(),
                List.of(validDepot()), List.of(validStation()), List.of(validRoute()),
                List.of(validSupply()), List.of(allocation));

        assertThat(errors).anyMatch(e -> e.contains("non-positive quantity"));
    }

    // --------------------------------------------------------- empty world

    @Test
    @DisplayName("an empty world is rejected rather than accepted as 'nothing is wrong'")
    void rejectsEmptyWorld() {
        List<String> errors = validateWith(validInstance(),
                List.of(), List.of(), List.of(), List.of(), List.of());

        assertThat(errors).isNotEmpty();
        assertThat(errors).anyMatch(e -> e.contains("no depots"));
        assertThat(errors).anyMatch(e -> e.contains("no stations"));
        assertThat(errors).anyMatch(e -> e.contains("no routes"));
    }
}
