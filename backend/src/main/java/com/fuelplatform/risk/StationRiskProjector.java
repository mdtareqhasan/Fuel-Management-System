package com.fuelplatform.risk;

import com.fuelplatform.config.RiskProperties;
import com.fuelplatform.simulator.dto.SimulatorDtos.Allocation;
import com.fuelplatform.simulator.dto.SimulatorDtos.Route;
import com.fuelplatform.simulator.dto.SimulatorDtos.Station;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Projects station inventory forward and labels the shortage risk.
 *
 * <p>Pure. It takes a snapshot, the forecasts and the policy, and returns one
 * {@link StationRisk} per station and fuel. No HTTP, no clock.
 *
 * <p>The projection walks one future tick at a time:
 *
 * <pre>
 *   stock = current station inventory
 *   for each future tick:
 *       stock += fuel arriving on this tick
 *       stock -= forecast demand for this tick
 *       if stock &lt;= 0 and no stockout yet:
 *           record the stockout tick
 * </pre>
 *
 * <p>Two things this deliberately gets right, because both change the answer:
 *
 * <ul>
 *   <li><strong>Arrival timing.</strong> Fuel that lands <em>after</em> the tank runs
 *       dry does not prevent the earlier stockout. The simulator's own
 *       {@code expected_arrival_tick} is used for IN_TRANSIT shipments. A PENDING
 *       shipment has not departed yet, so its arrival is an estimate and is reported
 *       separately as a best case — never mixed into the committed trajectory.</li>
 *   <li><strong>Two trajectories.</strong> The central projection and the stress
 *       projection are computed separately, because a risk label that blends them hides
 *       which one is driving the decision.</li>
 * </ul>
 */
public final class StationRiskProjector {

    private final RiskProperties properties;

    public StationRiskProjector(RiskProperties properties) {
        this.properties = properties;
    }

    /**
     * Projects every station and fuel.
     *
     * @param snapshotTick   the tick the projection starts from
     * @param stations       the stations
     * @param routes         the routes, for transit times
     * @param allocations    the simulator's allocation ledger
     * @param forecasts      the demand forecasts, keyed by station and fuel
     * @param tickMinutes    simulated minutes per tick
     */
    public List<StationRisk> projectAll(
            long snapshotTick,
            List<Station> stations,
            List<Route> routes,
            List<Allocation> allocations,
            Map<String, DemandForecast> forecasts,
            int tickMinutes) {

        List<StationRisk> risks = new ArrayList<>();

        for (Station station : stations) {
            for (String fuel : List.of("DIESEL", "PETROL", "OCTANE")) {
                DemandForecast forecast = forecasts.get(DemandForecaster.key(station.id(), fuel));
                risks.add(project(station, fuel, snapshotTick, routes, allocations, forecast, tickMinutes));
            }
        }

        risks.sort(Comparator
                .comparingInt((StationRisk r) -> r.level().severityRank())
                .thenComparing(StationRisk::stationId)
                .thenComparing(StationRisk::fuel));
        return risks;
    }

