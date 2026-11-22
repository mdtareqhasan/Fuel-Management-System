package com.fuelplatform.decision;

import com.fuelplatform.domain.WorldSnapshot;
import com.fuelplatform.simulator.dto.SimulatorDtos.Depot;
import com.fuelplatform.simulator.dto.SimulatorDtos.Route;
import com.fuelplatform.simulator.dto.SimulatorDtos.Station;

import java.util.List;
import java.util.Optional;

/**
 * Revalidates a reviewed line against the world as it is now.
 *
 * <p>This is the control that makes ADR 003 real. The operator reviewed an
 * envelope; at approval time the envelope is checked against a fresh read, and
 * the outcome is one of four:
 *
 * <table>
 *   <caption>The four outcomes</caption>
 *   <tr><th>Fresh state</th><th>Outcome</th></tr>
 *   <tr><td>Still feasible</td><td>send the reviewed quantity</td></tr>
 *   <tr><td>Feasible for less</td><td>send the smaller quantity, never more</td></tr>
 *   <tr><td>Route gone, station in OUTAGE, depot closed</td><td>send nothing</td></tr>
 *   <tr><td>The read itself is stale or invalid</td><td>send nothing, block</td></tr>
 * </table>
 *
 * <p>This class is <strong>pure</strong>. It reads a snapshot and returns a
 * decision; it never contacts the simulator and never touches the database. That
 * is what makes every branch testable without a running world.
 */
public final class Revalidator {

    private Revalidator() {
    }

    /**
     * The dispatch allowance still available, per depot.
     *
     * <p>This is the constraint that catches people out (INV-17): the dispatch
     * allowance is <strong>per depot and shared across every fuel type and every
     * destination</strong>, and it is consumed at POST time.
     *
     * <p>A revalidator that checked each line on its own would approve six
     * lines of 5,000 litres from a depot that allows 12,000 per tick, and the
     * simulator would refuse the last four. So the allowance is reserved as
     * the lines are checked, exactly the way the planner reserves it.
     */
    public static final class DispatchBudget {

        private final java.util.Map<String, Double> remaining = new java.util.LinkedHashMap<>();
        private final java.util.Map<String, Double> capacity = new java.util.LinkedHashMap<>();

        /** Builds the budget from the world's depots. */
        public static DispatchBudget from(WorldSnapshot snapshot) {
            DispatchBudget budget = new DispatchBudget();
            for (Depot depot : snapshot.depots()) {
                double allowance = depot.dispatchCapacityPerTick() == null
                        ? 0
                        : depot.dispatchCapacityPerTick();
                budget.capacity.put(depot.id(), allowance);
                budget.remaining.put(depot.id(), allowance);
            }
            budget.consumeAlreadyDispatched(snapshot);
            return budget;
        }

        /**
         * Subtracts what this tick's earlier shipments already consumed.
         *
         * <p>The allowance is consumed at POST time and resets each tick. So a
         * second approval in the same tick has to account for the shipments the
         * first approval created — otherwise the application would approve a
         * shipment the simulator is going to refuse.
         *
         * <p>Only allocations created at the <strong>current</strong> tick count.
         * A shipment from an earlier tick consumed that tick's allowance, not
         * this one's.
         */
        private void consumeAlreadyDispatched(WorldSnapshot snapshot) {
            long currentTick = snapshot.tick();

            for (var allocation : snapshot.allocations()) {
                if (allocation.createdTick() == null
                        || allocation.createdTick() != currentTick
                        || allocation.sourceDepotId() == null
                        || allocation.quantity() == null) {
                    continue;
                }
                String status = allocation.status() == null ? "" : allocation.status();
                // A cancelled shipment refunded the allowance.
                if ("CANCELLED".equalsIgnoreCase(status) || "FAILED".equalsIgnoreCase(status)) {
                    continue;
                }
                reserve(allocation.sourceDepotId(), allocation.quantity());
            }
        }

        /** How much a depot may still dispatch in this tick. */
        public double remaining(String depotId) {
            return remaining.getOrDefault(depotId, 0.0);
        }

        /** The depot's full allowance for this tick. */
        public double capacity(String depotId) {
            return capacity.getOrDefault(depotId, 0.0);
        }

        /** Reserves a quantity against a depot's allowance. */
        public void reserve(String depotId, double quantity) {
            remaining.put(depotId, remaining(depotId) - quantity);
        }
    }

    /**
     * Checks one line against a fresh snapshot.
     *
     * @param line    the line the operator reviewed
     * @param fresh   the world as it is now
     * @param allowed the maximum the operator agreed to, which is the reviewed
     *                quantity and may be lower if the operator reduced it
     */
    public static Outcome revalidate(ProposalLine line, WorldSnapshot fresh, double allowed) {
        return revalidate(line, fresh, allowed, DispatchBudget.from(fresh));
    }

