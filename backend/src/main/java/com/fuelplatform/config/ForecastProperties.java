package com.fuelplatform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Forecast policy settings.
 *
 * <p>Every value here is a <strong>proposed starting point</strong>, not a tuned
 * parameter. The guide is explicit that alpha 0.3 and the eight-observation window are
 * starting values, and that day 5 measures whether they beat the naive baseline before
 * anyone claims the EWMA is better.
 *
 * <p>{@code stressMultiplier} produces the conservative policy scenario. It is labelled
 * a stress scenario in the output and is <strong>not</strong> a confidence interval and
 * <strong>not</strong> a stockout probability (invariant INV-23).
 */
@ConfigurationProperties(prefix = "app.forecast")
public record ForecastProperties(
        double ewmaAlpha,
        int windowSize,
        int minimumObservations,
        double stressMultiplier) {

    public ForecastProperties {
        if (ewmaAlpha <= 0 || ewmaAlpha >= 1) {
            ewmaAlpha = 0.3;
        }
        if (windowSize <= 0) {
            windowSize = 8;
        }
        if (minimumObservations <= 0) {
            minimumObservations = 4;
        }
        if (stressMultiplier < 1.0) {
            stressMultiplier = 1.25;
        }
    }
}
