package com.settlement;

import java.util.Map;
import java.util.UUID;

import com.settlement.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/** Health, metrics, correlation ids and the admin page: the things an operator needs. */
class OperabilityTest extends IntegrationTest {

    @Test
    @DisplayName("liveness and readiness are separate, and readiness checks the datastore")
    void healthEndpoints() {
        ResponseEntity<String> live = get("/healthz");
        assertThat(live.getStatusCode().value()).isEqualTo(200);
        assertThat(body(live).get("status").asText()).isEqualTo("ok");

        ResponseEntity<String> ready = get("/readyz");
        assertThat(ready.getStatusCode().value()).isEqualTo(200);
        assertThat(body(ready).get("status").asText()).isEqualTo("ready");
        assertThat(body(ready).get("datastore").asText()).isEqualTo("ok");
    }

    @Test
    @DisplayName("/metrics exposes the business counters and a real request-latency histogram")
    void metricsEndpoint() {
        setFailureRate(0.0);
        UUID paymentId = authorizeAndCapture();
        drainUntilQuiet(20);
        assertThat(settlementStatus(paymentId)).isEqualTo("SETTLED");

        ResponseEntity<String> response = get("/metrics");
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        String scrape = response.getBody();

        assertThat(scrape)
                .contains("payments_authorized_total")
                .contains("payments_captured_total")
                .contains("settlement_attempts_total")
                .contains("settlement_success_total")
                .contains("settlement_retry_total")
                .contains("settlement_dead_letter_total")
                .contains("settlement_outbox_size{")
                .contains("settlement_call_duration_seconds_bucket{")
                // Request latency is exported as histogram buckets rather than
                // pre-computed quantiles, so p99 is derived with
                // histogram_quantile() and can be aggregated correctly across
                // instances — averaging per-instance p99s cannot be.
                .contains("http_server_requests_seconds_bucket{")
                .contains("http_server_requests_seconds_count{")
                .contains("le=\"");

        assertThat(scrape).contains("payments_capture_race_lost_total");
    }

    @Test
    @DisplayName("a caller-supplied correlation id is echoed back and used for the request")
    void correlationIdIsHonoured() {
        String correlationId = "probe-" + UUID.randomUUID();
        var headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        headers.set("X-Correlation-Id", correlationId);

        ResponseEntity<String> response = http.exchange(url("/payments"),
                org.springframework.http.HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(
                        "{\"amount\":100,\"currency\":\"INR\",\"idempotency_key\":\"" + newKey() + "\"}", headers),
                String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isEqualTo(correlationId);

        // It is persisted onto the payment, which is how settlement work done
        // minutes later still logs the correlation id of the request that
        // created it.
        UUID paymentId = UUID.fromString(body(response).get("id").asText());
        assertThat(jdbc.queryForObject("SELECT correlation_id FROM payments WHERE id = ?", String.class, paymentId))
                .isEqualTo(correlationId);
    }

    @Test
    @DisplayName("the capture correlation id is carried onto the settlement job")
    void correlationIdFlowsFromCaptureToSettlement() {
        String correlationId = "capture-" + UUID.randomUUID();
        UUID paymentId = authorize();

        var headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        headers.set("X-Correlation-Id", correlationId);
        http.exchange(url("/payments/" + paymentId + "/capture"),
                org.springframework.http.HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(
                        "{\"idempotency_key\":\"" + newKey() + "\"}", headers), String.class);

        assertThat(jdbc.queryForObject(
                "SELECT correlation_id FROM settlement_outbox WHERE payment_id = ?", String.class, paymentId))
                .as("settlement retries and dead-letters must be traceable to the capture that caused them")
                .isEqualTo(correlationId);
    }

    @Test
    @DisplayName("an unhandled correlation id is generated when the caller does not supply one")
    void correlationIdIsGeneratedWhenAbsent() {
        ResponseEntity<String> response = get("/healthz");
        assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isNotBlank();
    }

    @Test
    @DisplayName("the admin page is served")
    void adminPageIsServed() {
        ResponseEntity<String> response = get("/");
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("Settlement Service");
    }

    @Test
    @DisplayName("/admin/stats answers the whole correctness gate in one request")
    void statsSnapshot() {
        setFailureRate(0.0);
        authorizeAndCapture();
        authorize();
        drainUntilQuiet(20);

        var stats = stats();
        assertThat(stats.get("payments").get("CAPTURED").asLong()).isEqualTo(1);
        assertThat(stats.get("payments").get("AUTHORIZED").asLong()).isEqualTo(1);
        assertThat(stats.get("outbox").get("SETTLED").asLong()).isEqualTo(1);
        assertThat(stats.get("downstream").get("distinct_settlement_keys").asLong()).isEqualTo(1);
        assertThat(stats.get("invariants").get("safety_holds").asBoolean()).isTrue();
        assertThat(stats.get("invariants").get("converged").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("the downstream fault rate can be pinned at runtime")
    void faultRateIsConfigurable() {
        assertThat(body(post("/admin/mock-settlement/config", Map.of("failure_rate", 0.75)))
                .get("failure_rate").asDouble()).isEqualTo(0.75);
        assertThat(body(get("/admin/mock-settlement/config")).get("failure_rate").asDouble()).isEqualTo(0.75);
    }
}
