package com.wallet.application.result;

import com.wallet.application.port.out.LedgerRepository.LedgerEntryHistoryItem;
import java.util.List;

/** One page of ledger history: one {@link LedgerEntryHistoryItem} per {@code ledger_entries} row. */
public record TransactionsView(List<LedgerEntryHistoryItem> entries) {
}
