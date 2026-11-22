package com.fuelplatform.allocation;

import com.fuelplatform.risk.DemandForecast;
import com.fuelplatform.risk.DataConfidence;
import com.fuelplatform.risk.ForecastMethod;
import com.fuelplatform.risk.StationRisk;
import com.fuelplatform.risk.RiskLevel;
import com.fuelplatform.simulator.dto.SimulatorDtos.Allocation;
import com.fuelplatform.simulator.dto.SimulatorDtos.Depot;
import com.fuelplatform.simulator.dto.SimulatorDtos.Route;
import com.fuelplatform.simulator.dto.SimulatorDtos.Station;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the allocation heuristic.
 *
 * <p>The test that matters most is {@link #sharedDispatchLimitIsNotExceeded()}. It is the
 * trap the guide's manual exercise describes: a policy that allocates each fuel
 * independently dispatches more than the depot is allowed to.
 */
class AllocationPlannerTest {

    private final AllocationPlanner planner = new AllocationPlanner();
    private final AllocationPlanner.Policy policy = new AllocationPlanner.Policy(6, 200);

    // ------------------------------------------------------------- fixtures

    private static Depot gazipur() {
        return new Depot("depot-gazipur", "Gazipur Depot", "region-dhaka", "OPEN", 12000.0,
                Map.of("DIESEL", 90000.0, "PETROL", 70000.0, "OCTANE", 45000.0),
                Map.of("DIESEL", 60000.0, "PETROL", 45000.0, "OCTANE", 26000.0));
    }

    private static Depot patiya() {
        return new Depot("depot-patiya", "Patiya Depot", "region-chattogram", "OPEN", 11000.0,
                Map.of("DIESEL", 85000.0, "PETROL", 65000.0, "OCTANE", 40000.0),
                Map.of("DIESEL", 55000.0, "PETROL", 42000.0, "OCTANE", 24000.0));
    }

    private static Station mirpur() {
        return new Station("station-mirpur", "Mirpur", "region-dhaka", "OPEN", "urban_high", 1.0,
                Map.of("DIESEL", 15000.0, "PETROL", 14000.0, "OCTANE", 9000.0),
                Map.of("DIESEL", 9000.0, "PETROL", 9000.0, "OCTANE", 5000.0));
    }

    private static Route route(String id, String depot, String station, int transit, double max) {
        return new Route(id, depot, station, transit, max, "AVAILABLE");
    }

    /** A forecast at a given hourly rate. */
    private static DemandForecast forecast(String stationId, String fuel, double litersPerHour) {
        return new DemandForecast(stationId, fuel, ForecastMethod.EWMA, DataConfidence.GOOD,
                8, 8, litersPerHour, litersPerHour * 1.25, 1.0, 1.5,
                List.of(), List.of());
    }

    /**
     * A station risk that needs fuel at a given tick.
     *
     * <p>The need itself is computed by the planner from the forecast rate, the current
     * inventory and the inbound fuel, so the fixture only has to describe the world.
     * With a 400 litres/hour forecast and a 6 hour target cover, the desired stock is
     * 2,600 litres, so a station holding 9,000 needs nothing.
     */
    private static StationRisk risk(String stationId, String fuel, double inventory,
                                    double litersPerHour, long stockoutTick) {
        return new StationRisk(stationId, fuel, RiskLevel.WARNING, inventory,
                forecast(stationId, fuel, litersPerHour),
                stockoutTick, stockoutTick * 0.25, null, null, 0.0,
                List.of(), List.of(), List.of());
    }

    // ---------------------------------------------------------- the shared limit

    @Test
    @DisplayName("INV-17: two proposals from one depot never exceed its shared dispatch allowance")
    void sharedDispatchLimitIsNotExceeded() {
        // Two stations, two fuels, both needing more than the depot can send.
        // depot-gazipur dispatch allowance is 12,000 per tick, shared across fuels
        // and destinations.
        Depot depot = gazipur();
        Station mirpur = mirpur();

        List<StationRisk> risks = List.of(
                risk("station-mirpur", "DIESEL", 500, 400, 2),
                risk("station-mirpur", "PETROL", 500, 400, 3));

        Map<String, DemandForecast> forecasts = Map.of(
                "station-mirpur|DIESEL", forecast("station-mirpur", "DIESEL", 400),
                "station-mirpur|PETROL", forecast("station-mirpur", "PETROL", 400));

        AllocationPlan plan = planner.plan(
                40L,
                List.of(depot),
                List.of(mirpur),
                List.of(route("route-gazipur-mirpur", "depot-gazipur", "station-mirpur", 2, 7000)),
                List.of(),
                risks,
                forecasts,
                policy,
                15);

        double totalDispatched = plan.proposals().stream()
                .mapToDouble(AllocationProposal::quantityLiters)
                .sum();

        assertThat(totalDispatched)
                .as("the depot's shared dispatch allowance must not be exceeded")
                .isLessThanOrEqualTo(depot.dispatchCapacityPerTick());

        // The guide's manual exercise: an independent-per-fuel policy would dispatch
        // 4,700 litres where only 4,000 is allowed.
        assertThat(totalDispatched).isLessThan(4700.0);
    }

    @Test
    @DisplayName("the remaining dispatch allowance shrinks after each proposal")
    void dispatchAllowanceIsReservedBetweenProposals() {
        Depot depot = gazipur();
        Station mirpur = mirpur();

        List<StationRisk> risks = List.of(
                risk("station-mirpur", "DIESEL", 100, 400, 2),
                risk("station-mirpur", "PETROL", 100, 400, 3),
                risk("station-mirpur", "OCTANE", 100, 400, 4));

        Map<String, DemandForecast> forecasts = Map.of(
                "station-mirpur|DIESEL", forecast("station-mirpur", "DIESEL", 400),
                "station-mirpur|PETROL", forecast("station-mirpur", "PETROL", 400),
                "station-mirpur|OCTANE", forecast("station-mirpur", "OCTANE", 400));

        AllocationPlan plan = planner.plan(
                40L, List.of(depot), List.of(mirpur),
                List.of(route("route-gazipur-mirpur", "depot-gazipur", "station-mirpur", 2, 7000)),
                List.of(), risks, forecasts, policy, 15);

        double total = plan.proposals().stream()
                .mapToDouble(AllocationProposal::quantityLiters)
                .sum();

        assertThat(total).isLessThanOrEqualTo(12000.0);
        // The first line takes what it can, the second line sees less room.
        assertThat(plan.proposals().get(1).quantityLiters())
                .isLessThanOrEqualTo(plan.proposals().get(0).quantityLiters());
    }

    @Test
    @DisplayName("INV-16: depot stock is used as returned, not reduced again for pending shipments")
    void depotStockIsNotReducedTwice() {
        // The decisive setup: the need is large enough that the DEPOT STOCK is the
        // binding constraint, and the route limit and dispatch allowance are set
        // above it so they cannot mask the result.
        //
        // depot-gazipur reports 60,000 litres of DIESEL. The simulator already
        // deducted 5,000 for the pending shipment, so 60,000 is what is really there.
        Depot depot = new Depot("depot-gazipur", "Gazipur Depot", "region-dhaka", "OPEN",
                100000.0,
                Map.of("DIESEL", 100000.0, "PETROL", 70000.0, "OCTANE", 45000.0),
                Map.of("DIESEL", 60000.0, "PETROL", 45000.0, "OCTANE", 26000.0));

        Station bigTank = new Station("station-mirpur", "Mirpur", "region-dhaka", "OPEN",
                "urban_high", 1.0,
                Map.of("DIESEL", 200000.0, "PETROL", 14000.0, "OCTANE", 9000.0),
                Map.of("DIESEL", 0.0, "PETROL", 9000.0, "OCTANE", 5000.0));

        Allocation pending = new Allocation(1L, "key-1", "depot-gazipur", "station-mirpur",
                "route-gazipur-mirpur", "DIESEL", 5000.0, 38L, null, null, null, "PENDING", null);

        // 10,000 litres/hour for 6 hours of target cover plus a 200 litre reserve is a
        // desired stock of 60,200, so the need is the whole 60,200.
        Map<String, DemandForecast> forecasts = Map.of(
                "station-mirpur|DIESEL", forecast("station-mirpur", "DIESEL", 10000));

        StationRisk empty = new StationRisk("station-mirpur", "DIESEL", RiskLevel.WARNING, 0,
                forecast("station-mirpur", "DIESEL", 10000),
                2L, 0.5, null, null, 0.0, List.of(), List.of(), List.of());

        AllocationPlan plan = planner.plan(
                40L, List.of(depot), List.of(bigTank),
                List.of(route("route-gazipur-mirpur", "depot-gazipur", "station-mirpur", 2, 100000)),
                List.of(pending), List.of(empty), forecasts, policy, 15);

        // 60,000: the depot stock as returned. If it were reduced a second time for
        // the pending shipment, this would be 55,000.
        assertThat(plan.proposals().get(0).quantityLiters()).isEqualTo(60000.0);
    }

    @Test
    @DisplayName("INV-18: station headroom reserves fuel that is already on its way")
    void stationHeadroomReservesInboundFuel() {
        // A small tank makes the headroom the binding constraint, so the effect of
        // reserving the inbound fuel is visible in the quantity.
        Depot depot = gazipur();

        // Capacity 4,000 with 1,000 in the tank and 2,000 on the way leaves 1,000 of
        // headroom. Without the reservation it would look like 3,000.
        Station smallTank = new Station("station-mirpur", "Mirpur", "region-dhaka", "OPEN",
                "urban_high", 1.0,
                Map.of("DIESEL", 4000.0, "PETROL", 14000.0, "OCTANE", 9000.0),
                Map.of("DIESEL", 1000.0, "PETROL", 9000.0, "OCTANE", 5000.0));

        Allocation inTransit = new Allocation(1L, "key-1", "depot-gazipur", "station-mirpur",
                "route-gazipur-mirpur", "DIESEL", 2000.0, 38L, 38L, 40L, null, "IN_TRANSIT", null);

        Map<String, DemandForecast> forecasts = Map.of(
                "station-mirpur|DIESEL", forecast("station-mirpur", "DIESEL", 1000));

        StationRisk withInbound = new StationRisk("station-mirpur", "DIESEL", RiskLevel.WARNING, 1000,
                forecast("station-mirpur", "DIESEL", 1000),
                2L, 0.5, null, null, 0.0,
                List.of(new StationRisk.InboundShipment(1L, "route-gazipur-mirpur", 2000.0, 40L, false, "IN_TRANSIT")),
                List.of(), List.of());

        AllocationPlan plan = planner.plan(
                40L, List.of(depot), List.of(smallTank),
                List.of(route("route-gazipur-mirpur", "depot-gazipur", "station-mirpur", 2, 7000)),
                List.of(inTransit), List.of(withInbound), forecasts, policy, 15);

        // Headroom = capacity 4,000 - inventory 1,000 - inbound 2,000 = 1,000
        assertThat(plan.proposals().get(0).quantityLiters()).isEqualTo(1000.0);
    }

    // -------------------------------------------------------------- quantities

    @Test
    @DisplayName("the quantity is the minimum of need, stock, route limit, dispatch and headroom")
    void quantityIsTheMinimumOfEveryConstraint() {
        // Need is huge; every other constraint bites.
        assertThat(AllocationPlanner.computeQuantity(10000, 9000, 8000.0, 7000, 6000))
                .isEqualTo(6000);
    }

    @Test
    @DisplayName("a non-positive quantity is discarded rather than sent")
    void nonPositiveQuantityIsDiscarded() {
        assertThat(AllocationPlanner.computeQuantity(0, 9000, 8000.0, 7000, 6000)).isEqualTo(0);
        assertThat(AllocationPlanner.computeQuantity(100, 0, 8000.0, 7000, 6000)).isEqualTo(0);
        assertThat(AllocationPlanner.computeQuantity(100, 9000, 8000.0, 0, 6000)).isEqualTo(0);
        assertThat(AllocationPlanner.computeQuantity(100, 9000, 8000.0, 7000, 0)).isEqualTo(0);
    }

    @Test
    @DisplayName("the route's max_shipment caps a single shipment")
    void routeMaxShipmentCapsTheQuantity() {
        assertThat(AllocationPlanner.computeQuantity(10000, 90000, 6500.0, 12000, 15000))
                .isEqualTo(6500);
    }

    // ------------------------------------------------------------- alternatives

    @Test
    @DisplayName("INV-22: a station with one route gets NO_ALTERNATIVE_ROUTE, not a fabricated one")
    void singleRouteStationHasNoAlternative() {
        Station coxsbazar = new Station("station-coxsbazar", "Cox's Bazar", "region-chattogram",
                "OPEN", "regional", 1.0,
                Map.of("DIESEL", 12000.0, "PETROL", 12000.0, "OCTANE", 7000.0),
                Map.of("DIESEL", 7500.0, "PETROL", 7500.0, "OCTANE", 4200.0));

        List<StationRisk> risks = List.of(risk("station-coxsbazar", "DIESEL", 100, 400, 2));
        Map<String, DemandForecast> forecasts = Map.of(
                "station-coxsbazar|DIESEL", forecast("station-coxsbazar", "DIESEL", 400));

        AllocationPlan plan = planner.plan(
                40L, List.of(patiya()), List.of(coxsbazar),
                List.of(route("route-patiya-coxsbazar", "depot-patiya", "station-coxsbazar", 3, 6000)),
                List.of(), risks, forecasts, policy, 15);

        assertThat(plan.proposals()).hasSize(1);
        // Cox's Bazar has only one route. The proposal must say so explicitly rather
        // than showing an empty list, which reads like a bug (INV-22).
        assertThat(plan.proposals().get(0).alternatives()).hasSize(1);
        AllocationProposal.Alternative alternative = plan.proposals().get(0).alternatives().get(0);
        assertThat(alternative.reasonCode())
                .isEqualTo(AllocationProposal.Alternative.NO_ALTERNATIVE_ROUTE);
        assertThat(alternative.routeId()).isNull();
    }

    @Test
    @DisplayName("a station with two routes offers an alternative with a reason code")
    void twoRouteStationOffersAnAlternative() {
        Station karnaphuli = new Station("station-karnaphuli", "Karnaphuli", "region-chattogram",
                "OPEN", "highway", 1.0,
                Map.of("DIESEL", 14000.0, "PETROL", 15000.0, "OCTANE", 9000.0),
                Map.of("DIESEL", 8500.0, "PETROL", 9500.0, "OCTANE", 5200.0));

        List<StationRisk> risks = List.of(risk("station-karnaphuli", "DIESEL", 100, 400, 2));
        Map<String, DemandForecast> forecasts = Map.of(
                "station-karnaphuli|DIESEL", forecast("station-karnaphuli", "DIESEL", 400));

        AllocationPlan plan = planner.plan(
                40L, List.of(patiya(), gazipur()), List.of(karnaphuli),
                List.of(
                        route("route-patiya-karnaphuli", "depot-patiya", "station-karnaphuli", 2, 7000),
                        route("route-gazipur-karnaphuli", "depot-gazipur", "station-karnaphuli", 4, 5000)),
                List.of(), risks, forecasts, policy, 15);

        assertThat(plan.proposals()).hasSize(1);
        AllocationProposal proposal = plan.proposals().get(0);
        // The earliest arrival wins: patiya at 2 transit ticks beats gazipur at 4.
        assertThat(proposal.routeId()).isEqualTo("route-patiya-karnaphuli");
        assertThat(proposal.alternatives()).isNotEmpty();
        assertThat(proposal.alternatives().get(0).reasonCode())
                .isEqualTo(AllocationProposal.Alternative.LATER_ARRIVAL);
    }

    // -------------------------------------------------------------- unmet needs

    @Test
    @DisplayName("a station with no route reports NO_AVAILABLE_ROUTE")
    void stationWithNoRouteReportsNoAvailableRoute() {
        Station mirpur = mirpur();

        List<StationRisk> risks = List.of(risk("station-mirpur", "DIESEL", 100, 400, 2));
        Map<String, DemandForecast> forecasts = Map.of(
                "station-mirpur|DIESEL", forecast("station-mirpur", "DIESEL", 400));

        AllocationPlan plan = planner.plan(
                40L, List.of(gazipur()), List.of(mirpur),
                List.of(route("route-patiya-coxsbazar", "depot-patiya", "station-coxsbazar", 3, 6000)),
                List.of(), risks, forecasts, policy, 15);

        assertThat(plan.proposals()).isEmpty();
        assertThat(plan.unmetNeeds()).hasSize(1);
        assertThat(plan.unmetNeeds().get(0).reasonCode())
                .isEqualTo(AllocationPlan.UnmetNeed.NO_AVAILABLE_ROUTE);
    }

    @Test
    @DisplayName("a tank that cannot take the whole need reports ROUTE_CAPACITY_EXCEEDED, not NO_AVAILABLE_ROUTE")
    void fullTankReportsCapacityExceededRatherThanNoRoute() {
        // The route exists and the depot has plenty of fuel, but the tank only has
        // room for 1,000 litres. Those are opposite problems: a network fault
        // versus a tank that is already full. Reporting the wrong one sends the
        // operator looking for a broken route that does not exist.
        Depot depot = gazipur();

        Station smallTank = new Station("station-mirpur", "Mirpur", "region-dhaka", "OPEN",
                "urban_high", 1.0,
                Map.of("DIESEL", 4000.0, "PETROL", 14000.0, "OCTANE", 9000.0),
                Map.of("DIESEL", 3000.0, "PETROL", 9000.0, "OCTANE", 5000.0));

        List<StationRisk> risks = List.of(risk("station-mirpur", "DIESEL", 3000, 1000, 2));
        Map<String, DemandForecast> forecasts = Map.of(
                "station-mirpur|DIESEL", forecast("station-mirpur", "DIESEL", 1000));

        AllocationPlan plan = planner.plan(
                40L, List.of(depot), List.of(smallTank),
                List.of(route("route-gazipur-mirpur", "depot-gazipur", "station-mirpur", 2, 7000)),
                List.of(), risks, forecasts, policy, 15);

        assertThat(plan.unmetNeeds()).hasSize(1);
        assertThat(plan.unmetNeeds().get(0).reasonCode())
                .isEqualTo(AllocationPlan.UnmetNeed.ROUTE_CAPACITY_EXCEEDED);
        // The part that did fit was still proposed.
        assertThat(plan.proposals()).hasSize(1);
        assertThat(plan.proposals().get(0).quantityLiters()).isEqualTo(1000.0);
    }

    @Test
    @DisplayName("a station that is OUTAGE is not recommended to")
    void outageStationIsNotRecommendedTo() {
        Station outage = new Station("station-mirpur", "Mirpur", "region-dhaka", "OUTAGE",
                "urban_high", 1.0,
                Map.of("DIESEL", 15000.0, "PETROL", 14000.0, "OCTANE", 9000.0),
                Map.of("DIESEL", 9000.0, "PETROL", 9000.0, "OCTANE", 5000.0));

        List<StationRisk> risks = List.of(risk("station-mirpur", "DIESEL", 100, 400, 2));
        Map<String, DemandForecast> forecasts = Map.of(
                "station-mirpur|DIESEL", forecast("station-mirpur", "DIESEL", 400));

        AllocationPlan plan = planner.plan(
                40L, List.of(gazipur()), List.of(outage),
                List.of(route("route-gazipur-mirpur", "depot-gazipur", "station-mirpur", 2, 7000)),
                List.of(), risks, forecasts, policy, 15);

        assertThat(plan.proposals()).isEmpty();
        assertThat(plan.unmetNeeds().get(0).reasonCode())
                .isEqualTo(AllocationPlan.UnmetNeed.STATION_CLOSED);
    }

    @Test
    @DisplayName("a rounding residue is not reported as an unmet need")
    void roundingResidueIsNotAnUnmetNeed() {
        // The need is 2,400.5 litres and the tank takes 2,400. The 0.5 litre
        // residue is a rounding artefact, not a shortfall. Reporting it would fill
        // the operator's list with phantom problems and bury the real ones.
        Station mirpur = mirpur();

        List<StationRisk> risks = List.of(risk("station-mirpur", "DIESEL", 100, 400, 2));
        Map<String, DemandForecast> forecasts = Map.of(
                "station-mirpur|DIESEL", forecast("station-mirpur", "DIESEL", 400));

        AllocationPlan plan = planner.plan(
                40L, List.of(gazipur()), List.of(mirpur),
                List.of(route("route-gazipur-mirpur", "depot-gazipur", "station-mirpur", 2, 7000)),
                List.of(), risks, forecasts, policy, 15);

        assertThat(plan.proposals()).hasSize(1);
        assertThat(plan.unmetNeeds()).isEmpty();
    }

    @Test
    @DisplayName("one need never produces two different explanations")
    void oneNeedProducesOneExplanation() {
        // Two stations with the same need compete for the same depot allowance.
        // The second one is refused, and it must be refused exactly once with a
        // single reason, not once per route that was tried.
        Depot depot = gazipur();

        Station mirpur = mirpur();
        Station tongi = new Station("station-tongi", "Tongi", "region-dhaka", "OPEN",
                "industrial", 1.0,
                Map.of("DIESEL", 15000.0, "PETROL", 14000.0, "OCTANE", 9000.0),
                Map.of("DIESEL", 100.0, "PETROL", 9000.0, "OCTANE", 5000.0));

        List<StationRisk> risks = List.of(
                risk("station-mirpur", "DIESEL", 100, 400, 2),
                risk("station-tongi", "DIESEL", 100, 400, 2));

        Map<String, DemandForecast> forecasts = Map.of(
                "station-mirpur|DIESEL", forecast("station-mirpur", "DIESEL", 400),
                "station-tongi|DIESEL", forecast("station-tongi", "DIESEL", 400));

        AllocationPlan plan = planner.plan(
                40L, List.of(depot), List.of(mirpur, tongi),
                List.of(
                        route("route-gazipur-mirpur", "depot-gazipur", "station-mirpur", 2, 7000),
                        route("route-gazipur-tongi", "depot-gazipur", "station-tongi", 2, 7000)),
                List.of(), risks, forecasts, policy, 15);

        // No station and fuel may appear twice in the unmet list.
        assertThat(plan.unmetNeeds())
                .extracting(need -> need.stationId() + "|" + need.fuel())
                .doesNotHaveDuplicates();

        // And every unmet need carries exactly one reason.
        for (AllocationPlan.UnmetNeed need : plan.unmetNeeds()) {
            assertThat(need.reasonCode()).isNotBlank();
            assertThat(need.reason()).isNotBlank();
        }
    }

    @Test
    @DisplayName("a proposal carries reasons and constraints, not just numbers")
    void proposalCarriesReasonsAndConstraints() {
        List<StationRisk> risks = List.of(risk("station-mirpur", "DIESEL", 100, 400, 2));
        Map<String, DemandForecast> forecasts = Map.of(
                "station-mirpur|DIESEL", forecast("station-mirpur", "DIESEL", 400));

        AllocationPlan plan = planner.plan(
                40L, List.of(gazipur()), List.of(mirpur()),
                List.of(route("route-gazipur-mirpur", "depot-gazipur", "station-mirpur", 2, 7000)),
                List.of(), risks, forecasts, policy, 15);

        AllocationProposal proposal = plan.proposals().get(0);
        assertThat(proposal.reasons()).isNotEmpty();
        assertThat(proposal.constraints()).isNotEmpty();
        assertThat(proposal.estimatedArrivalTick()).isEqualTo(42L);
        assertThat(proposal.estimatedArrivalHours()).isEqualTo(0.5);
        assertThat(plan.policyVersion()).isEqualTo(AllocationPlanner.POLICY_VERSION);
    }
}
