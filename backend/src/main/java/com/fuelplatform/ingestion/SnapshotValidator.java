package com.fuelplatform.ingestion;

import com.fuelplatform.simulator.dto.SimulatorDtos.Allocation;
import com.fuelplatform.simulator.dto.SimulatorDtos.Depot;
import com.fuelplatform.simulator.dto.SimulatorDtos.DomainEvent;
import com.fuelplatform.simulator.dto.SimulatorDtos.Instance;
import com.fuelplatform.simulator.dto.SimulatorDtos.Route;
import com.fuelplatform.simulator.dto.SimulatorDtos.Station;
import com.fuelplatform.simulator.dto.SimulatorDtos.SupplyArrival;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Validates a candidate view of the world before it is published.
 *
 * <p>The single rule that drives everything here (invariant INV-6):
 * <strong>a missing value is unknown, never zero.</strong> Treating an absent
 * {@code inventory.DIESEL} as 0 would tell the operator a station is empty when the
 * truth is that the simulator did not tell us. That turns into a wrong recommendation
 * and a wrong dispatch.
 *
 * <p>Validation is a <em>gate</em>, not a transform. If anything required is missing,
 * invalid or inconsistent, the whole snapshot is rejected and the previous valid
 * snapshot is kept. A partially-valid world is not a world.
 */
public final class SnapshotValidator {

    private static final Set<String> KNOWN_FUELS = Set.of("DIESEL", "PETROL", "OCTANE");

    private final List<String> errors = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    /**
     * @return the list of validation problems. Empty means the snapshot may be published.
     */
    public List<String> validate(Instance instance,
                                 List<Depot> depots,
                                 List<Station> stations,
                                 List<Route> routes,
                                 List<SupplyArrival> supplyArrivals,
                                 List<DomainEvent> events,
                                 List<Allocation> allocations) {

        errors.clear();
        warnings.clear();

        validateInstance(instance);
        validateDepots(depots);
        validateStations(stations);
        validateRoutes(routes, depots, stations);
        validateSupplyArrivals(supplyArrivals, depots);
        validateAllocations(allocations, depots, stations, routes);

        return List.copyOf(errors);
    }

    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    // ------------------------------------------------------------- instance

    private void validateInstance(Instance instance) {
        if (instance == null) {
            errors.add("instance response missing");
            return;
        }
        if (instance.tick() == null) {
            errors.add("instance.tick missing");
        } else if (instance.tick() < 0) {
            errors.add("instance.tick must not be negative, got " + instance.tick());
        }
        if (instance.tickMinutes() == null) {
            errors.add("instance.tick_minutes missing");
        } else if (instance.tickMinutes() <= 0) {
            errors.add("instance.tick_minutes must be positive, got " + instance.tickMinutes());
        }
        if (instance.status() == null || instance.status().isBlank()) {
            errors.add("instance.status missing");
        }
        if (instance.simTime() == null || instance.simTime().isBlank()) {
            // sim_time is naive in the live response, so it is kept as a string.
            // A missing value is unknown, not "now".
            errors.add("instance.sim_time missing");
        }
    }

    // --------------------------------------------------------------- depots

    private void validateDepots(List<Depot> depots) {
        if (depots == null || depots.isEmpty()) {
            errors.add("no depots returned");
            return;
        }
        Set<String> seen = new HashSet<>();
        for (Depot depot : depots) {
            if (depot.id() == null || depot.id().isBlank()) {
                errors.add("depot without an id");
                continue;
            }
            if (!seen.add(depot.id())) {
                errors.add("duplicate depot id " + depot.id());
            }
            if (depot.dispatchCapacityPerTick() == null) {
                errors.add("depot " + depot.id() + " missing dispatch_capacity_per_tick");
            } else if (depot.dispatchCapacityPerTick() < 0) {
                errors.add("depot " + depot.id() + " has negative dispatch capacity");
            }
            validateFuelMap(depot.id(), "capacity", depot.capacity());
            validateFuelMap(depot.id(), "inventory", depot.inventory());
        }
    }

    // ------------------------------------------------------------- stations

    private void validateStations(List<Station> stations) {
        if (stations == null || stations.isEmpty()) {
            errors.add("no stations returned");
            return;
        }
        Set<String> seen = new HashSet<>();
        for (Station station : stations) {
            if (station.id() == null || station.id().isBlank()) {
                errors.add("station without an id");
                continue;
            }
            if (!seen.add(station.id())) {
                errors.add("duplicate station id " + station.id());
            }
            validateFuelMap(station.id(), "capacity", station.capacity());
            validateFuelMap(station.id(), "inventory", station.inventory());
        }
    }

    // --------------------------------------------------------------- routes

