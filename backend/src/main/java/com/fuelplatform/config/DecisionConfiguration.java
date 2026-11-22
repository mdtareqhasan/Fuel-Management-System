package com.fuelplatform.config;

import com.fuelplatform.decision.FreshnessPolicy;
import com.fuelplatform.decision.ProposalFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the decision components that are not Spring stereotypes.
 *
 * <p>{@link ProposalFactory} and {@link FreshnessPolicy} are plain classes on
 * purpose. They hold no state and do no I/O, so they are trivially testable and
 * do not need a container. The container just wires them.
 */
@Configuration
public class DecisionConfiguration {

    /** Builds proposals from the current plan. */
    @Bean
    public ProposalFactory proposalFactory() {
        return new ProposalFactory();
    }

    /** The mode-aware freshness rule from ADR 004. */
    @Bean
    public FreshnessPolicy freshnessPolicy(DecisionProperties properties) {
        return new FreshnessPolicy(properties);
    }
}
