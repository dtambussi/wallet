package com.wallet.application.result;

import java.util.Optional;

public record ResultError(int httpStatus, String errorCode, String message) {

    public static Optional<ResultError> of(int httpStatus, String errorCode, String message) {
        return Optional.of(new ResultError(httpStatus, errorCode, message));
    }
}
