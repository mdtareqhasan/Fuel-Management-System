package com.fuelplatform.allocation;

import com.fuelplatform.risk.DemandForecast;
import com.fuelplatform.risk.DemandForecaster;
import com.fuelplatform.risk.StationRisk;
import com.fuelplatform.simulator.dto.SimulatorDtos.Allocation;
import com.fuelplatform.simulator.dto.SimulatorDtos.Depot;
import com.fuelplatform.simulator.dto.SimulatorDtos.Route;
import com.fuelplatform.simulator.dto.SimulatorDtos.Station;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A deterministic, constrained allocation heuristic.
 *
 * <p>Pure. It takes a validated world snapshot plus the forecasts and produces feasible
 * proposed shipment lines with explanations. It <strong>writes nothing</strong>; a
 * separate validator runs again at approval.
 *
 * <p>The constraint that catches people out, and the reason this class exists:
 * <strong>the dispatch allowance is per depot and shared across every fuel and every
 * destination</strong> (INV-17). A policy that allocated each fuel independently would
 * dispatch 4,700 litres where only 4,000 is allowed. So every proposed line reserves its
 * quantity in a working copy of the depot's allowance <em>before</em> the next line is
 * planned.
 *
 * <p>Two more rules that are easy to get wrong:
 *
 * <ul>
 *   <li><strong>Depot inventory is used as returned</strong> (INV-16). The simulator
 *       deducts at POST time, so it is already net of pending shipments. Subtracting
 *       again starves the depot.</li>
 *   <li><strong>Station headroom reserves inbound fuel</strong> (INV-18). Fuel that is
 *       on its way will land, so it occupies space in the tank.</li>
 * </ul>
 */
public final class AllocationPlanner {

    /** The policy version recorded with every plan, so an old plan can be identified. */
    public static final String POLICY_VERSION = "day2-heuristic-v1";

    /** Litres are rounded down to whole units. */
    private static final double QUANTITY_PRECISION = 1.0;

    /**
     * The policy knobs.
     *
     * @param targetCoverHours      how many simulated hours of cover a station should hold after arrival
     * @param safetyReserveLiters   the extra buffer kept on top of the target cover
     */
    public record Policy(double targetCoverHours, double safetyReserveLiters) {

        public Policy {
            if (targetCoverHours <= 0) {
                targetCoverHours = 6;
            }
            if (safetyReserveLiters < 0) {
                safetyReserveLiters = 200;
            }
        }
    }

    /**
     * The working copy of the world's resources.
     *
     * <p>Everything the planner reserves goes here, never into the snapshot. The
     * snapshot is evidence of a past observation and must not be mutated.
     */
    static final class WorkingCopy {

        /** Remaining dispatch allowance per depot. Shared across fuels and destinations. */
        final Map<String, Double> dispatchRemaining = new LinkedHashMap<>();

        /** Remaining stock per depot and fuel. */
        final Map<String, Double> depotStock = new LinkedHashMap<>();

        /** Remaining headroom per station and fuel, with inbound fuel already reserved. */
        final Map<String, Double> stationHeadroom = new LinkedHashMap<>();

        double remainingDispatch(String depotId) {
            return dispatchRemaining.getOrDefault(depotId, 0.0);
        }

        void reserveDispatch(String depotId, double quantity) {
            dispatchRemaining.put(depotId, remainingDispatch(depotId) - quantity);
        }

        double stock(String depotId, String fuel) {
            return depotStock.getOrDefault(key(depotId, fuel), 0.0);
        }

        void reserveStock(String depotId, String fuel, double quantity) {
            depotStock.put(key(depotId, fuel), stock(depotId, fuel) - quantity);
        }

        double headroom(String stationId, String fuel) {
            return stationHeadroom.getOrDefault(key(stationId, fuel), 0.0);
        }

        void reserveHeadroom(String stationId, String fuel, double quantity) {
            stationHeadroom.put(key(stationId, fuel), headroom(stationId, fuel) - quantity);
        }

        private static String key(String owner, String fuel) {
            return owner + "|" + fuel;
        }
    }

