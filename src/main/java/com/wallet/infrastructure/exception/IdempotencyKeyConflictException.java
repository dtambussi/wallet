package com.wallet.infrastructure.exception;

import com.wallet.domain.WalletIdempotency;

public class IdempotencyKeyConflictException extends RuntimeException {

    public IdempotencyKeyConflictException() {
        super(WalletIdempotency.IDEMPOTENCY_KEY_CONFLICT_MESSAGE);
    }

    public IdempotencyKeyConflictException(String message) {
        super(message);
    }
}
