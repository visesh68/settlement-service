package com.settlement.web;

import java.util.LinkedHashMap;
import java.util.Map;

import com.settlement.config.AppConfig.InstanceId;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private final JdbcTemplate jdbc;
    private final InstanceId instanceId;
    private final PrometheusMeterRegistry prometheus;

    public HealthController(JdbcTemplate jdbc, InstanceId instanceId, PrometheusMeterRegistry prometheus) {
        this.jdbc = jdbc;
        this.instanceId = instanceId;
        this.prometheus = prometheus;
    }

    /**
     * Liveness. Deliberately checks nothing external: if the datastore is down,
     * restarting this process will not help, and a liveness probe that fails on
     * a database outage turns a recoverable incident into a crash loop.
     */
    @GetMapping("/healthz")
    public Map<String, Object> healthz() {
        return Map.of("status", "ok", "instance", instanceId.value());
    }

    /**
     * Readiness. Checks the datastore, because without it this instance cannot
     * serve a single useful request and should be taken out of rotation.
     */
    @GetMapping("/readyz")
    public ResponseEntity<Map<String, Object>> readyz() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("instance", instanceId.value());
        try {
            Integer one = jdbc.queryForObject("SELECT 1", Integer.class);
            boolean ok = one != null && one == 1;
            body.put("status", ok ? "ready" : "not_ready");
            body.put("datastore", ok ? "ok" : "unexpected_response");
            return ok ? ResponseEntity.ok(body)
                    : ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        } catch (RuntimeException e) {
            log.warn("event=readiness_failed component=datastore msg={}", e.toString());
            body.put("status", "not_ready");
            body.put("datastore", "unreachable");
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        }
    }

    /**
     * Prometheus exposition at the conventional path. Actuator serves the same
     * data at /actuator/prometheus; this alias is what the spec asks for and
     * what a scrape config will look for by default.
     */
    @GetMapping(value = "/metrics", produces = "text/plain; version=0.0.4; charset=utf-8")
    public ResponseEntity<String> metrics() {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/plain; version=0.0.4; charset=utf-8"))
                .body(prometheus.scrape());
    }
}
