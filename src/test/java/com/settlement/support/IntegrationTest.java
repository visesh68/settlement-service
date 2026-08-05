package com.settlement.support;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * Base class for tests that exercise the service over real HTTP against real
 * PostgreSQL. Nothing is mocked: the drainer makes genuine HTTP calls to the
 * simulated provider, and the provider genuinely sleeps and genuinely fails.
 *
 * <p>The scheduled drain tick is off by default so that tests decide exactly
 * when draining happens; a dedicated test turns it back on to prove the service
 * is self-driving.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        // Tests drive draining explicitly unless they say otherwise.
        "app.drain.enabled=false",
        // Keep retries quick so the suite does not spend its life in backoff.
        "app.settlement.backoff-base=10ms",
        "app.settlement.backoff-max=60ms",
        // A little more headroom than production, so that a test asserting
        // "everything settled" is not a coin flip against a 40% failure rate.
        "app.settlement.max-attempts=12",
        "spring.datasource.hikari.maximum-pool-size=40"
})
public abstract class IntegrationTest {

    /**
     * Only the datasource coordinates are dynamic — they are not known until the
     * embedded server has picked a port. Everything else lives in
     * {@code @TestPropertySource} above, because {@code @DynamicPropertySource}
     * outranks it and would make subclass overrides silently ineffective.
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        TestDatabase.Handle db = TestDatabase.get();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
    }

    @Autowired
    protected TestRestTemplate http;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected ObjectMapper json;

    @LocalServerPort
    protected int port;

    /**
     * Truncating between methods is deliberate: every test asserts on global
     * counts ("exactly one settlement key per captured payment"), and those
     * assertions are only meaningful against a database that holds this test's
     * rows and nothing else.
     */
    @BeforeEach
    void resetDatabase() {
        jdbc.execute("""
                TRUNCATE TABLE idempotency_records, settlement_outbox, mock_settlements, payments
                RESTART IDENTITY CASCADE
                """);
        setFailureRate(0.0);
    }

    // ------------------------------------------------------------------
    // HTTP helpers
    // ------------------------------------------------------------------

    protected URI url(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    protected ResponseEntity<String> post(String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(url(path), HttpMethod.POST, new HttpEntity<>(toJson(body), headers), String.class);
    }

    protected ResponseEntity<String> get(String path) {
        return http.exchange(url(path), HttpMethod.GET, HttpEntity.EMPTY, String.class);
    }

    protected JsonNode body(ResponseEntity<String> response) {
        try {
            return json.readTree(response.getBody() == null ? "null" : response.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("response body was not JSON: " + response.getBody(), e);
        }
    }

    private String toJson(Object body) {
        try {
            return body instanceof String s ? s : json.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------
    // Domain helpers
    // ------------------------------------------------------------------

    protected String newKey() {
        return UUID.randomUUID().toString();
    }

    /** Authorizes a payment and returns its id, failing loudly if it did not work. */
    protected UUID authorize(long amountMinor, String currency) {
        ResponseEntity<String> response = post("/payments",
                Map.of("amount", amountMinor, "currency", currency, "idempotency_key", newKey()));
        if (response.getStatusCode().value() != 201) {
            throw new AssertionError("authorize failed: " + response.getStatusCode() + " " + response.getBody());
        }
        return UUID.fromString(body(response).get("id").asText());
    }

    protected UUID authorize() {
        return authorize(125_000L, "INR");
    }

    protected UUID authorizeAndCapture() {
        UUID id = authorize();
        ResponseEntity<String> response = post("/payments/" + id + "/capture",
                Map.of("idempotency_key", newKey()));
        if (response.getStatusCode().value() != 200) {
            throw new AssertionError("capture failed: " + response.getStatusCode() + " " + response.getBody());
        }
        return id;
    }

    protected void setFailureRate(double rate) {
        post("/admin/mock-settlement/config", Map.of("failure_rate", rate));
    }

    protected JsonNode stats() {
        return body(get("/admin/stats"));
    }

    protected JsonNode drain(int passes) {
        return body(post("/admin/drain?passes=" + passes, null));
    }

    /**
     * Repeatedly drains until nothing is outstanding, or gives up. Mirrors what
     * an operator (or the background tick) would do, and returns whether the
     * outbox reached quiescence.
     */
    protected boolean drainUntilQuiet(int maxRounds) {
        for (int round = 0; round < maxRounds; round++) {
            drain(3);
            if (stats().get("invariants").get("fully_drained").asBoolean()) {
                return true;
            }
            sleep(Duration.ofMillis(40));
        }
        return stats().get("invariants").get("fully_drained").asBoolean();
    }

    // ------------------------------------------------------------------
    // Concurrency helpers
    // ------------------------------------------------------------------

    /**
     * Runs {@code count} copies of {@code task} as simultaneously as the machine
     * allows. The latch matters: without it the tasks start staggered by thread
     * creation and the race under test may simply never happen, which is how
     * concurrency tests end up passing for the wrong reason.
     */
    protected <T> List<T> inParallel(int count, Callable<T> task) {
        CountDownLatch startGun = new CountDownLatch(1);
        CountDownLatch ready = new CountDownLatch(count);
        List<Future<T>> futures = new ArrayList<>(count);

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < count; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    startGun.await(30, TimeUnit.SECONDS);
                    return task.call();
                }));
            }
            if (!ready.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("workers did not reach the start line");
            }
            startGun.countDown();

            List<T> results = new ArrayList<>(count);
            for (Future<T> future : futures) {
                results.add(future.get(120, TimeUnit.SECONDS));
            }
            return results;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    protected static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------
    // Direct database assertions (deliberately bypassing the API)
    // ------------------------------------------------------------------

    protected long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    protected long capturedCount() {
        return count("SELECT count(*) FROM payments WHERE state = 'CAPTURED'");
    }

    protected long outboxCount(UUID paymentId) {
        return count("SELECT count(*) FROM settlement_outbox WHERE payment_id = ?", paymentId);
    }

    protected long settlementCountFor(UUID paymentId) {
        return count("SELECT count(*) FROM mock_settlements WHERE payment_id = ?", paymentId);
    }

    protected long distinctSettlementKeys() {
        return count("SELECT count(*) FROM mock_settlements");
    }

    protected long totalDeliveries() {
        return count("SELECT COALESCE(sum(delivery_count), 0) FROM mock_settlements");
    }

    protected String paymentState(UUID paymentId) {
        return jdbc.queryForObject("SELECT state FROM payments WHERE id = ?", String.class, paymentId);
    }

    protected String settlementStatus(UUID paymentId) {
        return jdbc.queryForObject("SELECT status FROM settlement_outbox WHERE payment_id = ?",
                String.class, paymentId);
    }
}
