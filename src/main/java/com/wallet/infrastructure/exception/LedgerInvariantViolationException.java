package com.wallet.infrastructure.exception;

/** Raised when internal ledger composition rules are violated by server code. */
public class LedgerInvariantViolationException extends RuntimeException {
    public LedgerInvariantViolationException(String message) {
        super(message);
    }
}
