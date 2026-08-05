package com.settlement.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.settlement.domain.OutboxItem;
import com.settlement.service.MockSettlementService;
import com.settlement.service.SettlementDrainer;
import com.settlement.service.SettlementDrainer.DrainReport;
import com.settlement.service.StatsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AdminController {

    private final SettlementDrainer drainer;
    private final StatsService stats;
    private final MockSettlementService mockDownstream;

    public AdminController(SettlementDrainer drainer, StatsService stats, MockSettlementService mockDownstream) {
        this.drainer = drainer;
        this.stats = stats;
        this.mockDownstream = mockDownstream;
    }

    /**
     * Drain the outbox now. The background tick runs the same code path; this
     * exists so a probe can drive it deterministically.
     *
     * <p>Safe to call concurrently with itself and with the tick — that is the
     * point of {@code FOR UPDATE SKIP LOCKED}, and calling it concurrently is a
     * legitimate way to check that no item gets settled twice.
     *
     * @param passes how many sequential passes to run. One pass only touches
     *               items whose backoff has elapsed, so several passes are the
     *               convenient way to push a batch to completion.
     */
    @PostMapping("/admin/drain")
    public Map<String, Object> drain(@RequestParam(name = "batch_size", required = false) Integer batchSize,
                                     @RequestParam(name = "passes", defaultValue = "1") int passes) {
        int effectivePasses = Math.clamp(passes, 1, 50);
        int settled = 0;
        int retried = 0;
        int deadLettered = 0;
        int claimed = 0;
        int reclaimed = 0;
        long durationMs = 0;

        for (int i = 0; i < effectivePasses; i++) {
            DrainReport report = batchSize == null
                    ? drainer.drain()
                    : drainer.drain(Math.clamp(batchSize, 1, 1000));
            settled += report.settled();
            retried += report.retried();
            deadLettered += report.deadLettered();
            claimed += report.claimed();
            reclaimed += report.reclaimed();
            durationMs += report.durationMs();
            if (!report.didWork() && i > 0) {
                break; // nothing due; further passes in this call would be no-ops
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("passes", effectivePasses);
        body.put("reclaimed", reclaimed);
        body.put("claimed", claimed);
        body.put("settled", settled);
        body.put("retried", retried);
        body.put("dead_lettered", deadLettered);
        body.put("duration_ms", durationMs);
        return body;
    }

    /** One request that answers the whole correctness gate. */
    @GetMapping("/admin/stats")
    public Map<String, Object> stats() {
        return stats.snapshot();
    }

    /** Nothing is silently dropped: terminal failures are listed here. */
    @GetMapping("/admin/dead-letters")
    public List<OutboxItem> deadLetters(@RequestParam(name = "limit", defaultValue = "100") int limit) {
        return stats.deadLetters(Math.clamp(limit, 1, 1000));
    }

    /**
     * Pin the downstream's fault rate. Defaults to the configured ~0.4; set 0.0
     * to prove convergence deterministically or 1.0 to force dead-lettering,
     * without redeploying.
     */
    @PostMapping("/admin/mock-settlement/config")
    public Map<String, Object> configureDownstream(@RequestBody Map<String, Object> body) {
        Object rate = body.get("failure_rate");
        if (rate instanceof Number number) {
            mockDownstream.setFailureRate(number.doubleValue());
        }
        return Map.of("failure_rate", mockDownstream.failureRate());
    }

    @GetMapping("/admin/mock-settlement/config")
    public Map<String, Object> downstreamConfig() {
        return Map.of("failure_rate", mockDownstream.failureRate());
    }
}