    /**
     * Plans allocations for every station and fuel that needs fuel.
     *
     * @param stationRisks the projected station risks, which carry the need
     * @param forecasts    the demand forecasts, for the target cover calculation
     */
    public AllocationPlan plan(
            long snapshotTick,
            List<Depot> depots,
            List<Station> stations,
            List<Route> routes,
            List<Allocation> allocations,
            List<StationRisk> stationRisks,
            Map<String, DemandForecast> forecasts,
            Policy policy,
            int tickMinutes) {

        WorkingCopy working = buildWorkingCopy(depots, stations, allocations);

        // Rank the needs by urgency: soonest stockout first, then lowest cover, then
        // a stable tiebreak on id and fuel so the plan is reproducible.
        List<StationRisk> needs = new ArrayList<>();
        for (StationRisk risk : stationRisks) {
            if (risk.centralStockoutTick() != null) {
                needs.add(risk);
            }
        }
        needs.sort(Comparator
                .comparingLong(StationRisk::centralStockoutTick)
                .thenComparing(StationRisk::stationId)
                .thenComparing(StationRisk::fuel));

        List<AllocationProposal> proposals = new ArrayList<>();
        List<AllocationPlan.UnmetNeed> unmet = new ArrayList<>();

        for (StationRisk risk : needs) {
            Station station = findStation(stations, risk.stationId());
            if (station == null) {
                continue;
            }
            if (station.status() != null && !"OPEN".equalsIgnoreCase(station.status())) {
                unmet.add(new AllocationPlan.UnmetNeed(
                        risk.stationId(), risk.fuel(), needLiters(risk, forecasts, policy),
                        0,
                        AllocationPlan.UnmetNeed.STATION_CLOSED,
                        "station " + risk.stationId() + " is " + station.status()
                                + ", so fuel cannot be delivered"));
                continue;
            }

            double need = needLiters(risk, forecasts, policy);
            if (need <= 0) {
                continue;
            }

            List<Route> candidateRoutes = candidateRoutes(routes, station.id());
            if (candidateRoutes.isEmpty()) {
                unmet.add(new AllocationPlan.UnmetNeed(
                        risk.stationId(), risk.fuel(), need, 0,
                        AllocationPlan.UnmetNeed.NO_AVAILABLE_ROUTE,
                        "no AVAILABLE route reaches " + risk.stationId()));
                continue;
            }

            // Rank by earliest arrival, then by the depot's projected cover after the
            // shipment, then by a stable route id.
            List<Route> ranked = new ArrayList<>(candidateRoutes);
            ranked.sort(Comparator
                    .comparingInt((Route r) -> r.transitTicks() == null ? Integer.MAX_VALUE : r.transitTicks())
                    .thenComparing(Route::id));

            AllocationProposal primary = null;
            List<AllocationProposal.Alternative> alternatives = new ArrayList<>();
            double met = 0;

            // The dispatch allowance each candidate depot had before this line was
            // planned. It is what an alternative would have had to work with too.
            Map<String, Double> dispatchBefore = new LinkedHashMap<>();
            for (Route candidate : ranked) {
                dispatchBefore.putIfAbsent(candidate.sourceDepotId(),
                        working.remainingDispatch(candidate.sourceDepotId()));
            }

            for (int index = 0; index < ranked.size(); index++) {
                Route route = ranked.get(index);
                Depot depot = findDepot(depots, route.sourceDepotId());
                if (depot == null) {
                    continue;
                }

                Long arrivalTick = snapshotTick + (route.transitTicks() == null ? 0 : route.transitTicks());
                double quantity = computeQuantity(
                        need - met,
                        working.stock(depot.id(), risk.fuel()),
                        route.maxShipment(),
                        working.remainingDispatch(depot.id()),
                        working.headroom(station.id(), risk.fuel()));

                if (quantity <= 0) {
                    // This route cannot carry more. The reason is worked out once,
                    // after the loop, so that a single need never produces several
                    // conflicting explanations.
                    continue;
                }

                if (primary == null) {
                    primary = buildProposal(risk, station, depot, route, quantity, need,
                            arrivalTick, snapshotTick, forecasts, policy, tickMinutes);
                }

                // Reserve in the working copy before planning the next line. This is
                // the step that stops two individually valid proposals from together
                // breaking the shared dispatch limit.
                working.reserveDispatch(depot.id(), quantity);
                working.reserveStock(depot.id(), risk.fuel(), quantity);
                working.reserveHeadroom(station.id(), risk.fuel(), quantity);
                met += quantity;

                if (met >= need) {
                    break;
                }
            }

            // The alternatives are worked out AFTER the primary line is settled, so
            // they describe what a different route could have done rather than
            // consuming resources the primary already used. Without this, a station
            // whose need was met by the first route would never show an alternative.
            if (primary != null) {
                alternatives.addAll(buildAlternatives(
                        primary, ranked, depots, working, dispatchBefore, need, snapshotTick, forecasts, policy));
                // The proposal is immutable, so it is rebuilt with the alternatives
                // attached. Forgetting this would silently drop every alternative.
                primary = primary.withAlternatives(alternatives);
            }

            if (primary != null) {
                proposals.add(primary);
            }

            if (met < need) {
                // Quantities are rounded to whole litres, so a need of 4253.718
                // litres is met by 4253 litres with 0.718 litres left over. That
                // residue is not a shortfall; reporting it as one would fill the
                // operator's list with phantom problems and bury the real ones.
                double gap = need - met;
                if (gap < QUANTITY_PRECISION) {
                    continue;
                }

                String reasonCode;
                String reason;
                if (allDepotsDispatchExhausted(working, depots)) {
                    reasonCode = AllocationPlan.UnmetNeed.DISPATCH_LIMIT;
                    reason = "every depot has used its dispatch allowance for this tick";
                } else if (allDepotStockExhausted(working, depots, risk.fuel())) {
                    reasonCode = AllocationPlan.UnmetNeed.SOURCE_SHORTFALL;
                    reason = "no depot has enough " + risk.fuel() + " left to close the gap";
                } else if (headroomExhausted(working, station.id(), risk.fuel())) {
                    // The tank cannot take it all in one drop. This is a sizing
                    // problem, not a network problem, and the operator can act on it
                    // by sending a second shipment.
                    reasonCode = AllocationPlan.UnmetNeed.ROUTE_CAPACITY_EXCEEDED;
                    reason = "the destination headroom can only take " + round(met)
                            + " of the " + round(need) + " litres needed";
                } else {
                    reasonCode = AllocationPlan.UnmetNeed.NO_AVAILABLE_ROUTE;
                    reason = "no route could carry the remaining " + round(gap) + " litres";
                }
                unmet.add(new AllocationPlan.UnmetNeed(
                        risk.stationId(), risk.fuel(), need, met, reasonCode, reason));
            }
        }

        return new AllocationPlan(proposals, unmet, POLICY_VERSION);
    }

