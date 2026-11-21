package com.fuelplatform.web;

import com.fuelplatform.domain.WorldSnapshot;
import com.fuelplatform.ingestion.SnapshotIngestionService;
import com.fuelplatform.risk.RiskEngine;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The operator's intelligence view: forecasts, risk, anomalies and proposals.
 *
 * <p>Thin, like every controller here. It reads the current snapshot, hands it to the
 * risk engine and shapes the answer. It computes nothing and allocates nothing.
 *
 * <p>The response carries the snapshot identity and tick, so a reader can tell whether
 * the numbers describe the world they are looking at or an older one.
 */
@RestController
@RequestMapping("/api")
public class RiskController {

    private final SnapshotIngestionService ingestionService;
    private final RiskEngine riskEngine;

    public RiskController(SnapshotIngestionService ingestionService, RiskEngine riskEngine) {
        this.ingestionService = ingestionService;
        this.riskEngine = riskEngine;
    }

    /**
     * The current risk assessment.
     *
     * <p>Returns 503 with a reason when no valid snapshot exists yet. An empty risk
     * table and "the simulator is unreachable" are different situations.
     */
    @GetMapping("/risk")
    public ResponseEntity<RiskResponse> risk() {
        WorldSnapshot snapshot = ingestionService.currentSnapshot();
        if (snapshot == null) {
            return ResponseEntity.status(503).body(RiskResponse.unavailable(
                    "no valid snapshot yet: the simulator has not been read successfully"));
        }

        RiskEngine.RiskAssessment assessment = riskEngine.assess(snapshot);

        return ResponseEntity.ok(new RiskResponse(
                SnapshotMetadata.from(snapshot),
                assessment.tick(),
                assessment.simTime(),
                assessment.tickMinutes(),
                assessment.horizonHours(),
                assessment.quality(),
                assessment.detectorStatus(),
                assessment.detectorNotes(),
                assessment.forecasts(),
                assessment.stationRisks(),
                assessment.depotRisks(),
                assessment.anomalyFlags(),
                assessment.proposals(),
                assessment.unmetNeeds(),
                assessment.policyVersion()));
    }

    /**
     * @param forecasts       every station and fuel forecast, keyed by "station|fuel"
     * @param stationRisks    the projected station risk, most urgent first
     * @param depotRisks      the projected depot risk, most urgent first
     * @param anomalyFlags    the advisory anomaly flags
     * @param proposals       the feasible shipment proposals
     * @param unmetNeeds      the needs that could not be met, with the reason
     */
    public record RiskResponse(
            SnapshotMetadata metadata,
            long tick,
            String simTime,
            int tickMinutes,
            int horizonHours,
            com.fuelplatform.domain.DataQuality quality,
            String detectorStatus,
            java.util.List<String> detectorNotes,
            java.util.Map<String, com.fuelplatform.risk.DemandForecast> forecasts,
            java.util.List<com.fuelplatform.risk.StationRisk> stationRisks,
            java.util.List<com.fuelplatform.risk.DepotRisk> depotRisks,
            java.util.List<com.fuelplatform.risk.AnomalyFlag> anomalyFlags,
            java.util.List<com.fuelplatform.allocation.AllocationProposal> proposals,
            java.util.List<com.fuelplatform.allocation.AllocationPlan.UnmetNeed> unmetNeeds,
            String policyVersion) {

        static RiskResponse unavailable(String reason) {
            return new RiskResponse(
                    SnapshotMetadata.empty(reason), 0, null, 0, 0,
                    com.fuelplatform.domain.DataQuality.ineligible(reason),
                    "UNAVAILABLE", java.util.List.of(),
                    java.util.Map.of(), java.util.List.of(), java.util.List.of(),
                    java.util.List.of(), java.util.List.of(), java.util.List.of(), null);
        }
    }
}
