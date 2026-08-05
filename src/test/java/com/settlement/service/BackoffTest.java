package com.settlement.service;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import com.settlement.config.AppConfig.InstanceId;
import com.settlement.config.AppProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for the retry schedule. No database, no HTTP — just the arithmetic,
 * which is worth pinning because a backoff that silently collapses to zero
 * turns a struggling downstream into a hammered one.
 */
class BackoffTest {

    private static SettlementDrainer drainerWith(Duration base, Duration cap) {
        AppProperties props = new AppProperties("test",
                new AppProperties.Settlement("http://localhost/none", Duration.ofSeconds(3), 10,
                        base, cap, Duration.ofSeconds(30), 50, 16),
                new AppProperties.Drain(false, Duration.ofSeconds(2)),
                new AppProperties.MockDownstream(0.4, Duration.ofMillis(100), Duration.ofMillis(500)));
        return new SettlementDrainer(null, null, null, props, new InstanceId("test"));
    }

    @Test
    @DisplayName("backoff grows exponentially and is capped")
    void backoffGrowsAndIsCapped() {
        SettlementDrainer drainer = drainerWith(Duration.ofMillis(250), Duration.ofSeconds(10));

        for (int attempt = 1; attempt <= 20; attempt++) {
            Duration delay = drainer.backoffFor(attempt);
            assertThat(delay).isLessThanOrEqualTo(Duration.ofSeconds(10));
            assertThat(delay).isPositive();
        }

        // The ceiling for attempt 1 is one base interval; for attempt 5 it is
        // sixteen. Sampling the maximum observed delay is a stable way to show
        // growth without asserting on a single jittered draw.
        long maxAtFirstAttempt = maxOver(drainer, 1, 200);
        long maxAtFifthAttempt = maxOver(drainer, 5, 200);
        assertThat(maxAtFifthAttempt).isGreaterThan(maxAtFirstAttempt);
        assertThat(maxAtFirstAttempt).isLessThanOrEqualTo(250);
    }

    @Test
    @DisplayName("jitter actually varies, so a failed batch does not retry in lockstep")
    void backoffIsJittered() {
        SettlementDrainer drainer = drainerWith(Duration.ofMillis(250), Duration.ofSeconds(10));

        Set<Long> observed = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            observed.add(drainer.backoffFor(6).toMillis());
        }
        assertThat(observed)
                .as("without jitter a whole failed batch retries at the same instant, forever")
                .hasSizeGreaterThan(10);
    }

    @Test
    @DisplayName("a cap smaller than the base interval does not blow up")
    void degenerateConfigurationIsSafe() {
        SettlementDrainer drainer = drainerWith(Duration.ofMillis(250), Duration.ofMillis(10));
        assertThat(drainer.backoffFor(1)).isLessThanOrEqualTo(Duration.ofMillis(10));
        assertThat(drainer.backoffFor(9)).isLessThanOrEqualTo(Duration.ofMillis(10));
    }

    private static long maxOver(SettlementDrainer drainer, int attempt, int samples) {
        long max = 0;
        for (int i = 0; i < samples; i++) {
            max = Math.max(max, drainer.backoffFor(attempt).toMillis());
        }
        return max;
    }
}
