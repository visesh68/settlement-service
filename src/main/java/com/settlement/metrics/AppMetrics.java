package com.settlement.metrics;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import com.settlement.repo.OutboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Business metrics, exposed on /metrics alongside the standard HTTP server
 * metrics (which carry a percentile histogram so p99 is a real quantile rather
 * than something inferred from a mean).
 */
@Component
public class AppMetrics {

    private final Counter paymentsAuthorized;
    private final Counter paymentsCaptured;
    private final Counter captureRaceLost;
    private final Counter idempotentReplayAuthorize;
    private final Counter idempotentReplayCapture;
    private final Counter idempotencyKeyReused;
    private final Counter settlementAttempts;
    private final Counter settlementSuccess;
    private final Counter settlementRetry;
    private final Counter settlementDeadLetter;
    private final Counter leaseReclaimed;
    private final Counter leaseLost;
    private final Timer settlementCall;

    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong inFlight = new AtomicLong();
    private final AtomicLong settled = new AtomicLong();
    private final AtomicLong deadLettered = new AtomicLong();

    private final OutboxRepository outbox;

    public AppMetrics(MeterRegistry registry, OutboxRepository outbox) {
        this.outbox = outbox;

        this.paymentsAuthorized = counter(registry, "payments.authorized", "Payments authorized");
        this.paymentsCaptured = counter(registry, "payments.captured", "Payments captured (each payment at most once)");
        this.captureRaceLost = counter(registry, "payments.capture.race.lost",
                "Capture attempts that lost the race to a concurrent capture");
        this.idempotentReplayAuthorize = Counter.builder("idempotency.replay")
                .description("Requests answered from a stored idempotent response")
                .tag("scope", "authorize").register(registry);
        this.idempotentReplayCapture = Counter.builder("idempotency.replay")
                .description("Requests answered from a stored idempotent response")
                .tag("scope", "capture").register(registry);
        this.idempotencyKeyReused = counter(registry, "idempotency.key.reused",
                "Idempotency keys replayed with a different request body (409)");
        this.settlementAttempts = counter(registry, "settlement.attempts", "Settlement delivery attempts");
        this.settlementSuccess = counter(registry, "settlement.success", "Settlements confirmed by the downstream");
        this.settlementRetry = counter(registry, "settlement.retry", "Settlement attempts rescheduled after failure");
        this.settlementDeadLetter = counter(registry, "settlement.dead.letter",
                "Settlements moved to dead letter after exhausting attempts");
        this.leaseReclaimed = counter(registry, "settlement.lease.reclaimed",
                "Stranded in-flight items reclaimed after lease expiry");
        this.leaseLost = counter(registry, "settlement.lease.lost",
                "Completions discarded because this instance no longer held the lease");
        this.settlementCall = Timer.builder("settlement.call.duration")
                .description("Latency of the downstream settlement call")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(registry);

        Gauge.builder("settlement.outbox.size", pending, AtomicLong::get)
                .description("Outbox rows by status").tag("status", "PENDING").register(registry);
        Gauge.builder("settlement.outbox.size", inFlight, AtomicLong::get)
                .tag("status", "IN_FLIGHT").register(registry);
        Gauge.builder("settlement.outbox.size", settled, AtomicLong::get)
                .tag("status", "SETTLED").register(registry);
        Gauge.builder("settlement.outbox.size", deadLettered, AtomicLong::get)
                .tag("status", "DEAD_LETTER").register(registry);
    }

    private static Counter counter(MeterRegistry registry, String name, String description) {
        return Counter.builder(name).description(description).register(registry);
    }

    public void authorized() {
        paymentsAuthorized.increment();
    }

    public void captured() {
        paymentsCaptured.increment();
    }

    public void captureRaceLost() {
        captureRaceLost.increment();
    }

    public void replayed(String scope) {
        if ("authorize".equals(scope)) {
            idempotentReplayAuthorize.increment();
        } else {
            idempotentReplayCapture.increment();
        }
    }

    public void keyReused() {
        idempotencyKeyReused.increment();
    }

    public void settlementAttempt() {
        settlementAttempts.increment();
    }

    public void settlementSucceeded(Duration took) {
        settlementSuccess.increment();
        settlementCall.record(took);
    }

    public void settlementFailed(Duration took) {
        settlementCall.record(took);
    }

    public void settlementRetried() {
        settlementRetry.increment();
    }

    public void settlementDeadLettered() {
        settlementDeadLetter.increment();
    }

    public void leasesReclaimed(int n) {
        leaseReclaimed.increment(n);
    }

    public void leaseLost() {
        leaseLost.increment();
    }

    /** Outbox depth is a poll, not a counter: it is state, not an event. */
    @Scheduled(fixedDelay = 5000, initialDelay = 2000)
    public void refreshOutboxGauges() {
        try {
            var counts = outbox.countsByStatus();
            pending.set(counts.getOrDefault("PENDING", 0L));
            inFlight.set(counts.getOrDefault("IN_FLIGHT", 0L));
            settled.set(counts.getOrDefault("SETTLED", 0L));
            deadLettered.set(counts.getOrDefault("DEAD_LETTER", 0L));
        } catch (RuntimeException ignored) {
            // A gauge refresh must never take the process down.
        }
    }
}