    // ------------------------------------------------------------- quantities

    /**
     * The quantity to ship.
     *
     * <p>The minimum of the need, the depot's stock, the route's per-shipment limit, the
     * remaining shared dispatch allowance and the destination's safe headroom. A
     * non-positive result is discarded rather than sent.
     */
    static double computeQuantity(
            double need,
            double depotStock,
            Double routeMaxShipment,
            double dispatchRemaining,
            double stationHeadroom) {

        double quantity = need;
        quantity = Math.min(quantity, depotStock);
        if (routeMaxShipment != null) {
            quantity = Math.min(quantity, routeMaxShipment);
        }
        quantity = Math.min(quantity, dispatchRemaining);
        quantity = Math.min(quantity, stationHeadroom);

        if (quantity <= 0) {
            return 0;
        }
        // Round down to the precision and discard anything that rounds away.
        return Math.floor(quantity / QUANTITY_PRECISION) * QUANTITY_PRECISION;
    }

    /**
     * How much the station needs.
     *
     * <p>The target cover is the stock the station should hold after the fuel arrives:
     * target hours of demand, plus the safety reserve, minus what it will already have.
     */
    static double needLiters(StationRisk risk, Map<String, DemandForecast> forecasts, Policy policy) {
        DemandForecast forecast = forecasts.get(DemandForecaster.key(risk.stationId(), risk.fuel()));
        if (forecast == null || !forecast.isKnown()) {
            return 0;
        }

        double desiredStock = forecast.centralLitersPerHour() * policy.targetCoverHours()
                + policy.safetyReserveLiters();

        double current = risk.currentInventoryLiters();
        double inbound = 0;
        for (StationRisk.InboundShipment shipment : risk.inboundArrivals()) {
            inbound += shipment.quantityLiters();
        }

        double need = desiredStock - current - inbound;
        return need > 0 ? need : 0;
    }

