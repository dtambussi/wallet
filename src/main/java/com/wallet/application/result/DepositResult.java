package com.wallet.application.result;

import java.util.Optional;
import java.util.UUID;

public sealed interface DepositResult
    permits DepositResult.Success, DepositResult.UserNotFound, DepositResult.InvalidAmount, DepositResult.IdempotencyKeyConflict {

    default boolean isSuccess() { return false; }
    default Optional<ResultError> toError() { return Optional.empty(); }
    default UUID ledgerEntryId() { throw new IllegalStateException(); }

    record Success(UUID ledgerEntryId) implements DepositResult {
        public boolean isSuccess() { return true; }
    }

    record UserNotFound(String message) implements DepositResult {
        public Optional<ResultError> toError() { return ResultError.of(404, "USER_NOT_FOUND", message); }
    }

    record InvalidAmount(String message) implements DepositResult {
        public Optional<ResultError> toError() { return ResultError.of(400, "VALIDATION_ERROR", message); }
    }

    record IdempotencyKeyConflict(String message) implements DepositResult {
        public Optional<ResultError> toError() { return ResultError.of(409, "IDEMPOTENCY_KEY_CONFLICT", message); }
    }
}
