package com.settlement.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import com.settlement.config.AppConfig.InstanceId;
import com.settlement.config.AppProperties;
import com.settlement.domain.OutboxItem;
import com.settlement.metrics.AppMetrics;
import com.settlement.repo.OutboxRepository;
import com.settlement.service.SettlementClient.SettlementOutcome;
import com.settlement.web.CorrelationIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * Drains the settlement outbox.
 *
 * <p>Correctness rests on four things, none of which depend on this process
 * staying alive:
 *
 * <ol>
 *   <li><b>Claim with {@code FOR UPDATE SKIP LOCKED}.</b> Concurrent drain
 *       passes — two ticks, two admin calls, or two instances — take disjoint
 *       row sets, because each skips what the other has locked. No coordination
 *       service, no distributed lock, no partitioning scheme required.</li>
 *   <li><b>A stable settlement key per payment.</b> Minted once at capture and
 *       never regenerated, so every redelivery is the same request as far as
 *       the provider is concerned, and the provider dedupes it.</li>
 *   <li><b>A lease.</b> A claimed row records who holds it and until when. A
 *       drainer that dies leaves an expired lease, and the reaper hands the
 *       work back. Nothing is lost, nothing is stuck.</li>
 *   <li><b>Lease-checked completion.</b> Every terminal write is conditional on
 *       still owning the lease, so a slow drainer whose lease already expired
 *       cannot stamp over the drainer that took over from it.</li>
 * </ol>
 *
 * <p>What this buys is at-least-once <em>delivery</em> combined with provider
 * dedupe on the settlement key, which is effectively-once <em>settlement</em>.
 * That distinction is deliberate: exactly-once delivery over a network is not
 * achievable, and any design claiming it is really doing this.
 */
@Service
public class SettlementDrainer {

    private static final Logger log = LoggerFactory.getLogger(SettlementDrainer.class);

    private final OutboxRepository outbox;
    private final SettlementClient client;
    private final AppMetrics metrics;
    private final AppProperties.Settlement config;
    private final String owner;

    public SettlementDrainer(OutboxRepository outbox, SettlementClient client,
                             AppMetrics metrics, AppProperties props, InstanceId instanceId) {
        this.outbox = outbox;
        this.client = client;
        this.metrics = metrics;
        this.config = props.settlement();
        this.owner = instanceId.value();
    }

    public record DrainReport(int reclaimed, int claimed, int settled, int retried,
                              int deadLettered, int leaseLost, int errored, long durationMs) {

        public boolean didWork() {
            return reclaimed > 0 || claimed > 0;
        }
    }

    public DrainReport drain() {
        return drain(config.batchSize());
    }

    /**
     * One drain pass. Safe to call concurrently with itself, with the scheduled
     * tick, and with drain passes in other instances.
     */
    public DrainReport drain(int batchSize) {
        long startedAt = System.nanoTime();

        int reclaimed = reclaimStrandedWork();

        List<OutboxItem> claimed = outbox.claimBatch(batchSize, owner, config.leaseDuration());
        if (claimed.isEmpty()) {
            return new DrainReport(reclaimed, 0, 0, 0, 0, 0, 0, millisSince(startedAt));
        }

        AtomicInteger settled = new AtomicInteger();
        AtomicInteger retried = new AtomicInteger();
        AtomicInteger deadLettered = new AtomicInteger();
        AtomicInteger leaseLost = new AtomicInteger();
        AtomicInteger errored = new AtomicInteger();

        // Bounded fan-out: virtual threads make the blocking HTTP call cheap,
        // but the permit count keeps concurrent settlement calls (and therefore
        // borrowed database connections) below the connection pool size.
        Semaphore permits = new Semaphore(Math.max(1, config.concurrency()));
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Void>> futures = claimed.stream()
                    .map(item -> pool.submit((Callable<Void>) () -> {
                        permits.acquire();
                        try {
                            switch (process(item)) {
                                case SETTLED -> settled.incrementAndGet();
                                case RETRY_SCHEDULED -> retried.incrementAndGet();
                                case DEAD_LETTERED -> deadLettered.incrementAndGet();
                                case LEASE_LOST -> leaseLost.incrementAndGet();
                                case BOOKKEEPING_FAILED -> errored.incrementAndGet();
                            }
                        } finally {
                            permits.release();
                        }
                        return null;
                    }))
                    .toList();
            for (Future<Void> future : futures) {
                try {
                    future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    // process() already handles its own failures; anything here
                    // is a defect. The item stays IN_FLIGHT and the reaper will
                    // recover it, so it is never lost.
                    errored.incrementAndGet();
                    log.error("event=drain_task_failed msg={}", e.toString(), e);
                }
            }
        }