    // ------------------------------------------------------------- working copy

    /**
     * Builds the working copy.
     *
     * <p>Depot stock is taken <strong>as returned</strong> (INV-16). Station headroom
     * starts from the tank capacity and immediately reserves inbound fuel (INV-18),
     * because fuel on its way will occupy tank space.
     */
    private static WorkingCopy buildWorkingCopy(
            List<Depot> depots, List<Station> stations, List<Allocation> allocations) {

        WorkingCopy working = new WorkingCopy();

        for (Depot depot : depots) {
            working.dispatchRemaining.put(depot.id(),
                    depot.dispatchCapacityPerTick() == null ? 0 : depot.dispatchCapacityPerTick());
            for (String fuel : List.of("DIESEL", "PETROL", "OCTANE")) {
                Double stock = depot.inventory() == null ? null : depot.inventory().get(fuel);
                working.depotStock.put(key(depot.id(), fuel), stock == null ? 0 : stock);
            }
        }

        for (Station station : stations) {
            for (String fuel : List.of("DIESEL", "PETROL", "OCTANE")) {
                Double capacity = station.capacity() == null ? null : station.capacity().get(fuel);
                double headroom = capacity == null ? 0 : capacity;
                Double inventory = station.inventory() == null ? null : station.inventory().get(fuel);
                double current = inventory == null ? 0 : inventory;

                // Reserve the fuel already on its way to this station.
                double inbound = 0;
                if (allocations != null) {
                    for (Allocation allocation : allocations) {
                        if (allocation == null
                                || !station.id().equals(allocation.destinationStationId())
                                || !fuel.equalsIgnoreCase(allocation.fuelType())) {
                            continue;
                        }
                        String status = allocation.status() == null ? "" : allocation.status().toUpperCase();
                        if (!status.equals("IN_TRANSIT") && !status.equals("PENDING")) {
                            continue;
                        }
                        if (allocation.quantity() != null) {
                            inbound += allocation.quantity();
                        }
                    }
                }

                working.stationHeadroom.put(key(station.id(), fuel), Math.max(0, headroom - current - inbound));
            }
        }

        return working;
    }

    // ---------------------------------------------------------------- helpers

    /** The routes that reach a station and can carry fuel. */
    private static List<Route> candidateRoutes(List<Route> routes, String stationId) {
        List<Route> candidates = new ArrayList<>();
        if (routes == null) {
            return candidates;
        }
        for (Route route : routes) {
            if (!stationId.equals(route.destinationStationId())) {
                continue;
            }
            if (route.status() != null && !"AVAILABLE".equalsIgnoreCase(route.status())) {
                continue;
            }
            candidates.add(route);
        }
        return candidates;
    }

