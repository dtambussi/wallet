package com.wallet.integration.base;

import com.wallet.integration.support.SharedPostgresContainer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** One Postgres for the whole test JVM via {@link SharedPostgresContainer}. */
public abstract class PostgresIntegrationTestBase {

    @DynamicPropertySource
    static void commonDockerProperties(DynamicPropertyRegistry registry) {
        SharedPostgresContainer.ensureStarted();
        var postgres = SharedPostgresContainer.get();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("docker.api.version", () -> "1.44");
        // Disable automatic payout worker scheduling during tests to avoid DB-shutdown race noise.
        registry.add("wallet.payout.worker-initial-delay-ms", () -> "3600000");
        registry.add("wallet.payout.worker-interval-ms", () -> "3600000");
    }
}
