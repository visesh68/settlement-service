package com.settlement;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import com.settlement.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The settlement name: an optional human-readable label.
 *
 * <p>It is deliberately <em>not</em> an identifier — it is nullable, not unique,
 * and no control flow branches on it. What it does have to do is survive the
 * whole journey (authorize → capture → outbox → downstream provider) without
 * being dropped or mutated, and take part in idempotency the same way every
 * other request field does.
 */
class SettlementNameTest extends IntegrationTest {

    @Test
    @DisplayName("a name travels from authorize all the way to the downstream provider's ledger")
    void nameReachesTheDownstream() {
        String name = "Acme invoice #42";

        ResponseEntity<String> authorized = post("/payments",
                Map.of("name", name, "amount", 125_000L, "currency", "INR", "idempotency_key", newKey()));
        assertThat(authorized.getStatusCode().value()).isEqualTo(201);
        assertThat(body(authorized).get("name").asText()).isEqualTo(name);

        UUID id = UUID.fromString(body(authorized).get("id").asText());
        assertThat(post("/payments/" + id + "/capture", Map.of("idempotency_key", newKey()))
                .getStatusCode().value()).isEqualTo(200);

        assertThat(drainUntilQuiet(20)).as("settlement should converge at a 0.0 failure rate").isTrue();

        assertThat(nameIn("SELECT name FROM payments WHERE id = ?", id)).isEqualTo(name);
        assertThat(nameIn("SELECT name FROM settlement_outbox WHERE payment_id = ?", id))
                .as("the outbox row is a self-contained instruction, so it carries the name by value")
                .isEqualTo(name);
        assertThat(nameIn("SELECT name FROM mock_settlements WHERE payment_id = ?", id))
                .as("the provider records the name it was actually sent")
                .isEqualTo(name);

        assertThat(body(get("/payments/" + id)).get("name").asText()).isEqualTo(name);
    }

    @Test
    @DisplayName("the name is optional and absent stays null rather than becoming a placeholder")
    void nameIsOptional() {
        ResponseEntity<String> response = post("/payments",
                Map.of("amount", 4_200L, "currency", "INR", "idempotency_key", newKey()));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(body(response).get("name").isNull()).isTrue();

        UUID id = UUID.fromString(body(response).get("id").asText());
        assertThat(nameIn("SELECT name FROM payments WHERE id = ?", id)).isNull();
    }

    @Test
    @DisplayName("a blank name is normalized to null, so blank and absent are the same request")
    void blankNameNormalizesToNull() {
        String key = newKey();
        Map<String, Object> blank = new HashMap<>(
                Map.of("name", "   ", "amount", 500L, "currency", "INR", "idempotency_key", key));

        ResponseEntity<String> first = post("/payments", blank);
        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(body(first).get("name").isNull()).isTrue();

        // Same key, name omitted entirely. If blank and absent hashed differently
        // this would be a 409 instead of a replay.
        ResponseEntity<String> omitted = post("/payments",
                Map.of("amount", 500L, "currency", "INR", "idempotency_key", key));

        assertThat(omitted.getStatusCode().value()).isEqualTo(201);
        assertThat(omitted.getHeaders().getFirst("Idempotent-Replay")).isEqualTo("true");
        assertThat(count("SELECT count(*) FROM payments")).isEqualTo(1);
    }

    @Test
    @DisplayName("the same key with a different name is 409, like any other changed field")
    void sameKeyDifferentNameConflicts() {
        String key = newKey();
        post("/payments", Map.of("name", "first label", "amount", 4_200L,
                "currency", "INR", "idempotency_key", key));

        ResponseEntity<String> renamed = post("/payments",
                Map.of("name", "different label", "amount", 4_200L, "currency", "INR", "idempotency_key", key));

        assertThat(renamed.getStatusCode().value()).isEqualTo(409);
        assertThat(body(renamed).get("error").asText()).isEqualTo("idempotency_key_reused");
        assertThat(count("SELECT count(*) FROM payments")).isEqualTo(1);
    }

    @Test
    @DisplayName("a name longer than the column allows is rejected at the edge, not by the database")
    void overlongNameIsRejected() {
        ResponseEntity<String> response = post("/payments",
                Map.of("name", "x".repeat(121), "amount", 100L, "currency", "INR", "idempotency_key", newKey()));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(count("SELECT count(*) FROM payments")).isZero();
    }

    @Test
    @DisplayName("names are not unique — two payments may share one")
    void namesNeedNotBeUnique() {
        String name = "monthly payout";
        for (int i = 0; i < 2; i++) {
            assertThat(post("/payments",
                    Map.of("name", name, "amount", 700L, "currency", "INR", "idempotency_key", newKey()))
                    .getStatusCode().value()).isEqualTo(201);
        }
        assertThat(count("SELECT count(*) FROM payments WHERE name = 'monthly payout'")).isEqualTo(2);
    }

    @Test
    @DisplayName("a nameless request hashes exactly as it did before the name column existed")
    void namelessRequestKeepsItsLegacyFingerprint() {
        String key = newKey();
        post("/payments", Map.of("amount", 4_200L, "currency", "INR", "idempotency_key", key));

        String stored = jdbc.queryForObject("""
                SELECT request_fingerprint FROM idempotency_records
                 WHERE scope = 'authorize' AND idempotency_key = ?
                """, String.class, key);

        // Pins the canonical form for a request that carries no name. If a null
        // name ever starts contributing a trailing separator again, every
        // idempotency record written before this column existed stops replaying
        // and starts answering 409 — a regression that only shows up against a
        // database with pre-upgrade rows in it, i.e. production.
        assertThat(stored)
                .as("pre-upgrade idempotency records must still replay after deploy")
                .isEqualTo(sha256("authorize|4200|INR"));
    }

    private String nameIn(String sql, UUID id) {
        return jdbc.queryForObject(sql, String.class, id);
    }

    private static String sha256(String canonical) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
