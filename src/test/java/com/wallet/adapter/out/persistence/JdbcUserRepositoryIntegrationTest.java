package com.wallet.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wallet.integration.base.PostgresIntegrationTestBase;
import com.wallet.integration.support.WalletDatabaseCleaner;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@DisplayName("JdbcUserRepository (Postgres)")
class JdbcUserRepositoryIntegrationTest extends PostgresIntegrationTestBase {

    @Autowired
    private JdbcUserRepository jdbcUserRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        WalletDatabaseCleaner.truncateMutableTables(jdbcTemplate);
    }

    @Test
    @DisplayName("Insert and existence round-trip")
    void insertAndExistsRoundTrip() {
        UUID userId = UUID.randomUUID();

        assertThat(jdbcUserRepository.existsById(userId)).isFalse();

        jdbcUserRepository.insertUser(userId);

        assertThat(jdbcUserRepository.existsById(userId)).isTrue();
    }

    @Test
    @DisplayName("Duplicate insert surfaces duplicate key")
    void duplicateInsertFails() {
        UUID userId = UUID.randomUUID();
        jdbcUserRepository.insertUser(userId);

        assertThatThrownBy(() -> jdbcUserRepository.insertUser(userId)).isInstanceOf(DuplicateKeyException.class);
    }
}
