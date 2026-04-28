package com.wallet.domain.ledger;

import java.math.BigDecimal;
import java.util.UUID;

public record LedgerLine(UUID userId, String currency, BigDecimal amount, PostingBalanceType postingBalanceType) {

    public enum PostingBalanceType {
        AVAILABLE,
        PENDING
    }

    /** Credit that lands in available balance immediately (deposits, FX buy line, withdrawals). */
    public static LedgerLine available(UUID userId, String currency, BigDecimal amount) {
        return new LedgerLine(userId, currency, amount, PostingBalanceType.AVAILABLE);
    }

    /** Credit that lands in pending_amount and requires settlement before it is spendable.
     *  Use for pending-credit flows such as batch-transfer recipient lines. */
    public static LedgerLine pending(UUID userId, String currency, BigDecimal amount) {
        return new LedgerLine(userId, currency, amount, PostingBalanceType.PENDING);
    }
}
