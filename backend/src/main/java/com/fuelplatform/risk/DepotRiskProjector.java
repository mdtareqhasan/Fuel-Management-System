package com.fuelplatform.risk;

import com.fuelplatform.config.RiskProperties;
import com.fuelplatform.simulator.dto.SimulatorDtos.Allocation;
import com.fuelplatform.simulator.dto.SimulatorDtos.Depot;
import com.fuelplatform.simulator.dto.SimulatorDtos.Route;
import com.fuelplatform.simulator.dto.SimulatorDtos.Station;
import com.fuelplatform.simulator.dto.SimulatorDtos.SupplyArrival;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Projects depot supply and labels the shortfall risk.
 *
 * <p>Depot risk answers a different question from station risk. Station risk asks
 * whether a pump will run dry. Depot risk asks whether the fuel needed to prevent that
 * will <em>exist</em> and arrive in time. A supply delay or shortfall hurts the depot
 * first and the station later, so the depot needs its own projection.
 *
 * <p>Two cumulative curves are built over the horizon:
 *
 * <ul>
 *   <li><strong>Available supply</strong> = current depot inventory, plus every supply
 *       arrival that is SCHEDULED or DELAYED and planned at or before the tick. An
 *       ARRIVED supply is already in the inventory and is never added twice.</li>
 *   <li><strong>Required replenishment</strong> = the sum over the depot's own region's
 *       stations of the central forecast demand up to that tick, minus current station
 *       inventory and eligible inbound allocations, floored at zero per station.</li>
 * </ul>
 *
 * <p>The first tick where required exceeds available is the shortfall tick.
 *
 * <p><strong>Depot inventory is used as returned</strong> (INV-16). The simulator deducts
 * at POST time, so the inventory is already net of pending and in-transit shipments.
 * Subtracting them again would double-count the reservations.
 */
public final class DepotRiskProjector {

    private final RiskProperties properties;

    public DepotRiskProjector(RiskProperties properties) {
        this.properties = properties;
    }

    /**
     * Projects every depot and fuel.
     *
     * @param previousSupplyArrivals the supply arrivals as last observed, used to detect
     *                                delays and shortfalls the simulator overwrote
     */
    public List<DepotRisk> projectAll(
            long snapshotTick,
            List<Depot> depots,
            List<Station> stations,
            List<Route> routes,
            List<Allocation> allocations,
            List<SupplyArrival> supplyArrivals,
            Map<String, SupplyArrival> previousSupplyArrivals,
            Map<String, DemandForecast> forecasts,
            int tickMinutes) {

        List<DepotRisk> risks = new ArrayList<>();

        for (Depot depot : depots) {
            for (String fuel : List.of("DIESEL", "PETROL", "OCTANE")) {
                risks.add(project(depot, fuel, snapshotTick, stations, routes, allocations,
                        supplyArrivals, previousSupplyArrivals, forecasts, tickMinutes));
            }
        }

        risks.sort(Comparator
                .comparingInt((DepotRisk r) -> r.level().severityRank())
                .thenComparing(DepotRisk::depotId)
                .thenComparing(DepotRisk::fuel));
        return risks;
    }

