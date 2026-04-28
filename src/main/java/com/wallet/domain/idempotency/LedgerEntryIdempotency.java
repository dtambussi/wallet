package com.wallet.domain.idempotency;

import java.util.UUID;

/** Existing ledger entry identity and stored fingerprint for idempotency replay/conflict checks. */
public record LedgerEntryIdempotency(UUID ledgerEntryId, String idempotencyFingerprint) {}
