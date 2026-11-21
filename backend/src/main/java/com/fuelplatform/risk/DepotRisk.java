package com.fuelplatform.risk;

import java.util.List;

/**
 * The projected supply risk for one depot and one fuel.
 *
 * <p>Depot risk answers a different question from station risk. Station risk asks
 * whether a pump will run dry. Depot risk asks whether the fuel needed to prevent that
 * will <em>exist</em> and arrive in time. A supply delay or shortfall hurts the depot
 * first and the station later, so the depot needs its own projection.
 *
 * @param depotId                 the depot
 * @param fuel                    the fuel type
 * @param level                   the risk label, using the same thresholds as station risk
 * @param currentInventoryLiters  depot stock right now
 * @param requiredReplenishmentLiters  what the region's stations need over the horizon
 * @param shortfallTick           first tick where required replenishment exceeds available supply
 * @param shortfallHours          the same, in simulated hours
 * @param shortfallLiters         how much is short at that tick
 * @param scheduledArrivals       supply already on its way into this depot
 * @param supplyChanges           delays and shortfalls detected between snapshots
 * @param affectedStations        the stations in this depot's region, most urgent first
 * @param reasons                 the explanation shown beside the label
 */
public record DepotRisk(
        String depotId,
        String fuel,
        RiskLevel level,
        double currentInventoryLiters,
        double requiredReplenishmentLiters,
        Long shortfallTick,
        Double shortfallHours,
        Double shortfallLiters,
        List<SupplyArrivalView> scheduledArrivals,
        List<SupplyChange> supplyChanges,
        List<String> affectedStations,
        List<String> reasons) {

    public DepotRisk {
        scheduledArrivals = scheduledArrivals == null ? List.of() : List.copyOf(scheduledArrivals);
        supplyChanges = supplyChanges == null ? List.of() : List.copyOf(supplyChanges);
        affectedStations = affectedStations == null ? List.of() : List.copyOf(affectedStations);
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    /**
     * A scheduled supply arrival into this depot.
     *
     * <p>A depot supply arrival is <strong>not</strong> a station delivery. It never
     * adds to station inventory directly.
     */
    public record SupplyArrivalView(
            String supplyId,
            String fuel,
            double quantityLiters,
            long plannedTick,
            String status) {
    }

    /**
     * A change in a scheduled supply between two snapshots.
     *
     * <p>The simulator overwrites {@code planned_tick} and {@code quantity} when a delay
     * or shortfall event fires, so the change is detected by comparing snapshots rather
     * than by reading the event alone.
     */
    public record SupplyChange(
            String supplyId,
            String fuel,
            ChangeType type,
            long ticks,
            double liters,
            String description) {

        /** What kind of change was detected. */
        public enum ChangeType {
            /** The arrival was pushed later. */
            DELAY,
            /** The arrival is smaller than planned. */
            SHORTFALL
        }
    }
}