    /** Projects one depot and fuel. */
    public DepotRisk project(
            Depot depot,
            String fuel,
            long snapshotTick,
            List<Station> stations,
            List<Route> routes,
            List<Allocation> allocations,
            List<SupplyArrival> supplyArrivals,
            Map<String, SupplyArrival> previousSupplyArrivals,
            Map<String, DemandForecast> forecasts,
            int tickMinutes) {

        double currentInventory = inventoryOf(depot, fuel);
        List<String> reasons = new ArrayList<>();

        List<DepotRisk.SupplyArrivalView> scheduled = supplyArrivalViews(depot, fuel, supplyArrivals);
        List<DepotRisk.SupplyChange> changes = detectSupplyChanges(depot, fuel, supplyArrivals, previousSupplyArrivals);

        // The stations this depot is responsible for: the ones in its own region.
        List<Station> regionStations = new ArrayList<>();
        if (stations != null) {
            for (Station station : stations) {
                if (depot.regionId() != null && depot.regionId().equals(station.regionId())) {
                    regionStations.add(station);
                }
            }
        }

        List<String> affectedStations = new ArrayList<>();
        double requiredTotal = 0;

        for (Station station : regionStations) {
            DemandForecast forecast = forecasts.get(DemandForecaster.key(station.id(), fuel));
            if (forecast == null || !forecast.isKnown()) {
                continue;
            }
            double stationInventory = stationInventoryOf(station, fuel);
            double inbound = inboundFor(station, fuel, routes, allocations);
            double need = Math.max(0, forecast.centralLitersPerHour() * properties.horizonHours()
                    - stationInventory - inbound);
            requiredTotal += need;
            if (need > 0) {
                affectedStations.add(station.id() + " needs " + round(need) + " litres of " + fuel);
            }
        }

        Long shortfallTick = null;
        Double shortfallHours = null;
        Double shortfallLiters = null;

        if (tickMinutes > 0) {
            int horizonTicks = (int) Math.ceil(properties.horizonHours() * 60.0 / tickMinutes);
            Double perTick = null;
            for (Station station : regionStations) {
                DemandForecast forecast = forecasts.get(DemandForecaster.key(station.id(), fuel));
                if (forecast != null && forecast.isKnown()) {
                    perTick = forecast.centralLitersPerTick(tickMinutes);
                    break;
                }
            }

            if (perTick != null) {
                for (int offset = 1; offset <= horizonTicks; offset++) {
                    long tick = snapshotTick + offset;

                    double available = currentInventory;
                    for (DepotRisk.SupplyArrivalView arrival : scheduled) {
                        if (arrival.plannedTick() <= tick) {
                            available += arrival.quantityLiters();
                        }
                    }

                    // Required replenishment grows with time as the stations consume.
                    double required = 0;
                    for (Station station : regionStations) {
                        DemandForecast forecast = forecasts.get(DemandForecaster.key(station.id(), fuel));
                        if (forecast == null || !forecast.isKnown()) {
                            continue;
                        }
                        double stationInventory = stationInventoryOf(station, fuel);
                        double inbound = inboundFor(station, fuel, routes, allocations);
                        double consumed = forecast.centralLitersPerHour() * (offset * (double) tickMinutes / 60.0);
                        required += Math.max(0, consumed - stationInventory - inbound);
                    }

                    if (required > available) {
                        shortfallTick = tick;
                        shortfallHours = offset * (double) tickMinutes / 60.0;
                        shortfallLiters = required - available;
                        break;
                    }
                }
            }
        }

        RiskLevel level;
        if (shortfallTick == null) {
            level = RiskLevel.WATCH;
            reasons.add("depot stock of " + round(currentInventory) + " litres of " + fuel
                    + " covers the region's projected need over the next "
                    + properties.horizonHours() + " simulated hours");
        } else if (shortfallHours != null && shortfallHours <= properties.criticalHours()) {
            level = RiskLevel.CRITICAL;
            reasons.add("the depot runs short at tick " + shortfallTick + " ("
                    + round(shortfallHours) + " simulated hours from now)");
        } else {
            level = RiskLevel.WARNING;
            reasons.add("the depot runs short at tick " + shortfallTick + " ("
                    + round(shortfallHours) + " simulated hours from now)");
        }

        for (DepotRisk.SupplyChange change : changes) {
            reasons.add(change.description());
        }

        return new DepotRisk(
                depot.id(),
                fuel,
                level,
                currentInventory,
                requiredTotal,
                shortfallTick,
                shortfallHours,
                shortfallLiters,
                scheduled,
                changes,
                affectedStations,
                reasons);
    }

