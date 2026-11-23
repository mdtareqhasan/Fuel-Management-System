package com.fuelplatform.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * One configured HTTP client for every simulator call.
 *
 * <p>Two reasons this is a single shared bean rather than a client per call site:
 *
 * <ul>
 *   <li>Finite timeouts. The JDK client's connect timeout applies to the TCP handshake,
 *       so a simulator that accepts the connection but never answers still hits the
 *       response timeout instead of hanging a request thread forever.</li>
 *   <li>Connection pooling. The JDK client reuses connections, so the refresh loop does
 *       not pay a TCP handshake on every poll.</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties({
        SimulatorProperties.class,
        FreshnessProperties.class,
        ProjectionProperties.class,
        ResilienceProperties.class,
        StreamProperties.class})
public class SimulatorClientConfig {

    @Bean
    public RestClient simulatorRestClient(SimulatorProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.connectTimeoutMs()))
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(properties.responseTimeoutMs()));

        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }
}
