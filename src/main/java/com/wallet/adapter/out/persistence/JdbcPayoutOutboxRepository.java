package com.wallet.adapter.out.persistence;

import com.wallet.application.port.out.PayoutOutboxRepository;
import com.wallet.infrastructure.id.UuidV7Generator;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPayoutOutboxRepository implements PayoutOutboxRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcPayoutOutboxRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(UUID ledgerEntryId, UUID userId, String currency, BigDecimal amount) {
        jdbc.update(
            """
            INSERT INTO payout_outbox (id, ledger_entry_id, user_id, currency, amount)
            VALUES (:id, :ledgerEntryId, :userId, :currency, :amount)
            """,
            new MapSqlParameterSource()
                .addValue("id", UuidV7Generator.next())
                .addValue("ledgerEntryId", ledgerEntryId)
                .addValue("userId", userId)
                .addValue("currency", currency)
                .addValue("amount", amount)
        );
    }

    @Override
    public Optional<PayoutOutboxRecord> claimNextPending() {
        // FOR UPDATE SKIP LOCKED: concurrent workers each claim their own row without blocking each other.
        // next_attempt_at <= now() enforces backoff: a just-failed record is invisible until its delay elapses.
        List<PayoutOutboxRecord> rows = jdbc.query(
            """
            SELECT id, ledger_entry_id, user_id, currency, amount, attempts
            FROM payout_outbox
            WHERE status = 'PENDING'
              AND next_attempt_at <= now()
            ORDER BY next_attempt_at
            LIMIT 1
            FOR UPDATE SKIP LOCKED
            """,
            new MapSqlParameterSource(),
            (rs, i) -> new PayoutOutboxRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("ledger_entry_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("currency"),
                rs.getBigDecimal("amount"),
                rs.getInt("attempts")
            )
        );
        return rows.stream().findFirst();
    }

    @Override
    public void markSucceeded(UUID id, String providerRef) {
        jdbc.update(
            """
            UPDATE payout_outbox
            SET status = 'SUCCEEDED', provider_ref = :providerRef, last_attempted_at = :now
            WHERE id = :id
            """,
            new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("providerRef", providerRef)
                .addValue("now", Timestamp.from(Instant.now()))
        );
    }

    @Override
    public void markFailed(UUID id) {
        jdbc.update(
            """
            UPDATE payout_outbox
            SET status = 'FAILED', last_attempted_at = :now
            WHERE id = :id
            """,
            new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("now", Timestamp.from(Instant.now()))
        );
    }

    @Override
    public void incrementAttempts(UUID id, java.time.Instant nextAttemptAt) {
        jdbc.update(
            """
            UPDATE payout_outbox
            SET attempts = attempts + 1,
                last_attempted_at = :now,
                next_attempt_at = :nextAttemptAt
            WHERE id = :id
            """,
            new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("now", Timestamp.from(Instant.now()))
                .addValue("nextAttemptAt", Timestamp.from(nextAttemptAt))
        );
    }
}
