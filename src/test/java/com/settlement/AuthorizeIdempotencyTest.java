package com.settlement;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.settlement.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Idempotent authorize, including the case the specification calls out
 * explicitly: same key with a different body must be a 409, not a silent
 * replay of a response that does not match what was asked for.
 */
class AuthorizeIdempotencyTest extends IntegrationTest {

    @Test
    @DisplayName("authorize creates an AUTHORIZED payment in integer minor units")
    void authorizeCreatesPayment() {
        ResponseEntity<String> response = post("/payments",
                Map.of("amount", 125_000L, "currency", "INR", "idempotency_key", newKey()));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        var payment = body(response);
        assertThat(payment.get("state").asText()).isEqualTo("AUTHORIZED");
        assertThat(payment.get("amount").asLong()).isEqualTo(125_000L);
        assertThat(payment.get("currency").asText()).isEqualTo("INR");
        assertThat(payment.get("settlement_state").asText())
                .as("nothing is queued for settlement until capture")
                .isEqualTo("NONE");
        assertThat(count("SELECT count(*) FROM settlement_outbox")).isZero();
    }

    @Test
    @DisplayName("replaying the same key with the same body returns the original payment")
    void sameKeySameBodyReplays() {
        String key = newKey();
        Map<String, Object> request = Map.of("amount", 4_200L, "currency", "INR", "idempotency_key", key);

        ResponseEntity<String> first = post("/payments", request);
        ResponseEntity<String> second = post("/payments", request);

        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(second.getStatusCode().value()).isEqualTo(201);
        assertThat(body(second)).isEqualTo(body(first));
        assertThat(second.getHeaders().getFirst("Idempotent-Replay")).isEqualTo("true");
        assertThat(count("SELECT count(*) FROM payments")).isEqualTo(1);
    }

    @Test
    @DisplayName("the same key with a different body is 409")
    void sameKeyDifferentBodyConflicts() {
        String key = newKey();
        post("/payments", Map.of("amount", 4_200L, "currency", "INR", "idempotency_key", key));

        ResponseEntity<String> differentAmount = post("/payments",
                Map.of("amount", 9_900L, "currency", "INR", "idempotency_key", key));
        ResponseEntity<String> differentCurrency = post("/payments",
                Map.of("amount", 4_200L, "currency", "USD", "idempotency_key", key));

        assertThat(differentAmount.getStatusCode().value()).isEqualTo(409);
        assertThat(body(differentAmount).get("error").asText()).isEqualTo("idempotency_key_reused");
        assertThat(differentCurrency.getStatusCode().value()).isEqualTo(409);
        assertThat(count("SELECT count(*) FROM payments")).isEqualTo(1);
    }

    @Test
    @DisplayName("currency comparison is case-insensitive, so 'inr' is not a different request")
    void currencyIsNormalisedBeforeFingerprinting() {
        String key = newKey();
        ResponseEntity<String> first = post("/payments",
                Map.of("amount", 500L, "currency", "INR", "idempotency_key", key));
        ResponseEntity<String> second = post("/payments",
                Map.of("amount", 500L, "currency", "inr", "idempotency_key", key));

        assertThat(second.getStatusCode().value()).isEqualTo(201);
        assertThat(body(second).get("id")).isEqualTo(body(first).get("id"));
    }

    @Test
    @DisplayName("20 concurrent authorizes with one key create exactly one payment")
    void concurrentAuthorizeWithSameKeyCreatesOnePayment() {
        String key = newKey();
        Map<String, Object> request = Map.of("amount", 777L, "currency", "INR", "idempotency_key", key);

        List<ResponseEntity<String>> responses = inParallel(20, () -> post("/payments", request));

        assertThat(responses).allSatisfy(r -> assertThat(r.getStatusCode().value()).isEqualTo(201));
        assertThat(responses.stream().filter(r -> r.getStatusCode().is5xxServerError()).count()).isZero();

        Set<String> distinctIds = responses.stream()
                .map(r -> body(r).get("id").asText())
                .collect(Collectors.toSet());
        assertThat(distinctIds).as("every caller must be given the same payment").hasSize(1);
        assertThat(count("SELECT count(*) FROM payments")).isEqualTo(1);
    }

    @ParameterizedTest(name = "amount {0} is rejected")
    @CsvSource({"0", "-1", "-125000"})
    @DisplayName("zero and negative amounts are rejected")
    void invalidAmountsAreRejected(long amount) {
        ResponseEntity<String> response = post("/payments",
                Map.of("amount", amount, "currency", "INR", "idempotency_key", newKey()));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(count("SELECT count(*) FROM payments")).isZero();
    }

    @ParameterizedTest(name = "currency '{0}' is rejected")
    @CsvSource({"XYZ", "IN", "INRR", "123", "'  '"})
    @DisplayName("invalid currencies are rejected")
    void invalidCurrenciesAreRejected(String currency) {
        ResponseEntity<String> response = post("/payments",
                Map.of("amount", 100L, "currency", currency, "idempotency_key", newKey()));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(count("SELECT count(*) FROM payments")).isZero();
    }

    @Test
    @DisplayName("a missing idempotency key is rejected")
    void missingIdempotencyKeyIsRejected() {
        Map<String, Object> request = new HashMap<>();
        request.put("amount", 100L);
        request.put("currency", "INR");

        assertThat(post("/payments", request).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("a non-integer amount is rejected rather than silently rounded")
    void fractionalAmountIsRejected() {
        ResponseEntity<String> response = post("/payments",
                "{\"amount\": 12.34, \"currency\": \"INR\", \"idempotency_key\": \"" + newKey() + "\"}");

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(count("SELECT count(*) FROM payments")).isZero();
    }

    @Test
    @DisplayName("an unknown payment is 404 and a malformed id is 400")
    void lookupErrors() {
        assertThat(get("/payments/" + java.util.UUID.randomUUID()).getStatusCode().value()).isEqualTo(404);
        assertThat(get("/payments/not-a-uuid").getStatusCode().value()).isEqualTo(400);
    }
}
