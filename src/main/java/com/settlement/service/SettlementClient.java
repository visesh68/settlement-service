package com.settlement.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.settlement.config.AppProperties;
import com.settlement.domain.OutboxItem;
import com.settlement.web.CorrelationIdFilter;
import org.springframework.stereotype.Component;

/**
 * Talks to the settlement provider over real HTTP — including when the provider
 * is this same app's /mock-settlement. Nothing here is a method call in
 * disguise, so connection failures, timeouts and 5xx are exercised for real.
 *
 * <p>The settlement key travels both in the body and as an {@code
 * Idempotency-Key} header, mirroring how real PSPs accept it, and it is the
 * key minted once at capture time — never regenerated. A retry is therefore
 * indistinguishable, to the provider, from the original request.
 */
@Component
public class SettlementClient {

    private final HttpClient http;
    private final ObjectMapper json;
    private final DownstreamEndpoint endpoint;
    private final Duration timeout;

    public SettlementClient(HttpClient http, ObjectMapper json, DownstreamEndpoint endpoint, AppProperties props) {
        this.http = http;
        this.json = json;
        this.endpoint = endpoint;
        this.timeout = props.settlement().requestTimeout();
    }

    /**
     * Never throws for an expected failure. A network error, a timeout and a
     * 500 are all just "not settled yet", and the caller decides between retry
     * and dead-letter.
     */
    public SettlementOutcome settle(OutboxItem item, String correlationId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("settlement_key", item.settlementKey().toString());
        body.put("payment_id", item.paymentId().toString());
        body.put("amount_minor", item.amountMinor());
        body.put("currency", item.currency());

        long startedAt = System.nanoTime();
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint.uri())
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", item.settlementKey().toString())
                    .header(CorrelationIdFilter.HEADER, correlationId)
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            Duration took = elapsed(startedAt);

            if (response.statusCode() / 100 == 2) {
                return SettlementOutcome.success(providerRef(response.body()), response.statusCode(), took);
            }
            return SettlementOutcome.failure(response.statusCode(),
                    "downstream returned HTTP " + response.statusCode(), took);

        } catch (java.net.http.HttpTimeoutException e) {
            // The single most dangerous outcome in a payments flow: we do not
            // know whether the provider processed it. We retry anyway, because
            // the settlement key makes redelivery safe.
            return SettlementOutcome.failure(0, "downstream timed out after " + timeout, elapsed(startedAt));
        } catch (IOException e) {
            return SettlementOutcome.failure(0, "downstream unreachable: " + e.getClass().getSimpleName(),
                    elapsed(startedAt));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SettlementOutcome.failure(0, "interrupted while settling", elapsed(startedAt));
        }
    }

    private String providerRef(String responseBody) {
        try {
            var node = json.readTree(responseBody);
            var ref = node.get("provider_ref");
            return ref == null ? null : ref.asText();
        } catch (Exception e) {
            return null;
        }
    }

    private static Duration elapsed(long startedAtNanos) {
        return Duration.ofNanos(System.nanoTime() - startedAtNanos);
    }

    public record SettlementOutcome(boolean success, String providerRef, int statusCode,
                                    String error, Duration took) {

        static SettlementOutcome success(String providerRef, int statusCode, Duration took) {
            return new SettlementOutcome(true, providerRef, statusCode, null, took);
        }

        static SettlementOutcome failure(int statusCode, String error, Duration took) {
            return new SettlementOutcome(false, null, statusCode, error, took);
        }
    }
}
