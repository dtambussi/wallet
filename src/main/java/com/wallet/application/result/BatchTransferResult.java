package com.wallet.application.result;

import java.util.Optional;
import java.util.UUID;

public sealed interface BatchTransferResult
    permits BatchTransferResult.Success,
            BatchTransferResult.UserNotFound,
            BatchTransferResult.InvalidBatch,
            BatchTransferResult.InsufficientFunds,
            BatchTransferResult.IdempotencyKeyConflict {

    default Optional<ResultError> toError() { return Optional.empty(); }
    default UUID ledgerEntryId() { throw new IllegalStateException(); }

    record Success(UUID ledgerEntryId) implements BatchTransferResult {
        public UUID ledgerEntryId() { return ledgerEntryId; }
    }

    record UserNotFound(String message) implements BatchTransferResult {
        public Optional<ResultError> toError() { return ResultError.of(404, "USER_NOT_FOUND", message); }
    }

    record InvalidBatch(String message) implements BatchTransferResult {
        public Optional<ResultError> toError() { return ResultError.of(400, "VALIDATION_ERROR", message); }
    }

    record InsufficientFunds(String message) implements BatchTransferResult {
        public Optional<ResultError> toError() { return ResultError.of(409, "INSUFFICIENT_FUNDS", message); }
    }

    record IdempotencyKeyConflict(String message) implements BatchTransferResult {
        public Optional<ResultError> toError() { return ResultError.of(409, "IDEMPOTENCY_KEY_CONFLICT", message); }
    }
}
