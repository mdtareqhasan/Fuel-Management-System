package com.fuelplatform.allocation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuelplatform.risk.DataConfidence;
import com.fuelplatform.risk.DemandForecast;
import com.fuelplatform.risk.ForecastMethod;
import com.fuelplatform.risk.RiskLevel;
import com.fuelplatform.risk.StationRisk;
import com.fuelplatform.simulator.dto.SimulatorDtos.Allocation;
import com.fuelplatform.simulator.dto.SimulatorDtos.Depot;
import com.fuelplatform.simulator.dto.SimulatorDtos.Route;
import com.fuelplatform.simulator.dto.SimulatorDtos.Station;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Deterministic planner sensitivity comparison for the Day 5 release report. */
class Day5PolicyComparisonTest {

    private static final List<String> FUELS = List.of("DIESEL", "PETROL", "OCTANE");
    private static final AllocationPlanner.Policy BASELINE = new AllocationPlanner.Policy(4, 0);
    private static final AllocationPlanner.Policy CANDIDATE = new AllocationPlanner.Policy(6, 200);
    private final AllocationPlanner planner = new AllocationPlanner();

    private record Scenario(String name, double demandMultiplier, boolean disruptPrimaryRoutes) {}

    @Test
    void comparePoliciesAcrossDeterministicScenariosAndWriteEvidence() throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        List<Scenario> scenarios = List.of(
                new Scenario("normal", 1.0, false),
                new Scenario("demand_spike_1_8x", 1.8, false),
                new Scenario("route_disruption", 1.0, true),
                new Scenario("combined_spike_and_route_disruption", 1.8, true));

        for (Scenario scenario : scenarios) {
            for (long tick : List.of(0L, 4L)) {
                for (var namedPolicy : List.of(
                        Map.entry("baseline_4h_no_reserve", BASELINE),
                        Map.entry("candidate_6h_200l_reserve", CANDIDATE))) {
                    AllocationPlan plan = evaluate(scenario, tick, namedPolicy.getValue());
                    Map<String, Object> result = summarize(
                            scenario, tick, namedPolicy.getKey(), namedPolicy.getValue(), plan);
                    assertThat(result.get("constraint_violations")).isEqualTo(0);
                    rows.add(result);
                }
            }
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("kind", "deterministic planner scenario comparison");
        report.put("source", "production AllocationPlanner with simulator-topology-based deterministic fixtures");
        report.put("limitations", List.of(
                "This compares proposal quantities and physical feasibility, not realized service level or forecast accuracy.",
                "The spike and route changes are deterministic input transformations, not live simulator event runs.",
                "Tick 4 is a stepped inventory fixture with demand consumption applied; it is not a live simulator step.",
                "The constraint count covers each depot's shared per-tick dispatch ceiling; source stock, route, and tank caps are applied by AllocationPlanner."));
        report.put("policies", Map.of(
                "baseline_4h_no_reserve", Map.of("target_cover_hours", 4, "safety_reserve_liters", 0),
                "candidate_6h_200l_reserve", Map.of("target_cover_hours", 6, "safety_reserve_liters", 200)));
        report.put("seed", 12345);
        report.put("tick_minutes", 15);
        report.put("aggregate", aggregate(rows));
        report.put("results", rows);

        Path output = Path.of("..", "docs", "evidence", "day-05", "policy-comparison.json");
        Files.createDirectories(output.getParent());
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
        assertThat(rows).hasSize(16);
    }

