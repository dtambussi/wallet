package com.wallet.application.result;

import java.util.Optional;
import java.util.UUID;

public sealed interface WithdrawResult
    permits
        WithdrawResult.Success,
        WithdrawResult.UserNotFound,
        WithdrawResult.InvalidAmount,
        WithdrawResult.InsufficientFunds,
        WithdrawResult.IdempotencyKeyConflict {

    default boolean isSuccess() { return false; }
    default Optional<ResultError> toError() { return Optional.empty(); }
    default UUID ledgerEntryId() { throw new IllegalStateException(); }

    record Success(UUID ledgerEntryId) implements WithdrawResult {
        public boolean isSuccess() { return true; }
    }

    record UserNotFound(String message) implements WithdrawResult {
        public Optional<ResultError> toError() { return ResultError.of(404, "USER_NOT_FOUND", message); }
    }

    record InvalidAmount(String message) implements WithdrawResult {
        public Optional<ResultError> toError() { return ResultError.of(400, "VALIDATION_ERROR", message); }
    }

    record InsufficientFunds(String message) implements WithdrawResult {
        public Optional<ResultError> toError() { return ResultError.of(409, "INSUFFICIENT_FUNDS", message); }
    }

    record IdempotencyKeyConflict(String message) implements WithdrawResult {
        public Optional<ResultError> toError() { return ResultError.of(409, "IDEMPOTENCY_KEY_CONFLICT", message); }
    }
}