        DrainReport report = new DrainReport(reclaimed, claimed.size(), settled.get(), retried.get(),
                deadLettered.get(), leaseLost.get(), errored.get(), millisSince(startedAt));
        log.info("event=drain_completed reclaimed={} claimed={} settled={} retried={} dead_lettered={} "
                        + "lease_lost={} errored={} duration_ms={}",
                report.reclaimed(), report.claimed(), report.settled(), report.retried(),
                report.deadLettered(), report.leaseLost(), report.errored(), report.durationMs());
        return report;
    }

    /**
     * Hand back work whose owner disappeared. This is the entire crash-recovery
     * mechanism: there is no in-memory state to rebuild on startup, because the
     * outbox already holds everything, and an expired lease is indistinguishable
     * from "a drainer died holding this row".
     */
    private int reclaimStrandedWork() {
        List<OutboxItem> reclaimed = outbox.reclaimExpiredLeases();
        if (reclaimed.isEmpty()) {
            return 0;
        }
        metrics.leasesReclaimed(reclaimed.size());
        for (OutboxItem item : reclaimed) {
            withItemContext(item, () -> log.warn(
                    "event=settlement_lease_reclaimed outbox_id={} attempts={} of {} new_status={} "
                            + "reason=owner_lease_expired",
                    item.id(), item.attempts(), item.maxAttempts(), item.status()));
        }
        return reclaimed.size();
    }

    private enum Outcome {
        SETTLED, RETRY_SCHEDULED, DEAD_LETTERED, LEASE_LOST, BOOKKEEPING_FAILED
    }

    private Outcome process(OutboxItem item) {
        String correlationId = item.correlationId() == null
                ? "settlement-" + item.settlementKey()
                : item.correlationId();

        MDC.put(CorrelationIdFilter.MDC_KEY, correlationId);
        MDC.put(CorrelationIdFilter.INSTANCE_MDC_KEY, owner);
        MDC.put("payment_id", item.paymentId().toString());
        MDC.put("settlement_key", item.settlementKey().toString());
        MDC.put("outbox_id", Long.toString(item.id()));
        try {
            metrics.settlementAttempt();
            log.info("event=settlement_attempt attempt={} of {}", item.attempts(), item.maxAttempts());

            SettlementOutcome outcome = client.settle(item, correlationId);

            if (outcome.success()) {
                if (outbox.markSettled(item.id(), owner) == 0) {
                    // Our lease expired mid-call and someone else owns this row
                    // now. The settlement itself is fine — same key, provider
                    // deduped — but the bookkeeping belongs to the new owner.
                    metrics.leaseLost();
                    log.warn("event=settlement_lease_lost outcome=settled_but_not_recorded "
                            + "reason=lease_expired_during_call took_ms={}", outcome.took().toMillis());
                    return Outcome.LEASE_LOST;
                }
                metrics.settlementSucceeded(outcome.took());
                log.info("event=settlement_succeeded provider_ref={} attempts={} took_ms={}",
                        outcome.providerRef(), item.attempts(), outcome.took().toMillis());
                return Outcome.SETTLED;
            }

            metrics.settlementFailed(outcome.took());

            if (item.attemptsExhausted()) {
                if (outbox.markDeadLetter(item.id(), owner, outcome.error()) == 0) {
                    metrics.leaseLost();
                    log.warn("event=settlement_lease_lost outcome=dead_letter_not_recorded");
                    return Outcome.LEASE_LOST;
                }
                metrics.settlementDeadLettered();
                log.error("event=settlement_dead_lettered attempts={} of {} downstream_status={} last_error={}",
                        item.attempts(), item.maxAttempts(), outcome.statusCode(), outcome.error());
                return Outcome.DEAD_LETTERED;
            }

            Duration backoff = backoffFor(item.attempts());
            Instant nextAttemptAt = Instant.now().plus(backoff);
            if (outbox.scheduleRetry(item.id(), owner, nextAttemptAt, outcome.error()) == 0) {
                metrics.leaseLost();
                log.warn("event=settlement_lease_lost outcome=retry_not_scheduled");
                return Outcome.LEASE_LOST;
            }
            metrics.settlementRetried();
            log.warn("event=settlement_retry_scheduled attempt={} of {} downstream_status={} "
                            + "backoff_ms={} next_attempt_at={} error={}",
                    item.attempts(), item.maxAttempts(), outcome.statusCode(),
                    backoff.toMillis(), nextAttemptAt, outcome.error());
            return Outcome.RETRY_SCHEDULED;

        } catch (RuntimeException e) {
            // The settlement call may or may not have landed, and we failed to
            // record the result. Leaving the row IN_FLIGHT is the safe choice:
            // the lease will expire and the work will be redelivered under the
            // same settlement key.
            log.error("event=settlement_bookkeeping_failed msg={} recovery=lease_expiry", e.toString(), e);
            return Outcome.BOOKKEEPING_FAILED;
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
            MDC.remove(CorrelationIdFilter.INSTANCE_MDC_KEY);
            MDC.remove("payment_id");
            MDC.remove("settlement_key");
            MDC.remove("outbox_id");
        }
    }

    /**
     * Exponential backoff with full jitter. The jitter matters more than the
     * exponent here: without it, a batch that fails together retries together,
     * and a downstream that is struggling gets hit by a synchronised wave every
     * round instead of a smooth trickle.
     */
    Duration backoffFor(int attempt) {
        long baseMillis = config.backoffBase().toMillis();
        long capMillis = config.backoffMax().toMillis();
        int exponent = Math.min(Math.max(attempt - 1, 0), 20);
        long ceiling = Math.min(capMillis, baseMillis << exponent);
        if (ceiling <= 0) {
            return Duration.ZERO;
        }
        // Keep a floor so a retry is never truly immediate, but never let the
        // floor exceed the cap (which a small backoff-max would otherwise do).
        long floor = Math.min(baseMillis / 2 + 1, ceiling);
        return Duration.ofMillis(ThreadLocalRandom.current().nextLong(floor, ceiling + 1));
    }

    private void withItemContext(OutboxItem item, Runnable action) {
        MDC.put("payment_id", item.paymentId().toString());
        MDC.put("settlement_key", item.settlementKey().toString());
        MDC.put(CorrelationIdFilter.MDC_KEY,
                item.correlationId() == null ? "settlement-" + item.settlementKey() : item.correlationId());
        try {
            action.run();
        } finally {
            MDC.remove("payment_id");
            MDC.remove("settlement_key");
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }

    private static long millisSince(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }
}
