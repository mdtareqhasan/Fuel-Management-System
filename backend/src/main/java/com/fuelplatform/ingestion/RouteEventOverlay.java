package com.fuelplatform.ingestion;

import com.fuelplatform.simulator.dto.SimulatorDtos.DomainEvent;
import com.fuelplatform.simulator.dto.SimulatorDtos.Route;

import java.util.ArrayList;
import java.util.List;

/** Applies active simulator route-disruption events to the decision snapshot. */
final class RouteEventOverlay {

    private RouteEventOverlay() {
    }

    static List<Route> apply(List<Route> routes, List<DomainEvent> events) {
        List<String> disrupted = events.stream()
                .filter(event -> "route_disruption".equalsIgnoreCase(event.type()))
                .filter(event -> "ACTIVE".equalsIgnoreCase(event.status()))
                .map(event -> event.parameters() == null ? null : event.parameters().get("route_id"))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();

        if (disrupted.isEmpty()) {
            return routes;
        }
        List<Route> result = new ArrayList<>(routes.size());
        for (Route route : routes) {
            if (disrupted.contains(route.id()) && "AVAILABLE".equalsIgnoreCase(route.status())) {
                result.add(new Route(route.id(), route.sourceDepotId(), route.destinationStationId(),
                        route.transitTicks(), route.maxShipment(), "DISRUPTED"));
            } else {
                result.add(route);
            }
        }
        return List.copyOf(result);
    }
}