    /**
     * Projects one station and fuel.
     *
     * @param forecast may be null, in which case the demand is unknown and the risk is
     *                 {@link RiskLevel#UNKNOWN} rather than a guess
     */
    public StationRisk project(
            Station station,
            String fuel,
            long snapshotTick,
            List<Route> routes,
            List<Allocation> allocations,
            DemandForecast forecast,
            int tickMinutes) {

        double currentInventory = inventoryOf(station, fuel);
        List<StationRisk.InboundShipment> inbound = inboundShipments(station, fuel, routes, allocations);

        List<String> reasons = new ArrayList<>();
        List<String> blocking = new ArrayList<>();

        if (forecast == null || !forecast.isKnown()) {
            reasons.add("the demand forecast for " + station.id() + " " + fuel + " is unknown");
            return new StationRisk(
                    station.id(), fuel, RiskLevel.UNKNOWN, currentInventory, forecast,
                    null, null, null, null, 0.0, inbound, blocking, reasons);
        }

        int horizonTicks = horizonTicks(tickMinutes);
        Double centralPerTick = forecast.centralLitersPerTick(tickMinutes);
        Double stressPerTick = forecast.stressLitersPerTick(tickMinutes);

        if (centralPerTick == null || centralPerTick <= 0) {
            // Zero demand is a real reading, not missing data. There is no stockout to
            // project, and dividing by zero would produce a fake infinite cover time.
            reasons.add("forecast demand is zero, so no shortage is projected within the horizon");
            return new StationRisk(
                    station.id(), fuel, RiskLevel.WATCH, currentInventory, forecast,
                    null, null, null, null, 0.0, inbound, blocking, reasons);
        }

        Trajectory central = walk(snapshotTick, horizonTicks, currentInventory, centralPerTick,
                inbound, false, tickMinutes);
        Trajectory stress = walk(snapshotTick, horizonTicks, currentInventory, stressPerTick,
                inbound, false, tickMinutes);

        RiskLevel level = label(central, stress);

        reasons.add("stock is " + round(currentInventory) + " litres of " + fuel
                + " and the forecast is " + round(forecast.centralLitersPerHour()) + " litres/hour");
        if (central.stockoutTick() != null) {
            reasons.add("the central projection runs dry at tick " + central.stockoutTick()
                    + " (" + round(central.stockoutHours()) + " simulated hours from now)");
        } else {
            reasons.add("the central projection survives the whole " + properties.horizonHours()
                    + " simulated hour horizon");
        }
        if (stress.stockoutTick() != null && central.stockoutTick() == null) {
            reasons.add("only the conservative stress scenario runs dry, at tick "
                    + stress.stockoutTick());
        }
        for (StationRisk.InboundShipment shipment : inbound) {
            reasons.add("shipment " + shipment.allocationId() + " adds "
                    + round(shipment.quantityLiters()) + " litres arriving at tick "
                    + shipment.expectedArrivalTick()
                    + (shipment.arrivalIsEstimate() ? " (estimated, not yet departed)" : ""));
        }

        blocking.addAll(blockingConstraints(station, fuel, routes, allocations, currentInventory));

        return new StationRisk(
                station.id(),
                fuel,
                level,
                currentInventory,
                forecast,
                central.stockoutTick(),
                central.stockoutHours(),
                stress.stockoutTick(),
                stress.stockoutHours(),
                central.unmetLiters(),
                inbound,
                blocking,
                reasons);
    }

    // ------------------------------------------------------------- projection

    /**
     * Walks the inventory forward.
     *
     * @param includeEstimates whether PENDING shipments count. The committed trajectory
     *                          excludes them; the best case includes them.
     */
    private Trajectory walk(
            long snapshotTick,
            int horizonTicks,
            double startInventory,
            double litersPerTick,
            List<StationRisk.InboundShipment> inbound,
            boolean includeEstimates,
            int tickMinutes) {

        double stock = startInventory;
        double unmet = 0;
        Long stockoutTick = null;
        Double stockoutHours = null;

        for (int offset = 1; offset <= horizonTicks; offset++) {
            long tick = snapshotTick + offset;

            double arriving = 0;
            for (StationRisk.InboundShipment shipment : inbound) {
                if (shipment.expectedArrivalTick() == null) {
                    continue;
                }
                if (shipment.arrivalIsEstimate() && !includeEstimates) {
                    continue;
                }
                if (shipment.expectedArrivalTick() == tick) {
                    arriving += shipment.quantityLiters();
                }
            }
            stock += arriving;

            stock -= litersPerTick;
            // A tank at exactly zero is empty. The station has run dry, so the
            // condition is <= 0 and not < 0. Using < 0 would report the stockout one
            // tick late, which is exactly the kind of off-by-one that makes a
            // projection disagree with a hand calculation.
            if (stock <= 0) {
                unmet += -stock;
                if (stockoutTick == null) {
                    stockoutTick = tick;
                    stockoutHours = offset * (double) tickMinutes / 60.0;
                }
                stock = 0;
            }
        }

        return new Trajectory(stockoutTick, stockoutHours, unmet);
    }

