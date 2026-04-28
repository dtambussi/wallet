package com.wallet.domain.audit;

public final class AuditOutcomes {

    private AuditOutcomes() {}

    public static final String SUCCESS = "SUCCESS";
    public static final String IDEMPOTENCY_REPLAY = "IDEMPOTENCY_REPLAY";
    public static final String IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT";
    public static final String USER_NOT_FOUND = "USER_NOT_FOUND";
    public static final String INVALID_AMOUNT = "INVALID_AMOUNT";
    public static final String INSUFFICIENT_FUNDS = "INSUFFICIENT_FUNDS";
    public static final String SAME_ACCOUNT_TRANSFER = "SAME_ACCOUNT_TRANSFER";
    public static final String QUOTE_NOT_FOUND = "QUOTE_NOT_FOUND";
    public static final String QUOTE_USED = "QUOTE_USED";
    public static final String QUOTE_EXPIRED = "QUOTE_EXPIRED";
    public static final String QUOTE_UNAVAILABLE = "QUOTE_UNAVAILABLE";
    public static final String INVALID_SELL_AMOUNT = "INVALID_SELL_AMOUNT";
    public static final String NOTHING_TO_SETTLE = "NOTHING_TO_SETTLE";
    public static final String INVALID_BATCH = "INVALID_BATCH";
    public static final String PAYOUT_DISPATCHED = "PAYOUT_DISPATCHED";
    public static final String PAYOUT_RETRYING = "PAYOUT_RETRYING";
    public static final String PAYOUT_REVERSED = "PAYOUT_REVERSED";
}
