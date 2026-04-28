package com.wallet.application.port.out;

import com.wallet.domain.ledger.LedgerLine;
import com.wallet.domain.ledger.TransactionCursor;
import com.wallet.domain.idempotency.LedgerEntryIdempotency;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.lang.Nullable;

public interface LedgerRepository {

    /**
     * Looks up a prior ledger entry by the user-scoped idempotency key (path user + client key or body fingerprint).
     */
    Optional<LedgerEntryIdempotency> findIdempotencyByScopedKey(String scopedIdempotencyKey);

    /**
     * Persists a balanced multi-line {@code ledger_lines} set under one {@code ledger_entries} row. Amounts are signed:
     * positive credits the user, negative debits. On unique-key insert races, verifies {@code idempotencyFingerprint}
     * matches the stored row. Metadata is for long-tail context; query-critical fields should use typed columns/projections.
     */
    PostLedgerEntryResult postLedgerEntry(
        String scopedIdempotencyKey,
        String entryType,
        List<LedgerLine> lines,
        Map<String, Object> metadata,
        String idempotencyFingerprint,
        String correlationId
    );

    sealed interface PostLedgerEntryResult permits PostLedgerEntryResult.Created, PostLedgerEntryResult.Replayed {
        UUID ledgerEntryId();

        record Created(UUID ledgerEntryId) implements PostLedgerEntryResult {}

        record Replayed(UUID ledgerEntryId) implements PostLedgerEntryResult {}
    }

    /** Merges {@code metadataPatch} into the ledger entry JSONB metadata (shallow merge in SQL). */
    void mergeEntryMetadata(UUID ledgerEntryId, Map<String, Object> metadataPatch);

    /**
     * Ledger history for the user (one parent {@code ledger_entries} row per item, with its
     * {@code ledger_lines} for this user as line items).
     */
    List<LedgerEntryHistoryItem> listTransactionsForUser(UUID userId, int limit, @Nullable TransactionCursor cursor);

    /** All non-zero available balance rows for the user (zeros omitted). */
    Map<String, BigDecimal> loadBalances(UUID userId);

    /** All non-zero pending balance rows for the user (zeros omitted). */
    Map<String, BigDecimal> loadPendingBalances(UUID userId);

    /**
     * Moves every pending_amount > 0 into amount for the given user in one UPDATE.
     * Returns the per-currency amounts moved by that statement (captured by SQL RETURNING).
     */
    Map<String, BigDecimal> settleAllPending(UUID userId);

    record LedgerEntryHistoryItem(
        UUID entryId,
        String entryType,
        String createdAtUtc,
        List<LedgerEntryHistoryLine> lines
    ) {}

    record LedgerEntryHistoryLine(String currency, BigDecimal amount) {}

}