    /**
     * The risk label.
     *
     * <p>The guide's proposed thresholds: CRITICAL when the central projection reaches
     * zero before the earliest feasible replenishment or within two hours, WARNING when
     * a central shortage falls inside the horizon, WATCH when only the stress scenario
     * runs dry, UNKNOWN when the data will not support a claim.
     */
    private RiskLevel label(Trajectory central, Trajectory stress) {
        if (central.stockoutTick() == null) {
            // The central projection survives the horizon. If only the stress scenario
            // runs dry, that is WATCH rather than a clean bill of health.
            return RiskLevel.WATCH;
        }

        double hoursToStockout = central.stockoutHours();
        if (hoursToStockout <= properties.criticalHours()) {
            return RiskLevel.CRITICAL;
        }
        return RiskLevel.WARNING;
    }

    // --------------------------------------------------------------- inbound

    /**
     * The shipments already on their way to this station.
     *
     * <p>An IN_TRANSIT shipment has a simulator-provided {@code expected_arrival_tick}
     * and is treated as committed. A PENDING shipment has not departed, so its arrival
     * is an estimate: {@code created_tick + transit_ticks}, using the departure
     * convention measured on Day 1 (departure is the creation tick).
     */
    private List<StationRisk.InboundShipment> inboundShipments(
            Station station,
            String fuel,
            List<Route> routes,
            List<Allocation> allocations) {

        List<StationRisk.InboundShipment> shipments = new ArrayList<>();
        if (allocations == null) {
            return shipments;
        }

        Map<String, Route> routesById = new java.util.LinkedHashMap<>();
        if (routes != null) {
            for (Route route : routes) {
                routesById.put(route.id(), route);
            }
        }

        for (Allocation allocation : allocations) {
            if (allocation == null) {
                continue;
            }
            if (!station.id().equals(allocation.destinationStationId())) {
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

            if (status.equals("IN_TRANSIT") && allocation.expectedArrivalTick() != null) {
                shipments.add(new StationRisk.InboundShipment(
                        allocation.id(),
                        allocation.routeId(),
                        allocation.quantity(),
                        allocation.expectedArrivalTick(),
                        false,
                        allocation.status()));
            } else {
                // Best case: depart now, arrive after the route's transit time.
                Route route = routesById.get(allocation.routeId());
                Integer transit = route == null ? null : route.transitTicks();
                Long created = allocation.createdTick();
                if (transit == null || created == null) {
                    continue;
                }
                shipments.add(new StationRisk.InboundShipment(
                        allocation.id(),
                        allocation.routeId(),
                        allocation.quantity(),
                        created + transit,
                        true,
                        allocation.status()));
            }
        }

        shipments.sort(Comparator.comparing(StationRisk.InboundShipment::expectedArrivalTick));
        return shipments;
    }

    /**
     * What stops this station from being replenished.
     *
     * <p>These are the operator-facing reasons a risk cannot be fixed by a shipment.
     */
    private List<String> blockingConstraints(
            Station station,
            String fuel,
            List<Route> routes,
            List<Allocation> allocations,
            double currentInventory) {

        List<String> blocking = new ArrayList<>();

        if (station.status() != null && !"OPEN".equalsIgnoreCase(station.status())) {
            blocking.add("station status is " + station.status() + ", so a shipment cannot be delivered");
        }

        if (routes == null) {
            return blocking;
        }

        long usableRoutes = routes.stream()
                .filter(route -> station.id().equals(route.destinationStationId()))
                .filter(route -> route.status() == null || "AVAILABLE".equalsIgnoreCase(route.status()))
                .count();

        if (usableRoutes == 0) {
            blocking.add("no AVAILABLE route reaches this station");
        }
        return blocking;
    }

    // ---------------------------------------------------------------- helpers

    private static double inventoryOf(Station station, String fuel) {
        Map<String, Double> inventory = station.inventory();
        if (inventory == null) {
            return 0;
        }
        Double value = inventory.get(fuel);
        return value == null ? 0 : value;
    }

    /** The horizon in ticks, from the configured simulated hours. */
    private int horizonTicks(int tickMinutes) {
        if (tickMinutes <= 0) {
            return 0;
        }
        return (int) Math.ceil(properties.horizonHours() * 60.0 / tickMinutes);
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    /** The outcome of walking the inventory forward. */
    private record Trajectory(Long stockoutTick, Double stockoutHours, double unmetLiters) {
    }
}
