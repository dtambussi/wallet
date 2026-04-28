package com.wallet.application.port.out;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

public interface PayoutOutboxRepository {

    /**
     * Inserts a new PENDING outbox record in the caller's active transaction.
     * Must be called atomically with the corresponding {@code postLedgerEntry} to guarantee
     * the debit and the dispatch intent are committed together or not at all.
     */
    void insert(UUID ledgerEntryId, UUID userId, String currency, BigDecimal amount);

    /**
     * Atomically claims the oldest PENDING record by locking it with
     * {@code SELECT … FOR UPDATE SKIP LOCKED}. Returns empty when the queue is drained.
     * Must be called within an active transaction so the lock is held until commit.
     */
    Optional<PayoutOutboxRecord> claimNextPending();

    void markSucceeded(UUID id, String providerRef);

    void markFailed(UUID id);

    void incrementAttempts(UUID id, java.time.Instant nextAttemptAt);

    record PayoutOutboxRecord(UUID id, UUID ledgerEntryId, UUID userId, String currency, BigDecimal amount, int attempts) {}
}
