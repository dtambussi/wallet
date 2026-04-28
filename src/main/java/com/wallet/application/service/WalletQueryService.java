package com.wallet.application.service;

import com.wallet.application.port.out.LedgerRepository;
import com.wallet.application.result.BalancesResult;
import com.wallet.application.result.BalancesView;
import com.wallet.application.result.TransactionsResult;
import com.wallet.application.result.TransactionsView;
import com.wallet.domain.SupportedCurrency;
import com.wallet.domain.ledger.TransactionCursor;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.lang.Nullable;

@Service
public class WalletQueryService {

    private final LedgerRepository ledgerRepository;
    private final UserQueryService userQueryService;

    public WalletQueryService(LedgerRepository ledgerRepository, UserQueryService userQueryService) {
        this.ledgerRepository = ledgerRepository;
        this.userQueryService = userQueryService;
    }

    @Transactional(readOnly = true)
    public BalancesResult getBalances(UUID userId) {
        if (!userQueryService.existsById(userId)) {
            return new BalancesResult.UserNotFound("User not found: " + userId);
        }
        Map<String, BigDecimal> storedBalances = ledgerRepository.loadBalances(userId);
        Map<String, BigDecimal> balancesByCurrency = new LinkedHashMap<>();
        for (SupportedCurrency supportedCurrency : SupportedCurrency.values()) {
            balancesByCurrency.put(supportedCurrency.name(), storedBalances.getOrDefault(supportedCurrency.name(), BigDecimal.ZERO));
        }
        return new BalancesResult.Success(new BalancesView(balancesByCurrency));
    }

    @Transactional(readOnly = true)
    public BalancesResult getPendingBalances(UUID userId) {
        if (!userQueryService.existsById(userId)) {
            return new BalancesResult.UserNotFound("User not found: " + userId);
        }
        Map<String, BigDecimal> pending = ledgerRepository.loadPendingBalances(userId);
        // Only return currencies with non-zero pending amounts.
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        for (SupportedCurrency c : SupportedCurrency.values()) {
            BigDecimal amount = pending.getOrDefault(c.name(), BigDecimal.ZERO);
            if (amount.compareTo(BigDecimal.ZERO) > 0) {
                result.put(c.name(), amount);
            }
        }
        return new BalancesResult.Success(new BalancesView(result));
    }

    /** Returns paginated ledger history ({@link com.wallet.application.port.out.LedgerRepository.LedgerEntryHistoryItem} per parent entry). */
    @Transactional(readOnly = true)
    public TransactionsResult getTransactions(UUID userId, int limit, @Nullable TransactionCursor cursor) {
        if (!userQueryService.existsById(userId)) {
            return new TransactionsResult.UserNotFound("User not found: " + userId);
        }
        return new TransactionsResult.Success(
            new TransactionsView(ledgerRepository.listTransactionsForUser(userId, limit, cursor))
        );
    }

}
