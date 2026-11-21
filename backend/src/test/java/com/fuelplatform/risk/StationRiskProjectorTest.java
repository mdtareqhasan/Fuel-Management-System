package com.fuelplatform.risk;

import com.fuelplatform.config.RiskProperties;
import com.fuelplatform.simulator.dto.SimulatorDtos.Allocation;
import com.fuelplatform.simulator.dto.SimulatorDtos.Route;
import com.fuelplatform.simulator.dto.SimulatorDtos.Station;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the station projection.
 *
 * <p>The question the projection has to answer is "when does this pump run dry", and
 * the detail that makes it honest is <strong>arrival timing</strong>: fuel that lands
 * after the tank empties does not prevent the stockout.
 */
class StationRiskProjectorTest {

    private static final int TICK_MINUTES = 15;

    private final RiskProperties properties = new RiskProperties(8, 2, 3, 1.35, 1.15, 2);
    private final StationRiskProjector projector = new StationRiskProjector(properties);

    private static Station station(String id, double dieselInventory, double dieselCapacity) {
        return new Station(id, id, "region-dhaka", "OPEN", "urban_high", 1.0,
                Map.of("DIESEL", dieselCapacity, "PETROL", 14000.0, "OCTANE", 9000.0),
                Map.of("DIESEL", dieselInventory, "PETROL", 9000.0, "OCTANE", 5000.0));
    }

    private static DemandForecast forecast(String stationId, double litersPerHour) {
        return new DemandForecast(stationId, "DIESEL", ForecastMethod.EWMA, DataConfidence.GOOD,
                8, 8, litersPerHour, litersPerHour * 1.25, 1.0, 1.5, List.of(), List.of());
    }

    @Test
    @DisplayName("a station with enough stock is not at risk")
    void stationWithEnoughStockIsNotAtRisk() {
        StationRisk risk = projector.project(
                station("station-mirpur", 9000, 15000), "DIESEL", 40L,
                List.of(), List.of(), forecast("station-mirpur", 400), TICK_MINUTES);

        assertThat(risk.level()).isEqualTo(RiskLevel.WATCH);
        assertThat(risk.centralStockoutTick()).isNull();
        assertThat(risk.centralStockoutHours()).isNull();
    }

    @Test
    @DisplayName("the stockout tick is the first tick where the tank reaches zero")
    void stockoutTickIsTheFirstEmptyTick() {
        // 900 litres at 200 litres/hour is 50 litres per tick, so the tank empties
        // after 18 ticks. Starting from tick 40, that is tick 58, which is 4.5
        // simulated hours away.
        StationRisk risk = projector.project(
                station("station-mirpur", 900, 15000), "DIESEL", 40L,
                List.of(), List.of(), forecast("station-mirpur", 200), TICK_MINUTES);

        assertThat(risk.centralStockoutTick()).isEqualTo(58L);
        assertThat(risk.centralStockoutHours()).isEqualTo(4.5);
        assertThat(risk.level()).isEqualTo(RiskLevel.WARNING);
    }

    @Test
    @DisplayName("a stockout within two hours is CRITICAL")
    void stockoutWithinTwoHoursIsCritical() {
        // 300 litres at 400 litres/hour is 0.75 hours.
        StationRisk risk = projector.project(
                station("station-mirpur", 300, 15000), "DIESEL", 40L,
                List.of(), List.of(), forecast("station-mirpur", 400), TICK_MINUTES);

        assertThat(risk.level()).isEqualTo(RiskLevel.CRITICAL);
        assertThat(risk.centralStockoutHours()).isLessThanOrEqualTo(2.0);
    }

    @Test
    @DisplayName("fuel arriving after the tank empties does not prevent the earlier stockout")
    void lateArrivalDoesNotPreventTheEarlierStockout() {
        // 300 litres at 400 litres/hour is 100 litres per tick, so the tank empties
        // three ticks after the snapshot, at tick 43. The shipment arrives at tick
        // 50, seven ticks later, so the stockout still happens at tick 43.
        Allocation inTransit = new Allocation(1L, "key-1", "depot-gazipur", "station-mirpur",
                "route-gazipur-mirpur", "DIESEL", 5000.0, 40L, 40L, 50L, null, "IN_TRANSIT", null);

        StationRisk risk = projector.project(
                station("station-mirpur", 300, 15000), "DIESEL", 40L,
                List.of(new Route("route-gazipur-mirpur", "depot-gazipur", "station-mirpur", 10, 7000.0, "AVAILABLE")),
                List.of(inTransit), forecast("station-mirpur", 400), TICK_MINUTES);

        assertThat(risk.centralStockoutTick()).isEqualTo(43L);
    }

    @Test
    @DisplayName("fuel arriving before the tank empties prevents the stockout")
    void earlyArrivalPreventsTheStockout() {
        // The tank would empty at tick 43. The shipment lands at tick 41, in time.
        Allocation inTransit = new Allocation(1L, "key-1", "depot-gazipur", "station-mirpur",
                "route-gazipur-mirpur", "DIESEL", 5000.0, 40L, 40L, 41L, null, "IN_TRANSIT", null);

        StationRisk risk = projector.project(
                station("station-mirpur", 300, 15000), "DIESEL", 40L,
                List.of(new Route("route-gazipur-mirpur", "depot-gazipur", "station-mirpur", 1, 7000.0, "AVAILABLE")),
                List.of(inTransit), forecast("station-mirpur", 400), TICK_MINUTES);

        assertThat(risk.centralStockoutTick()).isNull();
    }

