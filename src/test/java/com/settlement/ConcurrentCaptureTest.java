package com.settlement;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.settlement.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Correctness gate 1: idempotent capture under concurrency.
 *
 * <p>"Captured exactly once, zero 500s" has two readings depending on whether
 * the concurrent retries share an idempotency key, and both are exercised here,
 * because a design can satisfy one and fail the other:
 *
 * <ul>
 *   <li><b>Same key</b> — a client retrying one logical request. Every caller
 *       must get the same successful response, and the payment must be captured
 *       once.</li>
 *   <li><b>Distinct keys</b> — genuinely separate capture attempts racing.
 *       Exactly one may win; the rest must be rejected with 409, not silently
 *       succeed and not blow up with a 500.</li>
 * </ul>
 */
class ConcurrentCaptureTest extends IntegrationTest {

    private static final int CONCURRENCY = 20;

    @Test
    @DisplayName("20 concurrent captures with the SAME key: one capture, twenty 200s, zero 500s")
    void concurrentCapturesWithSameKeyAllReplayTheSameResult() {
        UUID paymentId = authorize();
        String sharedKey = newKey();

        List<ResponseEntity<String>> responses = inParallel(CONCURRENCY,
                () -> post("/payments/" + paymentId + "/capture", Map.of("idempotency_key", sharedKey)));

        assertThat(responses).allSatisfy(response ->
                assertThat(response.getStatusCode().value())
                        .as("every retry of one logical capture must succeed")
                        .isEqualTo(200));

        assertThat(responses).noneSatisfy(response ->
                assertThat(response.getStatusCode().is5xxServerError()).isTrue());

        // Every caller saw the same payment in the same state.
        Set<String> distinctBodies = responses.stream()
                .map(r -> body(r).toString())
                .collect(Collectors.toSet());
        assertThat(distinctBodies)
                .as("a replayed idempotent response must be identical to the original")
                .hasSize(1);

        assertThat(paymentState(paymentId)).isEqualTo("CAPTURED");
        assertThat(capturedCount()).isEqualTo(1);
        assertThat(outboxCount(paymentId))
                .as("exactly one settlement job may exist for a captured payment")
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM idempotency_records WHERE scope = 'capture'")).isEqualTo(1);
    }

    @Test
    @DisplayName("20 concurrent captures with DISTINCT keys: exactly one wins, nineteen 409s, zero 500s")
    void concurrentCapturesWithDistinctKeysProduceExactlyOneWinner() {
        UUID paymentId = authorize();

        List<ResponseEntity<String>> responses = inParallel(CONCURRENCY,
                () -> post("/payments/" + paymentId + "/capture", Map.of("idempotency_key", newKey())));

        long succeeded = responses.stream().filter(r -> r.getStatusCode().value() == 200).count();
        long conflicted = responses.stream().filter(r -> r.getStatusCode().value() == 409).count();
        long serverErrors = responses.stream().filter(r -> r.getStatusCode().is5xxServerError()).count();

        assertThat(succeeded).as("exactly one capture may win the race").isEqualTo(1);
        assertThat(conflicted).as("every loser must be told it lost").isEqualTo(CONCURRENCY - 1);
        assertThat(serverErrors).as("losing a race is an expected outcome, not an error").isZero();

        assertThat(capturedCount()).isEqualTo(1);
        assertThat(outboxCount(paymentId)).isEqualTo(1);
    }

    @Test
    @DisplayName("captures of many different payments in parallel do not interfere")
    void concurrentCapturesAcrossDifferentPaymentsAllSucceed() {
        List<UUID> paymentIds = inParallel(15, this::authorize);

        List<ResponseEntity<String>> responses = inParallel(paymentIds.size(), new java.util.concurrent.Callable<>() {
            private final java.util.concurrent.atomic.AtomicInteger index = new java.util.concurrent.atomic.AtomicInteger();

            @Override
            public ResponseEntity<String> call() {
                UUID id = paymentIds.get(index.getAndIncrement());
                return post("/payments/" + id + "/capture", Map.of("idempotency_key", newKey()));
            }
        });

        assertThat(responses).allSatisfy(r -> assertThat(r.getStatusCode().value()).isEqualTo(200));
        assertThat(capturedCount()).isEqualTo(paymentIds.size());
        assertThat(count("SELECT count(*) FROM settlement_outbox")).isEqualTo(paymentIds.size());
    }

    @Test
    @DisplayName("an already-captured payment cannot be re-captured")
    void recaptureIsRejected() {
        UUID paymentId = authorizeAndCapture();

        ResponseEntity<String> response = post("/payments/" + paymentId + "/capture",
                Map.of("idempotency_key", newKey()));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(body(response).get("error").asText()).isEqualTo("already_captured");
        assertThat(outboxCount(paymentId)).isEqualTo(1);
    }

    @Test
    @DisplayName("a failed (voided) payment cannot be captured")
    void captureOfFailedPaymentIsRejected() {
        UUID paymentId = authorize();
        assertThat(post("/payments/" + paymentId + "/void", null).getStatusCode().value()).isEqualTo(200);
        assertThat(paymentState(paymentId)).isEqualTo("FAILED");

        ResponseEntity<String> response = post("/payments/" + paymentId + "/capture",
                Map.of("idempotency_key", newKey()));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(body(response).get("error").asText()).isEqualTo("invalid_state_transition");
        assertThat(outboxCount(paymentId)).as("a payment that was never captured has no settlement job").isZero();
    }

    @Test
    @DisplayName("replaying a capture key against a different payment is a 409, not a second capture")
    void captureKeyReuseAcrossPaymentsIsRejected() {
        UUID first = authorize();
        UUID second = authorize();
        String sharedKey = newKey();

        assertThat(post("/payments/" + first + "/capture", Map.of("idempotency_key", sharedKey))
                .getStatusCode().value()).isEqualTo(200);

        ResponseEntity<String> response =
                post("/payments/" + second + "/capture", Map.of("idempotency_key", sharedKey));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(body(response).get("error").asText()).isEqualTo("idempotency_key_reused");
        assertThat(paymentState(second)).isEqualTo("AUTHORIZED");
        assertThat(capturedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("the same key used concurrently against two different payments captures only one")
    void sameKeyRacingAcrossTwoPaymentsCapturesOnlyOne() {
        UUID first = authorize();
        UUID second = authorize();
        String sharedKey = newKey();

        // These two take *different* row locks, so they are not serialized by the
        // payment lock at all - the idempotency table's primary key is what stops
        // them both from capturing.
        List<ResponseEntity<String>> responses = inParallel(2, new java.util.concurrent.Callable<>() {
            private final java.util.concurrent.atomic.AtomicInteger index = new java.util.concurrent.atomic.AtomicInteger();

            @Override
            public ResponseEntity<String> call() {
                UUID id = index.getAndIncrement() == 0 ? first : second;
                return post("/payments/" + id + "/capture", Map.of("idempotency_key", sharedKey));
            }
        });

        assertThat(responses.stream().filter(r -> r.getStatusCode().is5xxServerError()).count()).isZero();
        assertThat(capturedCount()).as("one idempotency key may capture at most one payment").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM settlement_outbox")).isEqualTo(1);
    }

    @Test
    @DisplayName("capturing an unknown payment is 404")
    void captureOfUnknownPaymentIsNotFound() {
        ResponseEntity<String> response = post("/payments/" + UUID.randomUUID() + "/capture",
                Map.of("idempotency_key", newKey()));
        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }
}
