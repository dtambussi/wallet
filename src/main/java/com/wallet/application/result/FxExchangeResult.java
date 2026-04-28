package com.wallet.application.result;

import java.util.Optional;
import java.util.UUID;

public sealed interface FxExchangeResult
    permits
        FxExchangeResult.Success,
        FxExchangeResult.UserNotFound,
        FxExchangeResult.QuoteNotFound,
        FxExchangeResult.QuoteUsed,
        FxExchangeResult.QuoteExpired,
        FxExchangeResult.QuoteUnavailable,
        FxExchangeResult.InsufficientFunds,
        FxExchangeResult.IdempotencyKeyConflict {

    default boolean isSuccess() { return false; }
    default Optional<ResultError> toError() { return Optional.empty(); }
    default UUID ledgerEntryId() { throw new IllegalStateException(); }

    record Success(UUID ledgerEntryId) implements FxExchangeResult {
        public boolean isSuccess() { return true; }
    }

    record UserNotFound(String message) implements FxExchangeResult {
        public Optional<ResultError> toError() { return ResultError.of(404, "USER_NOT_FOUND", message); }
    }

    record QuoteNotFound(String message) implements FxExchangeResult {
        public Optional<ResultError> toError() { return ResultError.of(404, "QUOTE_NOT_FOUND", message); }
    }

    record QuoteExpired(String message) implements FxExchangeResult {
        public Optional<ResultError> toError() { return ResultError.of(410, "QUOTE_EXPIRED", message); }
    }

    record QuoteUsed(String message) implements FxExchangeResult {
        public Optional<ResultError> toError() { return ResultError.of(409, "QUOTE_USED", message); }
    }

    record QuoteUnavailable(String message) implements FxExchangeResult {
        public Optional<ResultError> toError() { return ResultError.of(409, "QUOTE_UNAVAILABLE", message); }
    }

    record InsufficientFunds(String message) implements FxExchangeResult {
        public Optional<ResultError> toError() { return ResultError.of(409, "INSUFFICIENT_FUNDS", message); }
    }

    record IdempotencyKeyConflict(String message) implements FxExchangeResult {
        public Optional<ResultError> toError() { return ResultError.of(409, "IDEMPOTENCY_KEY_CONFLICT", message); }
    }
}
