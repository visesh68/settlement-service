package com.settlement.service;

import com.settlement.service.SettlementDrainer.DrainReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The self-driving tick. Nothing about correctness depends on it — /admin/drain
 * runs the identical code path, and either can run while the other is running —
 * but without it the service would only settle when poked, which is not a
 * service.
 *
 * <p>{@code fixedDelay} (not {@code fixedRate}) means the next tick starts after
 * the previous one finishes, so a slow downstream cannot cause ticks to pile up
 * on top of each other.
 */
@Component
@ConditionalOnProperty(name = "app.drain.enabled", havingValue = "true", matchIfMissing = true)
public class DrainScheduler {

    private static final Logger log = LoggerFactory.getLogger(DrainScheduler.class);

    private final SettlementDrainer drainer;

    public DrainScheduler(SettlementDrainer drainer) {
        this.drainer = drainer;
    }

    @Scheduled(fixedDelayString = "${app.drain.interval:2s}", initialDelayString = "${app.drain.interval:2s}")
    public void tick() {
        try {
            DrainReport report = drainer.drain();
            if (report.didWork()) {
                log.debug("event=drain_tick claimed={} settled={}", report.claimed(), report.settled());
            }
        } catch (RuntimeException e) {
            // A failed tick must never kill the scheduler thread, or the service
            // would go quietly deaf. The next tick simply tries again; the outbox
            // still holds all the work.
            log.error("event=drain_tick_failed msg={}", e.toString(), e);
        }
    }
}