    private AllocationPlan evaluate(Scenario scenario, long tick, AllocationPlanner.Policy policy) {
        List<Depot> depots = List.of(
                new Depot("depot-gazipur", "Gazipur Depot", "region-dhaka", "OPEN", 12000.0,
                        Map.of("DIESEL", 90000.0, "PETROL", 70000.0, "OCTANE", 45000.0),
                        Map.of("DIESEL", 60000.0, "PETROL", 45000.0, "OCTANE", 26000.0)),
                new Depot("depot-patiya", "Patiya Depot", "region-chattogram", "OPEN", 11000.0,
                        Map.of("DIESEL", 85000.0, "PETROL", 65000.0, "OCTANE", 40000.0),
                        Map.of("DIESEL", 55000.0, "PETROL", 42000.0, "OCTANE", 24000.0)));

        List<Station> stations = List.of(
                station("station-mirpur", "region-dhaka", "urban_high",
                        Map.of("DIESEL", 15000.0, "PETROL", 14000.0, "OCTANE", 9000.0)),
                station("station-tongi", "region-dhaka", "industrial",
                        Map.of("DIESEL", 18000.0, "PETROL", 9000.0, "OCTANE", 6000.0)),
                station("station-karnaphuli", "region-chattogram", "highway",
                        Map.of("DIESEL", 14000.0, "PETROL", 15000.0, "OCTANE", 9000.0)),
                station("station-coxsbazar", "region-chattogram", "regional",
                        Map.of("DIESEL", 12000.0, "PETROL", 12000.0, "OCTANE", 7000.0)));

        List<Route> routes = new ArrayList<>();
        for (Station station : stations) {
            String primaryDepot = station.regionId().equals("region-dhaka")
                    ? "depot-gazipur" : "depot-patiya";
            String alternativeDepot = primaryDepot.equals("depot-gazipur") ? "depot-patiya" : "depot-gazipur";
            String primaryStatus = scenario.disruptPrimaryRoutes() ? "DISRUPTED" : "AVAILABLE";
            routes.add(new Route("primary-" + station.id(), primaryDepot, station.id(), 2, 7000.0, primaryStatus));
            routes.add(new Route("alternative-" + station.id(), alternativeDepot, station.id(), 4, 5000.0, "AVAILABLE"));
        }

        List<StationRisk> risks = new ArrayList<>();
        Map<String, DemandForecast> forecasts = new LinkedHashMap<>();
        for (Station station : stations) {
            for (String fuel : FUELS) {
                double hourly = rate(station, fuel) * scenario.demandMultiplier();
                double inventory = Math.max(0, 1500.0 - hourly * 0.25 * tick);
                DemandForecast forecast = new DemandForecast(station.id(), fuel,
                        ForecastMethod.PROFILE_ESTIMATE, DataConfidence.LOW_DATA,
                        0, 8, hourly, hourly * 1.25, null, null,
                        List.of("seeded deterministic evaluation fixture"),
                        List.of("scenario replay, not a measured live forecast"));
                forecasts.put(station.id() + "|" + fuel, forecast);
                risks.add(new StationRisk(station.id(), fuel, RiskLevel.WARNING, inventory, forecast,
                        tick + 8, 2.0, null, null, 0.0, List.of(), List.of(), List.of()));
            }
        }

        return planner.plan(tick, depots, stations, routes, List.<Allocation>of(), risks,
                forecasts, policy, 15);
    }

    private static Station station(String id, String region, String profile, Map<String, Double> capacity) {
        Map<String, Double> inventory = new LinkedHashMap<>();
        FUELS.forEach(fuel -> inventory.put(fuel, 1500.0));
        return new Station(id, id, region, "OPEN", profile, 1.0, capacity, inventory);
    }

