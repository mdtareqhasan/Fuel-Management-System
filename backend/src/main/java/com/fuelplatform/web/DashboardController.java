package com.fuelplatform.web;

import com.fuelplatform.domain.WorldSnapshot;
import com.fuelplatform.ingestion.SnapshotIngestionService;
import com.fuelplatform.simulator.dto.SimulatorDtos.Allocation;
import com.fuelplatform.simulator.dto.SimulatorDtos.Depot;
import com.fuelplatform.simulator.dto.SimulatorDtos.DomainEvent;
import com.fuelplatform.simulator.dto.SimulatorDtos.Region;
import com.fuelplatform.simulator.dto.SimulatorDtos.Route;
import com.fuelplatform.simulator.dto.SimulatorDtos.Station;
import com.fuelplatform.simulator.dto.SimulatorDtos.SupplyArrival;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * The operator's read view of the world.
 *
 * <p>Thin by design. A controller translates an HTTP request into a use case and
 * shapes the response; it does not parse simulator JSON and it never allocates fuel.
 * Business rules live in services, and the pure logic lives in the risk and allocation
 * packages where it can be tested without a server.
 */
@RestController
@RequestMapping("/api")
public class DashboardController {

    private final SnapshotIngestionService ingestionService;

    public DashboardController(SnapshotIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    /**
     * The latest validated view of the world.
     *
     * <p>Returns 503 with a reason when nothing has been read yet, rather than an
     * empty success. An empty array and "the simulator is unreachable" are different
     * situations and the operator needs to be able to tell them apart.
     */
    @GetMapping("/dashboard")
    public ResponseEntity<DashboardResponse> dashboard() {
        WorldSnapshot snapshot = ingestionService.currentSnapshot();
        if (snapshot == null) {
            return ResponseEntity.status(503).body(DashboardResponse.unavailable(
                    "no valid snapshot yet: the simulator has not been read successfully"));
        }

        return ResponseEntity.ok(new DashboardResponse(
                SnapshotMetadata.from(snapshot),
                snapshot.mode().name(),
                snapshot.tickMinutes(),
                snapshot.regions(),
                snapshot.depots(),
                snapshot.stations(),
                snapshot.routes(),
                snapshot.supplyArrivals(),
                snapshot.events(),
                snapshot.allocations(),
                ingestionService.currentAgeSeconds()));
    }

    /**
     * @param metadata        which snapshot, which ticks, and whether it may back a decision
     * @param simulationMode  PAUSED or RUNNING
     * @param tickMinutes     simulated minutes per tick, read from the simulator
     * @param dataAgeSeconds  wall-clock age of the view
     */
    public record DashboardResponse(
            SnapshotMetadata metadata,
            String simulationMode,
            int tickMinutes,
            List<Region> regions,
            List<Depot> depots,
            List<Station> stations,
            List<Route> routes,
            List<SupplyArrival> supplyArrivals,
            List<DomainEvent> events,
            List<Allocation> allocations,
            double dataAgeSeconds) {

        static DashboardResponse unavailable(String reason) {
            return new DashboardResponse(
                    SnapshotMetadata.empty(reason), "UNKNOWN", 0,
                    List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), -1);
        }
    }
}