    private void validateRoutes(List<Route> routes, List<Depot> depots, List<Station> stations) {
        if (routes == null || routes.isEmpty()) {
            errors.add("no routes returned");
            return;
        }
        Set<String> depotIds = ids(depots == null ? List.of() : depots.stream().map(Depot::id).toList());
        Set<String> stationIds = ids(stations == null ? List.of() : stations.stream().map(Station::id).toList());

        Set<String> seen = new HashSet<>();
        for (Route route : routes) {
            if (route.id() == null || route.id().isBlank()) {
                errors.add("route without an id");
                continue;
            }
            if (!seen.add(route.id())) {
                errors.add("duplicate route id " + route.id());
            }
            // A route pointing at a depot or station that does not exist is a broken
            // link. The simulator would reject any allocation on it anyway, so letting
            // it through would only produce a confusing failure later.
            if (route.sourceDepotId() != null && !depotIds.contains(route.sourceDepotId())) {
                errors.add("route " + route.id() + " references unknown depot " + route.sourceDepotId());
            }
            if (route.destinationStationId() != null && !stationIds.contains(route.destinationStationId())) {
                errors.add("route " + route.id() + " references unknown station " + route.destinationStationId());
            }
            if (route.transitTicks() == null) {
                errors.add("route " + route.id() + " missing transit_ticks");
            } else if (route.transitTicks() < 0) {
                errors.add("route " + route.id() + " has negative transit_ticks");
            }
            if (route.maxShipment() == null) {
                errors.add("route " + route.id() + " missing max_shipment");
            } else if (route.maxShipment() <= 0) {
                errors.add("route " + route.id() + " has non-positive max_shipment");
            }
        }
    }

    // ------------------------------------------------------- supply arrivals

    private void validateSupplyArrivals(List<SupplyArrival> supplyArrivals, List<Depot> depots) {
        if (supplyArrivals == null) {
            return;
        }
        Set<String> depotIds = ids(depots == null ? List.of() : depots.stream().map(Depot::id).toList());
        for (SupplyArrival arrival : supplyArrivals) {
            if (arrival.id() == null || arrival.id().isBlank()) {
                errors.add("supply arrival without an id");
                continue;
            }
            if (arrival.depotId() != null && !depotIds.contains(arrival.depotId())) {
                errors.add("supply arrival " + arrival.id() + " references unknown depot " + arrival.depotId());
            }
            if (arrival.quantity() == null) {
                errors.add("supply arrival " + arrival.id() + " missing quantity");
            } else if (arrival.quantity() < 0) {
                errors.add("supply arrival " + arrival.id() + " has negative quantity");
            }
            if (arrival.plannedTick() == null) {
                errors.add("supply arrival " + arrival.id() + " missing planned_tick");
            }
        }
    }

    // ----------------------------------------------------------- allocations

    private void validateAllocations(List<Allocation> allocations, List<Depot> depots,
                                     List<Station> stations, List<Route> routes) {
        if (allocations == null) {
            return;
        }
        Set<String> depotIds = ids(depots == null ? List.of() : depots.stream().map(Depot::id).toList());
        Set<String> stationIds = ids(stations == null ? List.of() : stations.stream().map(Station::id).toList());
        Set<String> routeIds = ids(routes == null ? List.of() : routes.stream().map(Route::id).toList());

        Set<String> seenKeys = new HashSet<>();
        for (Allocation allocation : allocations) {
            if (allocation.id() == null) {
                errors.add("allocation without an id");
                continue;
            }
            if (allocation.idempotencyKey() == null || allocation.idempotencyKey().isBlank()) {
                errors.add("allocation " + allocation.id() + " missing idempotency_key");
            } else if (!seenKeys.add(allocation.idempotencyKey())) {
                // Two rows sharing a key would break the reconciliation rule that
                // assumes one key means one shipment.
                errors.add("duplicate idempotency_key " + allocation.idempotencyKey()
                        + " in the simulator ledger");
            }
            if (allocation.sourceDepotId() != null && !depotIds.contains(allocation.sourceDepotId())) {
                warnings.add("allocation " + allocation.id() + " references unknown depot "
                        + allocation.sourceDepotId());
            }
            if (allocation.destinationStationId() != null && !stationIds.contains(allocation.destinationStationId())) {
                warnings.add("allocation " + allocation.id() + " references unknown station "
                        + allocation.destinationStationId());
            }
            if (allocation.routeId() != null && !routeIds.contains(allocation.routeId())) {
                warnings.add("allocation " + allocation.id() + " references unknown route "
                        + allocation.routeId());
            }
            if (allocation.quantity() == null) {
                errors.add("allocation " + allocation.id() + " missing quantity");
            } else if (allocation.quantity() <= 0) {
                errors.add("allocation " + allocation.id() + " has non-positive quantity");
            }
        }
    }

    // ----------------------------------------------------------------- fuel

    /**
     * Validates a per-fuel map.
     *
     * <p>A missing fuel key is an error, not a zero. If the simulator stops reporting
     * OCTANE for a station, the honest answer is "we do not know", and the snapshot is
     * rejected so the previous valid one is kept.
     */
    private void validateFuelMap(String owner, String field, java.util.Map<String, Double> map) {
        if (map == null) {
            errors.add(owner + " missing " + field + " map");
            return;
        }
        for (String fuel : KNOWN_FUELS) {
            Double value = map.get(fuel);
            if (value == null) {
                errors.add(owner + "." + field + " missing fuel " + fuel);
            } else if (!Double.isFinite(value)) {
                errors.add(owner + "." + field + "." + fuel + " is not a finite number");
            } else if (value < 0) {
                errors.add(owner + "." + field + "." + fuel + " is negative: " + value);
            }
        }
    }

    private static Set<String> ids(List<String> values) {
        Set<String> set = new HashSet<>();
        for (String value : values) {
            if (value != null) {
                set.add(value);
            }
        }
        return set;
    }
}
