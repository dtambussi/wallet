package com.wallet.application.result;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

public sealed interface BalancesResult permits BalancesResult.Success, BalancesResult.UserNotFound {

    default Optional<ResultError> toError() { return Optional.empty(); }
    default Map<String, BigDecimal> amountsByCurrency() { throw new IllegalStateException(); }

    record Success(BalancesView balances) implements BalancesResult {
        public Map<String, BigDecimal> amountsByCurrency() { return balances.amountsByCurrency(); }
    }

    record UserNotFound(String message) implements BalancesResult {
        public Optional<ResultError> toError() { return ResultError.of(404, "USER_NOT_FOUND", message); }
    }
}
