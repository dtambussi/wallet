package com.wallet.application.result;

import java.util.Optional;
import java.util.UUID;

public sealed interface TransferResult
    permits
        TransferResult.Success,
        TransferResult.UserNotFound,
        TransferResult.InvalidAmount,
        TransferResult.SameAccountTransfer,
        TransferResult.InsufficientFunds,
        TransferResult.IdempotencyKeyConflict {

    default boolean isSuccess() { return false; }
    default Optional<ResultError> toError() { return Optional.empty(); }
    default UUID ledgerEntryId() { throw new IllegalStateException(); }

    record Success(UUID ledgerEntryId) implements TransferResult {
        public boolean isSuccess() { return true; }
    }

    record UserNotFound(String message) implements TransferResult {
        public Optional<ResultError> toError() { return ResultError.of(404, "USER_NOT_FOUND", message); }
    }

    record InvalidAmount(String message) implements TransferResult {
        public Optional<ResultError> toError() { return ResultError.of(400, "VALIDATION_ERROR", message); }
    }

    record SameAccountTransfer(String message) implements TransferResult {
        public Optional<ResultError> toError() { return ResultError.of(400, "VALIDATION_ERROR", message); }
    }

    record InsufficientFunds(String message) implements TransferResult {
        public Optional<ResultError> toError() { return ResultError.of(409, "INSUFFICIENT_FUNDS", message); }
    }

    record IdempotencyKeyConflict(String message) implements TransferResult {
        public Optional<ResultError> toError() { return ResultError.of(409, "IDEMPOTENCY_KEY_CONFLICT", message); }
    }
}
