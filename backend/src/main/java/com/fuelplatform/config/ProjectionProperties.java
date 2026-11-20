package com.fuelplatform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Risk projection settings.
 *
 * <p>{@code horizonHours} is expressed in <strong>simulated</strong> hours, because the
 * question an operator asks is "how long until this station runs dry" — and that must
 * be answered in the world's clock, not the laptop's.
 */
@ConfigurationProperties(prefix = "app.projection")
public record ProjectionProperties(int horizonHours) {

    public ProjectionProperties {
        if (horizonHours <= 0) {
            horizonHours = 8;
        }
    }
}
