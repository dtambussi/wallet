package com.wallet.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.wallet.application.port.out.FxQuoteRepository;
import com.wallet.integration.base.PostgresIntegrationTestBase;
import com.wallet.integration.support.WalletDatabaseCleaner;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@DisplayName("JdbcFxQuoteRepository (Postgres)")
class JdbcFxQuoteRepositoryIntegrationTest extends PostgresIntegrationTestBase {

    @Autowired
    private JdbcUserRepository jdbcUserRepository;

    @Autowired
    private JdbcFxQuoteRepository jdbcFxQuoteRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        WalletDatabaseCleaner.truncateMutableTables(jdbcTemplate);
    }

    @Test
    @DisplayName("Persist provenance and load by id for user")
    void insertAndFindRoundTrip() {
        UUID userId = UUID.randomUUID();
        jdbcUserRepository.insertUser(userId);

        UUID quoteId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plusSeconds(600).truncatedTo(ChronoUnit.MICROS);
        Instant pricedAt = Instant.parse("2026-01-15T10:00:00Z");

        jdbcFxQuoteRepository.insertQuote(
            quoteId,
            userId,
            "USD",
            "ARS",
            new BigDecimal("100.000000000000000000"),
            new BigDecimal("950000.000000000000000000"),
            expiresAt,
            pricedAt,
            "MockFx",
            false
        );

        FxQuoteRepository.QuoteRow row =
            jdbcFxQuoteRepository.findQuoteByIdForUser(quoteId, userId).orElseThrow();

        assertThat(row.quoteId()).isEqualTo(quoteId);
        assertThat(row.userId()).isEqualTo(userId);
        assertThat(row.sellCurrency()).isEqualTo("USD");
        assertThat(row.buyCurrency()).isEqualTo("ARS");
        assertThat(row.pricedAt()).isEqualTo(pricedAt);
        assertThat(row.pricingSource()).isEqualTo("MockFx");
        assertThat(row.servedFromStale()).isFalse();
        assertThat(row.expiresAt()).isEqualTo(expiresAt);
    }

    @Test
    @DisplayName("Empty when quote belongs to another user")
    void findReturnsEmptyForWrongUser() {
        UUID ownerId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();
        jdbcUserRepository.insertUser(ownerId);
        jdbcUserRepository.insertUser(otherUserId);

        UUID quoteId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plusSeconds(60);
        jdbcFxQuoteRepository.insertQuote(
            quoteId,
            ownerId,
            "USD",
            "BRL",
            BigDecimal.ONE,
            new BigDecimal("5"),
            expiresAt,
            Instant.now(),
            "MockFx",
            false
        );

        assertThat(jdbcFxQuoteRepository.findQuoteByIdForUser(quoteId, otherUserId)).isEmpty();
    }
}
