package com.wallet.adapter.out.persistence;

import com.wallet.application.port.out.FxQuoteRepository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcFxQuoteRepository implements FxQuoteRepository {

    private final NamedParameterJdbcTemplate jdbc;

    private static final RowMapper<QuoteRow> QUOTE_ROW_MAPPER = (rs, i) -> new QuoteRow(
        rs.getObject("id", UUID.class),
        rs.getObject("user_id", UUID.class),
        rs.getString("sell_currency"),
        rs.getString("buy_currency"),
        rs.getBigDecimal("sell_amount"),
        rs.getBigDecimal("buy_amount"),
        rs.getTimestamp("expires_at").toInstant(),
        rs.getTimestamp("consumed_at") != null ? rs.getTimestamp("consumed_at").toInstant() : null,
        rs.getTimestamp("priced_at").toInstant(),
        rs.getString("pricing_source"),
        rs.getBoolean("served_from_stale")
    );

    public JdbcFxQuoteRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insertQuote(
        UUID quoteId,
        UUID userId,
        String sellCurrency,
        String buyCurrency,
        BigDecimal sellAmount,
        BigDecimal buyAmount,
        Instant expiresAt,
        Instant pricedAt,
        String pricingSource,
        boolean servedFromStale
    ) {
        jdbc.update(
            """
            INSERT INTO fx_quotes (
                id, user_id, sell_currency, buy_currency, sell_amount, buy_amount, expires_at,
                priced_at, pricing_source, served_from_stale
            )
            VALUES (
                :id,
                :userId,
                :sellCurrency,
                :buyCurrency,
                :sellAmount,
                :buyAmount,
                :expiresAt,
                :pricedAt,
                :pricingSource,
                :servedFromStale
            )
            """,
            new MapSqlParameterSource(
                Map.of(
                    "id", quoteId,
                    "userId", userId,
                    "sellCurrency", sellCurrency,
                    "buyCurrency", buyCurrency,
                    "sellAmount", sellAmount,
                    "buyAmount", buyAmount,
                    "expiresAt", Timestamp.from(expiresAt),
                    "pricedAt", Timestamp.from(pricedAt),
                    "pricingSource", pricingSource,
                    "servedFromStale", servedFromStale
                )
            )
        );
    }

    @Override
    public Optional<QuoteRow> findQuoteByIdForUser(UUID quoteId, UUID userId) {
        List<QuoteRow> rowsByIdAndUser = jdbc.query(
            """
            SELECT id, user_id, sell_currency, buy_currency, sell_amount, buy_amount, expires_at, consumed_at,
                   priced_at, pricing_source, served_from_stale
            FROM fx_quotes WHERE id = :quoteId AND user_id = :userId
            """,
            new MapSqlParameterSource(Map.of("quoteId", quoteId, "userId", userId)),
            QUOTE_ROW_MAPPER
        );
        return rowsByIdAndUser.stream().findFirst();
    }

    @Override
    public Optional<QuoteRow> lockOpenQuoteForUser(UUID quoteId, UUID userId) {
        List<QuoteRow> openQuoteRows = jdbc.query(
            """
            SELECT id, user_id, sell_currency, buy_currency, sell_amount, buy_amount, expires_at, consumed_at,
                   priced_at, pricing_source, served_from_stale
            FROM fx_quotes
            WHERE id = :quoteId AND user_id = :userId
              AND consumed_at IS NULL
              AND expires_at > now()
            FOR UPDATE
            """,
            new MapSqlParameterSource(Map.of("quoteId", quoteId, "userId", userId)),
            QUOTE_ROW_MAPPER
        );
        return openQuoteRows.stream().findFirst();
    }

    @Override
    public void markQuoteConsumed(UUID quoteId) {
        jdbc.update(
            "UPDATE fx_quotes SET consumed_at = now() WHERE id = :quoteId",
            new MapSqlParameterSource("quoteId", quoteId)
        );
    }
}