    /**
     * Checks one line against a fresh snapshot, sharing a dispatch budget.
     *
     * @param budget the allowance still available across the lines checked so
     *               far. The caller reserves from it as it goes.
     */
    public static Outcome revalidate(
            ProposalLine line, WorldSnapshot fresh, double allowed, DispatchBudget budget) {
        Station station = findStation(fresh, line.stationId());
        if (station == null) {
            return Outcome.changed("station " + line.stationId() + " no longer exists");
        }
        if (!"OPEN".equalsIgnoreCase(station.status())) {
            return Outcome.changed("station " + line.stationId() + " is " + station.status());
        }

        Depot depot = findDepot(fresh, line.sourceDepotId());
        if (depot == null) {
            return Outcome.changed("depot " + line.sourceDepotId() + " no longer exists");
        }
        if (!"OPEN".equalsIgnoreCase(depot.status())) {
            return Outcome.changed("depot " + line.sourceDepotId() + " is " + depot.status());
        }

        Route route = findRoute(fresh, line.routeId());
        if (route == null) {
            return Outcome.changed("route " + line.routeId() + " no longer exists");
        }
        if (!"AVAILABLE".equalsIgnoreCase(route.status())) {
            return Outcome.changed("route " + line.routeId() + " is " + route.status());
        }
        if (!route.destinationStationId().equals(line.stationId())
                || !route.sourceDepotId().equals(line.sourceDepotId())) {
            return Outcome.changed("route " + line.routeId() + " no longer connects "
                    + line.sourceDepotId() + " to " + line.stationId());
        }

        // The depot's stock is already net of reservations (ADR 008), so it is
        // used as returned and never reduced again.
        double depotStock = depot.inventory() == null
                ? 0
                : depot.inventory().getOrDefault(line.fuelType(), 0.0);

        // The station's headroom must still exist, and it must still have room
        // for the fuel that is already on its way (INV-18).
        double capacity = station.capacity() == null
                ? 0
                : station.capacity().getOrDefault(line.fuelType(), 0.0);
        double inventory = station.inventory() == null
                ? 0
                : station.inventory().getOrDefault(line.fuelType(), 0.0);
        double inbound = inboundFor(fresh, line.stationId(), line.fuelType());
        double headroom = capacity - inventory - inbound;

        double limit = Math.min(allowed, depotStock);
        limit = Math.min(limit, route.maxShipment() == null ? limit : route.maxShipment());
        limit = Math.min(limit, Math.max(0, headroom));

        // The depot's dispatch allowance, shared across every fuel and every
        // destination. This is what stops a proposal of six lines from
        // approving six shipments the simulator will refuse.
        double dispatchRemaining = budget.remaining(line.sourceDepotId());
        limit = Math.min(limit, dispatchRemaining);

        // Quantities are whole litres, matching the allocator.
        double sendable = Math.floor(limit);

        if (sendable <= 0) {
            if (depotStock <= 0) {
                return Outcome.changed("depot " + line.sourceDepotId() + " has no "
                        + line.fuelType() + " left");
            }
            if (headroom <= 0) {
                return Outcome.changed("station " + line.stationId() + " has no room for "
                        + line.fuelType() + " left");
            }
            if (dispatchRemaining <= 0) {
                return Outcome.changed("depot " + line.sourceDepotId()
                        + " has used its dispatch allowance of "
                        + budget.capacity(line.sourceDepotId()) + " litres for this tick");
            }
            return Outcome.changed("no route could carry any " + line.fuelType()
                    + " to " + line.stationId());
        }

        if (sendable >= allowed) {
            budget.reserve(line.sourceDepotId(), sendable);
            return Outcome.asReviewed(sendable);
        }
        budget.reserve(line.sourceDepotId(), sendable);
        return Outcome.reduced(sendable);
    }

    /** The fuel already on its way to a station for a fuel type. */
    private static double inboundFor(WorldSnapshot snapshot, String stationId, String fuel) {
        double total = 0;
        for (var allocation : snapshot.allocations()) {
            if (allocation.destinationStationId() != null
                    && allocation.destinationStationId().equals(stationId)
                    && allocation.fuelType() != null
                    && allocation.fuelType().equals(fuel)
                    && allocation.quantity() != null) {
                String status = allocation.status() == null ? "" : allocation.status();
                if ("PENDING".equalsIgnoreCase(status) || "IN_TRANSIT".equalsIgnoreCase(status)) {
                    total += allocation.quantity();
                }
            }
        }
        return total;
    }

    private static Station findStation(WorldSnapshot snapshot, String id) {
        for (Station station : snapshot.stations()) {
            if (station.id().equals(id)) {
                return station;
            }
        }
        return null;
    }

    private static Depot findDepot(WorldSnapshot snapshot, String id) {
        for (Depot depot : snapshot.depots()) {
            if (depot.id().equals(id)) {
                return depot;
            }
        }
        return null;
    }

    private static Route findRoute(WorldSnapshot snapshot, String id) {
        for (Route route : snapshot.routes()) {
            if (route.id().equals(id)) {
                return route;
            }
        }
        return null;
    }

    /**
     * The outcome of revalidating one line.
     *
     * @param status   what to do with the line
     * @param quantity the quantity that may be sent, zero when nothing may
     * @param reason   why, in one sentence the operator can read
     */
    public record Outcome(
            Decision decision,
            double quantity,
            String reason) {

        /** What the revalidation concluded. */
        public enum Decision {
            /** Send exactly what the operator reviewed. */
            AS_REVIEWED,
            /** Send less than the operator reviewed. */
            REDUCED,
            /** Send nothing. The world changed in a way that breaks the line. */
            CHANGED
        }

        static Outcome asReviewed(double quantity) {
            return new Outcome(Decision.AS_REVIEWED, quantity,
                    "still feasible; the reviewed quantity can be sent unchanged");
        }

        static Outcome reduced(double quantity) {
            return new Outcome(Decision.REDUCED, quantity,
                    "still feasible, but only " + Math.floor(quantity)
                            + " litres can be sent now, so less than the reviewed quantity");
        }

        static Outcome changed(String reason) {
            return new Outcome(Decision.CHANGED, 0, reason);
        }

        /** Whether anything may be sent for this line. */
        public boolean canShip() {
            return decision != Decision.CHANGED && quantity > 0;
        }
    }
}
