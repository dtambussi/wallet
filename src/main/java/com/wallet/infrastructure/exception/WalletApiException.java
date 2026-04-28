package com.wallet.infrastructure.exception;

import org.springframework.http.HttpStatus;

public class WalletApiException extends RuntimeException {

    private final String code;
    private final HttpStatus status;

    public WalletApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
