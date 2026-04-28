package com.wallet.application.port.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface FxQuoteRepository {

    void insertQuote(
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
    );

    Optional<QuoteRow> findQuoteByIdForUser(UUID quoteId, UUID userId);

    /**
     * Locks an open, non-expired quote for this user (PostgreSQL FOR UPDATE within current transaction).
     */
    Optional<QuoteRow> lockOpenQuoteForUser(UUID quoteId, UUID userId);

    void markQuoteConsumed(UUID quoteId);

    record QuoteRow(
        UUID quoteId,
        UUID userId,
        String sellCurrency,
        String buyCurrency,
        BigDecimal sellAmount,
        BigDecimal buyAmount,
        Instant expiresAt,
        Instant consumedAt,
        Instant pricedAt,
        String pricingSource,
        boolean servedFromStale
    ) {}

}
