package com.wallet.domain.ledger;

import java.util.UUID;

/**
 * Cursor position for transaction pagination.
 *
 * <p>With UUIDv7 entry ids, ordering is time-based by id alone.</p>
 */
public record TransactionCursor(UUID entryId) {}
