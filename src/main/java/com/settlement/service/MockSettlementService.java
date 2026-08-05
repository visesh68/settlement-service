package com.settlement.service;

import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import com.settlement.config.AppProperties;
import com.settlement.repo.MockSettlementRepository;
import com.settlement.repo.MockSettlementRepository.MockSettlement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The flaky downstream settlement provider, simulated.
 *
 * <p>This deliberately behaves like a real PSP rather than like a convenient
 * test double:
 *
 * <ul>
 *   <li>it takes 100–500&nbsp;ms, so timeouts and concurrency are real;</li>
 *   <li>it fails about 40% of the time with a 500, so retries are real;</li>
 *   <li>it is <b>idempotent on the settlement key</b> — a key it has already
 *       settled returns the original result and is never settled twice, no
 *       matter how many times it arrives or how concurrently.</li>
 * </ul>
 *
 * <p>Only successes are recorded. A 500 means the money did not move, so a
 * retry is still free to settle it — which is what makes the dice rolls
 * meaningful instead of merely noisy.
 *
 * <p>The failure rate is adjustable at runtime so the correctness gate and the
 * test suite can pin it (0.0 to prove convergence, 1.0 to prove dead-lettering)
 * without redeploying. It starts at the configured default.
 */
@Service
public class MockSettlementService {

    private static final Logger log = LoggerFactory.getLogger(MockSettlementService.class);

    private final MockSettlementRepository repo;
    private final long minLatencyMillis;
    private final long maxLatencyMillis;

    /** Stored as parts-per-million so the value is atomic without locking. */
    private final AtomicLong failureRatePpm;

    public MockSettlementService(MockSettlementRepository repo, AppProperties props) {
        this.repo = repo;
        AppProperties.MockDownstream config = props.mockDownstream();
        this.minLatencyMillis = config.minLatency().toMillis();
        this.maxLatencyMillis = Math.max(config.maxLatency().toMillis(), config.minLatency().toMillis());
        this.failureRatePpm = new AtomicLong(Math.round(clamp(config.failureRate()) * 1_000_000L));
    }

    public record SettlementResult(boolean settled, UUID settlementKey, UUID paymentId,
                                   String providerRef, boolean duplicate, int deliveryCount) {
    }

    /**
     * @return empty when the provider "fails" — the caller turns that into a 500.
     */
    public Optional<SettlementResult> settle(UUID settlementKey, UUID paymentId, long amountMinor, String currency) {
        sleepLikeANetworkCall();

        // Dedupe first: a key this provider has already settled always returns
        // its original result. This is the property our retry strategy depends
        // on, so it must hold even while the provider is failing everything else.
        Optional<MockSettlement> alreadySettled = repo.recordRedelivery(settlementKey);
        if (alreadySettled.isPresent()) {
            MockSettlement existing = alreadySettled.get();
            log.info("event=mock_settlement_deduplicated settlement_key={} delivery_count={} provider_ref={}",
                    settlementKey, existing.deliveryCount(), existing.providerRef());
            return Optional.of(new SettlementResult(true, settlementKey, existing.paymentId(),
                    existing.providerRef(), true, existing.deliveryCount()));
        }

        if (rollFailure()) {
            log.info("event=mock_settlement_failed settlement_key={} reason=injected_fault", settlementKey);
            return Optional.empty();
        }

        String providerRef = newProviderRef();
        Optional<MockSettlement> inserted =
                repo.insertIfAbsent(settlementKey, paymentId, amountMinor, currency, providerRef);

        if (inserted.isPresent()) {
            log.info("event=mock_settlement_recorded settlement_key={} provider_ref={}", settlementKey, providerRef);
            return Optional.of(new SettlementResult(true, settlementKey, paymentId, providerRef, false, 1));
        }

        // Lost an insert race with a concurrent delivery of the same key. The
        // winner's settlement stands; we return it rather than creating a second.
        MockSettlement winner = repo.recordRedelivery(settlementKey)
                .or(() -> repo.find(settlementKey))
                .orElseThrow(() -> new IllegalStateException(
                        "settlement key " + settlementKey + " conflicted but is not present"));
        log.info("event=mock_settlement_deduplicated settlement_key={} delivery_count={} reason=concurrent_insert",
                settlementKey, winner.deliveryCount());
        return Optional.of(new SettlementResult(true, settlementKey, winner.paymentId(),
                winner.providerRef(), true, winner.deliveryCount()));
    }

    public double failureRate() {
        return failureRatePpm.get() / 1_000_000.0;
    }

    public void setFailureRate(double rate) {
        double clamped = clamp(rate);
        failureRatePpm.set(Math.round(clamped * 1_000_000L));
        log.warn("event=mock_downstream_reconfigured failure_rate={}", clamped);
    }

    private boolean rollFailure() {
        long ppm = failureRatePpm.get();
        if (ppm <= 0) {
            return false;
        }
        if (ppm >= 1_000_000L) {
            return true;
        }
        return ThreadLocalRandom.current().nextLong(1_000_000L) < ppm;
    }

    private void sleepLikeANetworkCall() {
        if (maxLatencyMillis <= 0) {
            return;
        }
        long millis = minLatencyMillis >= maxLatencyMillis
                ? minLatencyMillis
                : ThreadLocalRandom.current().nextLong(minLatencyMillis, maxLatencyMillis + 1);
        try {
            // Virtual threads: this parks the carrier-free continuation rather
            // than pinning an OS thread, so hundreds of concurrent settlements
            // cost almost nothing.
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String newProviderRef() {
        byte[] bytes = new byte[6];
        ThreadLocalRandom.current().nextBytes(bytes);
        return "MSTL-" + HexFormat.of().withUpperCase().formatHex(bytes);
    }

    private static double clamp(double rate) {
        return Math.min(1.0, Math.max(0.0, rate));
    }
}
