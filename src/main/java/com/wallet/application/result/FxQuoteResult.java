package com.wallet.application.result;

import com.wallet.application.command.fx.FxCommandHandler.FxQuoteResponse;
import java.util.Optional;

public sealed interface FxQuoteResult permits FxQuoteResult.Success, FxQuoteResult.UserNotFound, FxQuoteResult.InvalidSellAmount {

    default Optional<ResultError> toError() { return Optional.empty(); }
    default FxQuoteResponse quoteResponse() { throw new IllegalStateException(); }

    record Success(FxQuoteResponse value) implements FxQuoteResult {
        public FxQuoteResponse quoteResponse() { return value; }
    }

    record UserNotFound(String message) implements FxQuoteResult {
        public Optional<ResultError> toError() { return ResultError.of(404, "USER_NOT_FOUND", message); }
    }

    record InvalidSellAmount(String message) implements FxQuoteResult {
        public Optional<ResultError> toError() { return ResultError.of(400, "VALIDATION_ERROR", message); }
    }
}