    private static double rate(Station station, String fuel) {
        String key = station.demandProfile() + "|" + fuel;
        double daily = switch (key) {
            case "urban_high|DIESEL" -> 8500;
            case "urban_high|PETROL" -> 10500;
            case "urban_high|OCTANE" -> 5600;
            case "industrial|DIESEL" -> 14000;
            case "industrial|PETROL" -> 4500;
            case "industrial|OCTANE" -> 2200;
            case "highway|DIESEL" -> 10500;
            case "highway|PETROL" -> 11000;
            case "highway|OCTANE" -> 6200;
            case "regional|DIESEL" -> 7200;
            case "regional|PETROL" -> 7600;
            case "regional|OCTANE" -> 3600;
            default -> throw new IllegalArgumentException("unknown profile/fuel " + key);
        };
        double hourFactor = switch (station.demandProfile()) {
            case "urban_high" -> 0.70;
            case "industrial" -> 0.45;
            case "highway" -> 0.75;
            case "regional" -> 0.65;
            default -> throw new IllegalArgumentException("unknown profile " + station.demandProfile());
        };
        double regionFactor = station.regionId().equals("region-chattogram") ? 1.08 : 1.0;
        return daily / 24.0 * hourFactor * regionFactor;
    }

    private static Map<String, Object> summarize(
            Scenario scenario, long tick, String policyName, AllocationPlanner.Policy policy, AllocationPlan plan) {
        double proposedLiters = plan.proposals().stream().mapToDouble(AllocationProposal::quantityLiters).sum();
        double unmetLiters = plan.unmetNeeds().stream()
                .mapToDouble(need -> Math.max(0, need.needLiters() - need.metLiters())).sum();
        double totalNeed = plan.proposals().stream().mapToDouble(AllocationProposal::needLiters).sum()
                + plan.unmetNeeds().stream().mapToDouble(AllocationPlan.UnmetNeed::needLiters).sum();
        Map<String, Double> sentPerDepot = new LinkedHashMap<>();
        plan.proposals().forEach(line -> sentPerDepot.merge(line.depotId(), line.quantityLiters(), Double::sum));
        int violations = 0;
        if (sentPerDepot.getOrDefault("depot-gazipur", 0.0) > 12000.0) violations++;
        if (sentPerDepot.getOrDefault("depot-patiya", 0.0) > 11000.0) violations++;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("scenario", scenario.name());
        row.put("tick", tick);
        row.put("mode", "PAUSED deterministic replay");
        row.put("policy", policyName);
        row.put("target_cover_hours", policy.targetCoverHours());
        row.put("safety_reserve_liters", policy.safetyReserveLiters());
        row.put("proposal_lines", plan.proposals().size());
        row.put("proposed_liters", round(proposedLiters));
        row.put("estimated_unmet_liters", round(unmetLiters));
        row.put("need_liters", round(totalNeed));
        row.put("unmet_need_items", plan.unmetNeeds().size());
        row.put("constraint_violations", violations);
        row.put("dispatch_by_depot_liters", sentPerDepot);
        return row;
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static Map<String, Object> aggregate(List<Map<String, Object>> rows) {
        Map<String, Object> baseline = totals(rows, "baseline_4h_no_reserve");
        Map<String, Object> candidate = totals(rows, "candidate_6h_200l_reserve");
        double baselineLiters = (double) baseline.get("proposed_liters");
        double candidateLiters = (double) candidate.get("proposed_liters");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("fixture_rows_per_policy", rows.size() / 2);
        result.put("baseline", baseline);
        result.put("candidate", candidate);
        result.put("candidate_proposed_liters_change_percent",
                baselineLiters == 0 ? null : round((candidateLiters - baselineLiters) / baselineLiters * 100.0));
        return result;
    }

    private static Map<String, Object> totals(List<Map<String, Object>> rows, String policyName) {
        double proposalLiters = 0;
        double unmetLiters = 0;
        int lines = 0;
        int violations = 0;
        for (Map<String, Object> row : rows) {
            if (policyName.equals(row.get("policy"))) {
                proposalLiters += (double) row.get("proposed_liters");
                unmetLiters += (double) row.get("estimated_unmet_liters");
                lines += (int) row.get("proposal_lines");
                violations += (int) row.get("constraint_violations");
            }
        }
        return Map.of("proposed_liters", round(proposalLiters), "estimated_unmet_liters", round(unmetLiters),
                "proposal_lines", lines, "constraint_violations", violations);
    }
}