    @Test
    @DisplayName("a PENDING shipment's arrival is an estimate and is marked as one")
    void pendingShipmentArrivalIsAnEstimate() {
        // A PENDING shipment has not departed, so its arrival is a best case.
        Allocation pending = new Allocation(1L, "key-1", "depot-gazipur", "station-mirpur",
                "route-gazipur-mirpur", "DIESEL", 5000.0, 40L, null, null, null, "PENDING", null);

        StationRisk risk = projector.project(
                station("station-mirpur", 9000, 15000), "DIESEL", 40L,
                List.of(new Route("route-gazipur-mirpur", "depot-gazipur", "station-mirpur", 2, 7000.0, "AVAILABLE")),
                List.of(pending), forecast("station-mirpur", 400), TICK_MINUTES);

        assertThat(risk.inboundArrivals()).hasSize(1);
        StationRisk.InboundShipment shipment = risk.inboundArrivals().get(0);
        assertThat(shipment.arrivalIsEstimate()).isTrue();
        // created_tick 40 + transit 2
        assertThat(shipment.expectedArrivalTick()).isEqualTo(42L);
    }

    @Test
    @DisplayName("an IN_TRANSIT shipment uses the simulator's own expected_arrival_tick")
    void inTransitUsesTheSimulatorArrivalTick() {
        Allocation inTransit = new Allocation(1L, "key-1", "depot-gazipur", "station-mirpur",
                "route-gazipur-mirpur", "DIESEL", 5000.0, 40L, 40L, 57L, null, "IN_TRANSIT", null);

        StationRisk risk = projector.project(
                station("station-mirpur", 9000, 15000), "DIESEL", 40L,
                List.of(new Route("route-gazipur-mirpur", "depot-gazipur", "station-mirpur", 2, 7000.0, "AVAILABLE")),
                List.of(inTransit), forecast("station-mirpur", 400), TICK_MINUTES);

        StationRisk.InboundShipment shipment = risk.inboundArrivals().get(0);
        assertThat(shipment.arrivalIsEstimate()).isFalse();
        assertThat(shipment.expectedArrivalTick()).isEqualTo(57L);
    }

    @Test
    @DisplayName("zero forecast demand produces no stockout and no fake infinite time")
    void zeroDemandProducesNoStockout() {
        StationRisk risk = projector.project(
                station("station-mirpur", 9000, 15000), "DIESEL", 40L,
                List.of(), List.of(), forecast("station-mirpur", 0), TICK_MINUTES);

        assertThat(risk.centralStockoutTick()).isNull();
        assertThat(risk.centralStockoutHours()).isNull();
        assertThat(risk.reasons()).anyMatch(r -> r.contains("zero"));
    }

    @Test
    @DisplayName("an unknown forecast produces UNKNOWN risk, not a zero risk")
    void unknownForecastProducesUnknownRisk() {
        StationRisk risk = projector.project(
                station("station-mirpur", 9000, 15000), "DIESEL", 40L,
                List.of(), List.of(), null, TICK_MINUTES);

        assertThat(risk.level()).isEqualTo(RiskLevel.UNKNOWN);
        assertThat(risk.centralStockoutTick()).isNull();
    }

    @Test
    @DisplayName("a station with no available route is reported as blocking replenishment")
    void stationWithNoRouteIsBlocked() {
        Station outage = new Station("station-mirpur", "Mirpur", "region-dhaka", "OUTAGE",
                "urban_high", 1.0,
                Map.of("DIESEL", 15000.0, "PETROL", 14000.0, "OCTANE", 9000.0),
                Map.of("DIESEL", 300.0, "PETROL", 9000.0, "OCTANE", 5000.0));

        StationRisk risk = projector.project(
                outage, "DIESEL", 40L, List.of(), List.of(), forecast("station-mirpur", 400), TICK_MINUTES);

        assertThat(risk.blockingConstraints()).anyMatch(c -> c.contains("OUTAGE"));
        assertThat(risk.blockingConstraints()).anyMatch(c -> c.contains("no AVAILABLE route"));
    }

    @Test
    @DisplayName("the risk table is sorted with the most urgent rows first")
    void riskTableIsSortedByUrgency() {
        Station mirpur = station("station-mirpur", 300, 15000);
        Station tongi = station("station-tongi", 9000, 15000);

        DemandForecast urgent = forecast("station-mirpur", 400);
        DemandForecast calm = forecast("station-tongi", 400);

        List<StationRisk> risks = projector.projectAll(
                40L,
                List.of(mirpur, tongi),
                List.of(),
                List.of(),
                Map.of(
                        "station-mirpur|DIESEL", urgent,
                        "station-tongi|DIESEL", calm),
                TICK_MINUTES);

        assertThat(risks).isNotEmpty();
        // The station that runs dry first must be first in the list.
        assertThat(risks.get(0).stationId()).isEqualTo("station-mirpur");
        assertThat(risks.get(0).level()).isEqualTo(RiskLevel.CRITICAL);
    }
}
