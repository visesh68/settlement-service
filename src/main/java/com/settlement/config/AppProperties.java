package com.settlement.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * All tunables in one place, bound from {@code app.*} and therefore from the
 * environment. See application.yml for the environment variable names.
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @DefaultValue("") String instanceId,
        @DefaultValue Settlement settlement,
        @DefaultValue Drain drain,
        @DefaultValue MockDownstream mockDownstream) {

    public record Settlement(
            @DefaultValue("http://127.0.0.1:8080/mock-settlement") String downstreamUrl,
            @DefaultValue("3s") Duration requestTimeout,
            @DefaultValue("10") int maxAttempts,
            @DefaultValue("250ms") Duration backoffBase,
            @DefaultValue("10s") Duration backoffMax,
            @DefaultValue("30s") Duration leaseDuration,
            @DefaultValue("50") int batchSize,
            @DefaultValue("16") int concurrency) {
    }

    public record Drain(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("2s") Duration interval) {
    }

    public record MockDownstream(
            @DefaultValue("0.4") double failureRate,
            @DefaultValue("100ms") Duration minLatency,
            @DefaultValue("500ms") Duration maxLatency) {
    }
}