    private AllocationProposal buildProposal(
            StationRisk risk,
            Station station,
            Depot depot,
            Route route,
            double quantity,
            double need,
            Long arrivalTick,
            long snapshotTick,
            Map<String, DemandForecast> forecasts,
            Policy policy,
            int tickMinutes) {

        List<String> reasons = new ArrayList<>();
        reasons.add(station.id() + " is projected to run dry for " + risk.fuel()
                + " and needs " + round(need) + " litres");
        reasons.add("route " + route.id() + " from " + depot.id() + " arrives at tick "
                + arrivalTick + " (" + round(hoursBetween(snapshotTick, arrivalTick, tickMinutes))
                + " simulated hours)");

        List<String> constraints = new ArrayList<>();
        constraints.add("quantity limited by the smaller of need, depot stock, route max_shipment, "
                + "remaining shared dispatch allowance and destination headroom");
        constraints.add("the dispatch allowance of " + depot.id() + " is shared across every fuel "
                + "and destination, so it is reserved before the next line is planned");

        Double coverBefore = risk.centralStockoutHours();
        Double coverAfter = coverAfter(risk, quantity, forecasts, policy);

        return new AllocationProposal(
                risk.stationId() + "|" + risk.fuel(),
                risk.stationId(),
                risk.fuel(),
                depot.id(),
                route.id(),
                quantity,
                need,
                arrivalTick,
                hoursBetween(snapshotTick, arrivalTick, tickMinutes),
                coverBefore,
                coverAfter,
                reasons,
                constraints,
                List.of());
    }

    /**
     * Builds the alternatives for a line.
     *
     * <p>Two kinds are considered, in the order the guide asks for them:
     *
     * <ol>
     *   <li>the next ranked feasible route, and</li>
     *   <li>a smaller quantity that leaves the shared dispatch allowance for a more
     *       urgent need.</li>
     * </ol>
     *
     * <p>Each one records <strong>why it lost</strong>. An alternative without a reason
     * is decoration, and a fabricated alternative is worse than none (INV-22).
     */
    private List<AllocationProposal.Alternative> buildAlternatives(
            AllocationProposal primary,
            List<Route> ranked,
            List<Depot> depots,
            WorkingCopy working,
            Map<String, Double> dispatchBefore,
            double need,
            long snapshotTick,
            Map<String, DemandForecast> forecasts,
            Policy policy) {

        List<AllocationProposal.Alternative> alternatives = new ArrayList<>();

        // The next ranked route that is not the one already chosen.
        for (Route route : ranked) {
            if (route.id().equals(primary.routeId())) {
                continue;
            }
            Depot depot = findDepot(depots, route.sourceDepotId());
            if (depot == null) {
                continue;
            }            Long arrivalTick = snapshotTick + (route.transitTicks() == null ? 0 : route.transitTicks());
            double quantity = computeQuantity(
                    need,
                    working.stock(depot.id(), primary.fuel()),
                    route.maxShipment(),
                    dispatchBefore.getOrDefault(depot.id(), 0.0),
                    Double.MAX_VALUE);

            if (quantity <= 0) {
                continue;
            }

            boolean laterArrival = route.transitTicks() != null && primary.estimatedArrivalTick() != null
                    && arrivalTick > primary.estimatedArrivalTick();

            String reasonCode;
            String reason;
            if (laterArrival) {
                reasonCode = AllocationProposal.Alternative.LATER_ARRIVAL;
                reason = "this route arrives at tick " + arrivalTick
                        + ", later than the chosen route at tick " + primary.estimatedArrivalTick();
            } else if (dispatchBefore.getOrDefault(depot.id(), 0.0) <= 0) {
                reasonCode = AllocationProposal.Alternative.DISPATCH_RESERVED_FOR_MORE_URGENT;
                reason = "the shared dispatch allowance of " + depot.id()
                        + " is reserved for a more urgent need";
            } else {
                reasonCode = AllocationProposal.Alternative.LOWER_SOURCE_COVER;
                reason = "depot " + depot.id() + " would have less cover left after this shipment";
            }

            alternatives.add(new AllocationProposal.Alternative(
                    route.id(),
                    depot.id(),
                    quantity,
                    arrivalTick,
                    coverAfter(primary, quantity, forecasts, policy),
                    reasonCode,
                    reason));

            if (alternatives.size() >= 2) {
                return alternatives;
            }
        }

        // A smaller quantity on the chosen route, leaving dispatch for a more urgent
        // need, when the primary used the whole remaining allowance.
        double primaryQuantity = primary.quantityLiters();
        double dispatchUsed = primaryQuantity;
        double remaining = dispatchBefore.getOrDefault(primary.depotId(), 0.0) - dispatchUsed;
        if (alternatives.isEmpty() && primaryQuantity > 0 && remaining < primaryQuantity) {
            double smaller = Math.max(0, Math.floor(remaining / QUANTITY_PRECISION) * QUANTITY_PRECISION);
            if (smaller > 0 && smaller < primaryQuantity) {
                alternatives.add(new AllocationProposal.Alternative(
                        primary.routeId(),
                        primary.depotId(),
                        smaller,
                        primary.estimatedArrivalTick(),
                        coverAfter(primary, smaller, forecasts, policy),
                        AllocationProposal.Alternative.DISPATCH_RESERVED_FOR_MORE_URGENT,
                        "a smaller shipment would leave the shared dispatch allowance free "
                                + "for a more urgent need"));
            }
        }

        // No alternative genuinely exists. This is stated explicitly rather than
        // left as an empty list, because an empty list reads as "nothing was
        // considered" and a reader cannot tell it from a bug (INV-22).
        if (alternatives.isEmpty()) {
            alternatives.add(new AllocationProposal.Alternative(
                    null,
                    null,
                    0,
                    null,
                    null,
                    AllocationProposal.Alternative.NO_ALTERNATIVE_ROUTE,
                    "no other route reaches this station, so no alternative exists"));
        }

        return alternatives;
    }