    /**
     * Detects delays and shortfalls between two snapshots.
     *
     * <p>The simulator <strong>overwrites</strong> {@code planned_tick} and
     * {@code quantity} when a delay or shortfall event fires, so the change is detected
     * by comparing the current reading with the first one observed. These events are
     * one-shot and do not undo themselves, so the change is reported rather than
     * waited on.
     */
    private static List<DepotRisk.SupplyChange> detectSupplyChanges(
            Depot depot,
            String fuel,
            List<SupplyArrival> supplyArrivals,
            Map<String, SupplyArrival> previousSupplyArrivals) {

        List<DepotRisk.SupplyChange> changes = new ArrayList<>();
        if (supplyArrivals == null || previousSupplyArrivals == null || previousSupplyArrivals.isEmpty()) {
            return changes;
        }

        for (SupplyArrival current : supplyArrivals) {
            if (current == null || current.id() == null) {
                continue;
            }
            if (!depot.id().equals(current.depotId())) {
                continue;
            }
            if (!fuel.equalsIgnoreCase(current.fuelType())) {
                continue;
            }
            SupplyArrival previous = previousSupplyArrivals.get(current.id());
            if (previous == null) {
                continue;
            }

            Long previousTick = previous.plannedTick();
            Long currentTick = current.plannedTick();
            if (previousTick != null && currentTick != null && currentTick > previousTick) {
                changes.add(new DepotRisk.SupplyChange(
                        current.id(),
                        fuel,
                        DepotRisk.SupplyChange.ChangeType.DELAY,
                        currentTick - previousTick,
                        0,
                        "supply " + current.id() + " was delayed by " + (currentTick - previousTick)
                                + " ticks, from tick " + previousTick + " to tick " + currentTick));
            }

            Double previousQuantity = previous.quantity();
            Double currentQuantity = current.quantity();
            if (previousQuantity != null && currentQuantity != null && currentQuantity < previousQuantity) {
                changes.add(new DepotRisk.SupplyChange(
                        current.id(),
                        fuel,
                        DepotRisk.SupplyChange.ChangeType.SHORTFALL,
                        0,
                        previousQuantity - currentQuantity,
                        "supply " + current.id() + " is short by "
                                + round(previousQuantity - currentQuantity) + " litres, planned "
                                + round(previousQuantity) + " but now " + round(currentQuantity)));
            }
        }
        return changes;
    }

    /** The supply arrivals for one depot and fuel, as a view. */
    private static List<DepotRisk.SupplyArrivalView> supplyArrivalViews(
            Depot depot, String fuel, List<SupplyArrival> supplyArrivals) {

        List<DepotRisk.SupplyArrivalView> views = new ArrayList<>();
        if (supplyArrivals == null) {
            return views;
        }
        for (SupplyArrival arrival : supplyArrivals) {
            if (arrival == null || !depot.id().equals(arrival.depotId())) {
                continue;
            }
            if (!fuel.equalsIgnoreCase(arrival.fuelType())) {
                continue;
            }
            String status = arrival.status() == null ? "" : arrival.status().toUpperCase();
            // An ARRIVED supply is already inside the depot inventory.
            if (status.equals("ARRIVED") || arrival.plannedTick() == null || arrival.quantity() == null) {
                continue;
            }
            views.add(new DepotRisk.SupplyArrivalView(
                    arrival.id(), fuel, arrival.quantity(), arrival.plannedTick(), arrival.status()));
        }
        views.sort(Comparator.comparingLong(DepotRisk.SupplyArrivalView::plannedTick));
        return views;
    }

    /**
     * The fuel already heading to a station.
     *
     * <p>Both committed IN_TRANSIT shipments and the best-case estimate for PENDING
     * shipments count here, because the depot's replenishment requirement must not
     * double-count fuel that is already on its way.
     */
    private static double inboundFor(
            Station station, String fuel, List<Route> routes, List<Allocation> allocations) {

        if (allocations == null) {
            return 0;
        }
        Map<String, Route> routesById = new LinkedHashMap<>();
        if (routes != null) {
            for (Route route : routes) {
                routesById.put(route.id(), route);
            }
        }

        double inbound = 0;
        for (Allocation allocation : allocations) {
            if (allocation == null || !station.id().equals(allocation.destinationStationId())) {
                continue;
            }
            if (!fuel.equalsIgnoreCase(allocation.fuelType())) {
                continue;
            }
            String status = allocation.status() == null ? "" : allocation.status().toUpperCase();
            if (!status.equals("IN_TRANSIT") && !status.equals("PENDING")) {
                continue;
            }
            if (allocation.quantity() == null) {
                continue;
            }
            if (status.equals("IN_TRANSIT")) {
                inbound += allocation.quantity();
            } else {
                Route route = routesById.get(allocation.routeId());
                if (route != null && route.transitTicks() != null && allocation.createdTick() != null) {
                    inbound += allocation.quantity();
                }
            }
        }
        return inbound;
    }

    private static double inventoryOf(Depot depot, String fuel) {
        Map<String, Double> inventory = depot.inventory();
        if (inventory == null) {
            return 0;
        }
        Double value = inventory.get(fuel);
        return value == null ? 0 : value;
    }

    private static double stationInventoryOf(Station station, String fuel) {
        Map<String, Double> inventory = station.inventory();
        if (inventory == null) {
            return 0;
        }
        Double value = inventory.get(fuel);
        return value == null ? 0 : value;
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
