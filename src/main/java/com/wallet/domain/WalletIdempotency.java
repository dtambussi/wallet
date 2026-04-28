package com.wallet.domain;

public final class WalletIdempotency {

    public static final String IDEMPOTENCY_KEY_CONFLICT_MESSAGE = "Idempotency-Key was already used with a different request body";

    private WalletIdempotency() {}
}
