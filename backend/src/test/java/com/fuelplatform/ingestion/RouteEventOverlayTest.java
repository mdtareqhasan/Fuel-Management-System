package com.fuelplatform.ingestion;

import com.fuelplatform.simulator.dto.SimulatorDtos.DomainEvent;
import com.fuelplatform.simulator.dto.SimulatorDtos.Route;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RouteEventOverlayTest {

    private static final Route MIRPUR = new Route("route-gazipur-mirpur", "depot-gazipur",
            "station-mirpur", 2, 7_000.0, "AVAILABLE");
    private static final Route TONGI = new Route("route-gazipur-tongi", "depot-gazipur",
            "station-tongi", 2, 6_500.0, "AVAILABLE");

    @Test
    void activeDisruptionMarksOnlyItsRouteUnavailableToPlanning() {
        DomainEvent event = new DomainEvent(1L, "route_disruption", 1L, 8L, "ACTIVE",
                Map.of("route_id", "route-gazipur-mirpur"));

        List<Route> result = RouteEventOverlay.apply(List.of(MIRPUR, TONGI), List.of(event));

        assertThat(result).extracting(Route::status).containsExactly("DISRUPTED", "AVAILABLE");
        assertThat(MIRPUR.status()).isEqualTo("AVAILABLE");
    }

    @Test
    void scheduledOrResolvedEventDoesNotChangeRoutes() {
        DomainEvent scheduled = new DomainEvent(1L, "route_disruption", 1L, 8L, "SCHEDULED",
                Map.of("route_id", "route-gazipur-mirpur"));

        assertThat(RouteEventOverlay.apply(List.of(MIRPUR, TONGI), List.of(scheduled)))
                .containsExactly(MIRPUR, TONGI);
    }
}
