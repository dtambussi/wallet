package com.wallet.integration.support;

import org.testcontainers.containers.PostgreSQLContainer;

/**
 * A single Testcontainers PostgreSQL instance for the whole test JVM, so every {@code @SpringBootTest} that
 * extends {@link com.wallet.integration.base.PostgresIntegrationTestBase} reuses the same port and we can split
 * adapter tests into multiple classes without starting (or invalidating) a second server.
 */
public final class SharedPostgresContainer {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    public static void ensureStarted() {
        if (!POSTGRES.isRunning()) {
            POSTGRES.start();
        }
    }

    public static PostgreSQLContainer<?> get() {
        return POSTGRES;
    }

    private SharedPostgresContainer() {}
}
