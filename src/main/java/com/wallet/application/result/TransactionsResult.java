package com.wallet.application.result;

import com.wallet.application.port.out.LedgerRepository.LedgerEntryHistoryItem;
import java.util.List;
import java.util.Optional;

public sealed interface TransactionsResult permits TransactionsResult.Success, TransactionsResult.UserNotFound {

    default Optional<ResultError> toError() { return Optional.empty(); }
    default List<LedgerEntryHistoryItem> entries() { throw new IllegalStateException(); }

    record Success(TransactionsView ledgerHistory) implements TransactionsResult {
        public List<LedgerEntryHistoryItem> entries() { return ledgerHistory.entries(); }
    }

    record UserNotFound(String message) implements TransactionsResult {
        public Optional<ResultError> toError() { return ResultError.of(404, "USER_NOT_FOUND", message); }
    }
}
