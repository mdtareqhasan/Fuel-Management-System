package com.fuelplatform.risk;

import com.fuelplatform.config.ForecastProperties;
import com.fuelplatform.config.RiskProperties;
import com.fuelplatform.config.SimulatorProfileProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the pure decision functions as beans.
 *
 * <p>Each of these classes is a plain object with no Spring dependencies of its own.
 * They are constructed here so a test can build one directly with plain values, which
 * is what keeps the forecasting and allocation logic testable without a running
 * simulator or a Spring context.
 */
@Configuration
public class RiskConfiguration {

    @Bean
    public DemandForecaster demandForecaster(
            ForecastProperties forecastProperties,
            SimulatorProfileProperties profileProperties) {
        return new DemandForecaster(forecastProperties, profileProperties);
    }

    @Bean
    public DemandAnomalyDetector demandAnomalyDetector(
            RiskProperties riskProperties,
            SimulatorProfileProperties profileProperties) {
        return new DemandAnomalyDetector(riskProperties, profileProperties);
    }

    @Bean
    public StationRiskProjector stationRiskProjector(RiskProperties riskProperties) {
        return new StationRiskProjector(riskProperties);
    }

    @Bean
    public DepotRiskProjector depotRiskProjector(RiskProperties riskProperties) {
        return new DepotRiskProjector(riskProperties);
    }
}