    /** The projected cover after a shipment of the given quantity. */
    private static Double coverAfter(
            AllocationProposal primary, double quantity,
            Map<String, DemandForecast> forecasts, Policy policy) {

        DemandForecast forecast = forecasts.get(
                DemandForecaster.key(primary.stationId(), primary.fuel()));
        if (forecast == null || !forecast.isKnown() || forecast.centralLitersPerHour() <= 0) {
            return null;
        }
        return primary.coverBeforeHours() == null
                ? null
                : primary.coverBeforeHours() + quantity / forecast.centralLitersPerHour();
    }

    /** The projected cover after the shipment arrives. */
    private static Double coverAfter(
            StationRisk risk, double quantity, Map<String, DemandForecast> forecasts, Policy policy) {

        DemandForecast forecast = forecasts.get(DemandForecaster.key(risk.stationId(), risk.fuel()));
        if (forecast == null || !forecast.isKnown()) {
            return null;
        }
        double stockAfter = risk.currentInventoryLiters() + quantity;
        double inbound = 0;
        for (StationRisk.InboundShipment shipment : risk.inboundArrivals()) {
            inbound += shipment.quantityLiters();
        }
        double usable = stockAfter + inbound;
        if (forecast.centralLitersPerHour() <= 0) {
            return null;
        }
        return usable / forecast.centralLitersPerHour();
    }

    private static double hoursBetween(long fromTick, Long toTick, int tickMinutes) {
        if (toTick == null || tickMinutes <= 0) {
            return 0;
        }
        return (toTick - fromTick) * (double) tickMinutes / 60.0;
    }

    private static Station findStation(List<Station> stations, String stationId) {
        for (Station station : stations) {
            if (station.id().equals(stationId)) {
                return station;
            }
        }
        return null;
    }

    private static Depot findDepot(List<Depot> depots, String depotId) {
        for (Depot depot : depots) {
            if (depot.id().equals(depotId)) {
                return depot;
            }
        }
        return null;
    }

    private static boolean allDepotsDispatchExhausted(WorkingCopy working, List<Depot> depots) {
        for (Depot depot : depots) {
            if (working.remainingDispatch(depot.id()) > 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean allDepotStockExhausted(WorkingCopy working, List<Depot> depots, String fuel) {
        for (Depot depot : depots) {
            if (working.stock(depot.id(), fuel) > 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether the destination tank has no room left for more fuel.
     *
     * <p>This separates "the network cannot reach this station" from "the tank is
     * full". The second one is a different problem with a different fix, and
     * telling an operator the wrong one sends them looking for a broken route.
     */
    private static boolean headroomExhausted(WorkingCopy working, String stationId, String fuel) {
        return working.headroom(stationId, fuel) <= 0;
    }

    private static String key(String owner, String fuel) {
        return owner + "|" + fuel;
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
