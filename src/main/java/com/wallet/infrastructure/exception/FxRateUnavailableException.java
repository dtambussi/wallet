package com.wallet.infrastructure.exception;

public class FxRateUnavailableException extends RuntimeException {

    public FxRateUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
