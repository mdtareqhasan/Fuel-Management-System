package com.fuelplatform.risk;

import java.util.List;

/**
 * The projected shortage risk for one station and one fuel.
 *
 * <p>This is a <strong>deterministic scenario</strong>, not a probability of stockout
 * (invariant INV-23). The central trajectory uses the central forecast; the stress
 * trajectory uses the conservative policy scenario. Both are computed and reported
 * separately rather than blended into a single "risk score".
 *
 * @param stationId                the station
 * @param fuel                     the fuel type
 * @param level                    the risk label
 * @param currentInventoryLiters   the stock right now
 * @param forecast                 the demand forecast used for the projection
 * @param centralStockoutTick      first tick where the central projection hits zero, null if it survives
 * @param centralStockoutHours     the same, in simulated hours
 * @param stressStockoutTick       first tick where the stress projection hits zero
 * @param stressStockoutHours      the same, in simulated hours
 * @param projectedUnmetLiters     central unmet demand across the horizon
 * @param inboundArrivals           shipments already on their way to this station
 * @param blockingConstraints       what prevents replenishment, in operator language
 * @param reasons                  the explanation shown beside the label
 */
public record StationRisk(
        String stationId,
        String fuel,
        RiskLevel level,
        double currentInventoryLiters,
        DemandForecast forecast,
        Long centralStockoutTick,
        Double centralStockoutHours,
        Long stressStockoutTick,
        Double stressStockoutHours,
        double projectedUnmetLiters,
        List<InboundShipment> inboundArrivals,
        List<String> blockingConstraints,
        List<String> reasons) {

    public StationRisk {
        inboundArrivals = inboundArrivals == null ? List.of() : List.copyOf(inboundArrivals);
        blockingConstraints = blockingConstraints == null ? List.of() : List.copyOf(blockingConstraints);
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    /**
     * A shipment already on its way to this station.
     *
     * <p>{@code expectedArrivalTick} is the simulator's own field for IN_TRANSIT
     * shipments. For a PENDING shipment the departure is not yet observed, so the
     * arrival is an estimate and is flagged as such — a best case, shown separately
     * from the committed arrivals.
     */
    public record InboundShipment(
            long allocationId,
            String routeId,
            double quantityLiters,
            Long expectedArrivalTick,
            boolean arrivalIsEstimate,
            String status) {
    }
}
