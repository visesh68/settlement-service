package com.settlement.support;

import java.io.IOException;
import java.io.UncheckedIOException;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

/**
 * A real PostgreSQL for the test suite.
 *
 * <p>The concurrency guarantees under test are PostgreSQL guarantees —
 * {@code SELECT ... FOR UPDATE}, {@code FOR UPDATE SKIP LOCKED}, unique-index
 * blocking on uncommitted keys, READ COMMITTED snapshot behaviour. An in-memory
 * or embedded-Java database would not reproduce any of them, so testing against
 * one would prove nothing about the properties that matter here.
 *
 * <p>Zonky's embedded PostgreSQL is used rather than Testcontainers so that
 * {@code mvn verify} is green from a clean checkout on a machine with no Docker
 * daemon: it downloads real PostgreSQL binaries and runs them as an ordinary
 * child process. Set {@code TEST_DATABASE_URL} (plus optional
 * {@code TEST_DATABASE_USERNAME} / {@code TEST_DATABASE_PASSWORD}) to run the
 * suite against an external PostgreSQL instead — for example a service
 * container in CI.
 *
 * <p>One instance is started per JVM and shared by every test class; individual
 * tests get isolation by truncating between methods, which is far faster than
 * standing up a server per class.
 */
public final class TestDatabase {

    private static final String ENV_URL = "TEST_DATABASE_URL";
    private static final String ENV_USER = "TEST_DATABASE_USERNAME";
    private static final String ENV_PASSWORD = "TEST_DATABASE_PASSWORD";

    private static volatile Handle handle;

    private TestDatabase() {
    }

    public record Handle(String jdbcUrl, String username, String password) {
    }

    public static synchronized Handle get() {
        if (handle == null) {
            handle = external().orElseGet(TestDatabase::startEmbedded);
        }
        return handle;
    }

    private static java.util.Optional<Handle> external() {
        String url = System.getenv(ENV_URL);
        if (url == null || url.isBlank()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new Handle(
                url,
                System.getenv().getOrDefault(ENV_USER, "postgres"),
                System.getenv().getOrDefault(ENV_PASSWORD, "postgres")));
    }

    private static Handle startEmbedded() {
        try {
            EmbeddedPostgres postgres = EmbeddedPostgres.builder()
                    // Defaults tuned for a test workload, not durability: this
                    // database is thrown away, and fsync dominates the runtime
                    // of a suite that commits constantly.
                    .setServerConfig("fsync", "off")
                    .setServerConfig("synchronous_commit", "off")
                    .setServerConfig("full_page_writes", "off")
                    .setServerConfig("max_connections", "200")
                    .start();

            // Shut the server down with the JVM; individual tests must not have
            // to think about lifecycle.
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    postgres.close();
                } catch (IOException ignored) {
                    // Nothing useful to do while the JVM is exiting.
                }
            }, "embedded-postgres-shutdown"));

            String jdbcUrl = "jdbc:postgresql://127.0.0.1:" + postgres.getPort() + "/postgres";
            return new Handle(jdbcUrl, "postgres", "postgres");
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Could not start the embedded PostgreSQL used by the test suite. "
                            + "Set " + ENV_URL + " to point at an existing PostgreSQL instead.", e);
        }
    }
}
