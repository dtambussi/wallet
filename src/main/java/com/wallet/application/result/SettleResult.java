package com.wallet.application.result;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

public sealed interface SettleResult
    permits SettleResult.Success,
            SettleResult.UserNotFound,
            SettleResult.NothingToSettle {

    default Optional<ResultError> toError() { return Optional.empty(); }
    default Map<String, BigDecimal> settledAmounts() { throw new IllegalStateException(); }

    /** settled contains the per-currency amounts that were moved from pending to available. */
    record Success(Map<String, BigDecimal> settled) implements SettleResult {
        public Map<String, BigDecimal> settledAmounts() { return settled; }
    }

    record UserNotFound(String message) implements SettleResult {
        public Optional<ResultError> toError() { return ResultError.of(404, "USER_NOT_FOUND", message); }
    }

    record NothingToSettle(String message) implements SettleResult {
        public Map<String, BigDecimal> settledAmounts() { return Map.of(); }
    }
}
