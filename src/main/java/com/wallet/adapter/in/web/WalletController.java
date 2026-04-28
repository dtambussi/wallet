package com.wallet.adapter.in.web;

import com.wallet.application.command.deposit.DepositCommandHandler;
import com.wallet.application.command.settlement.SettlementCommandHandler;
import com.wallet.application.command.withdrawal.WithdrawalCommandHandler;
import com.wallet.application.port.out.LedgerRepository.LedgerEntryHistoryItem;
import com.wallet.application.result.BalancesResult;
import com.wallet.application.result.DepositResult;
import com.wallet.application.result.ResultError;
import com.wallet.application.result.SettleResult;
import com.wallet.application.result.TransactionsResult;
import com.wallet.application.result.WithdrawResult;
import com.wallet.application.service.WalletQueryService;
import com.wallet.domain.SupportedCurrency;
import com.wallet.domain.idempotency.IdempotencyContext;
import com.wallet.domain.idempotency.IdempotencyFingerprint;
import com.wallet.domain.ledger.TransactionCursor;
import com.wallet.infrastructure.audit.AuditContextSupport;
import com.wallet.infrastructure.config.OperationalSwitchPolicy;
import com.wallet.infrastructure.exception.WalletApiException;
import com.wallet.infrastructure.validation.ValidMoneyAmount;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/users/{userId}")
public class WalletController {

    private final DepositCommandHandler depositCommandHandler;
    private final WithdrawalCommandHandler withdrawalCommandHandler;
    private final WalletQueryService walletQueryService;
    private final SettlementCommandHandler settlementCommandHandler;
    private final MeterRegistry meterRegistry;
    private final OperationalSwitchPolicy operationalSwitchPolicy;

    public WalletController(
        DepositCommandHandler depositCommandHandler,
        WithdrawalCommandHandler withdrawalCommandHandler,
        WalletQueryService walletQueryService,
        SettlementCommandHandler settlementCommandHandler,
        MeterRegistry meterRegistry,
        OperationalSwitchPolicy operationalSwitchPolicy
    ) {
        this.depositCommandHandler = depositCommandHandler;
        this.withdrawalCommandHandler = withdrawalCommandHandler;
        this.walletQueryService = walletQueryService;
        this.settlementCommandHandler = settlementCommandHandler;
        this.meterRegistry = meterRegistry;
        this.operationalSwitchPolicy = operationalSwitchPolicy;
    }

    @PostMapping("/deposits")
    @ResponseStatus(HttpStatus.CREATED)
    public LedgerEntryResponse deposit(
        @PathVariable UUID userId,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @Valid @RequestBody AmountCurrencyRequest body
    ) {
        SupportedCurrency requestedCurrency = SupportedCurrency.fromCode(body.currency());
        IdempotencyContext idempotencyContext = IdempotencyContext.scopedForUser(
            userId,
            idempotencyKey,
            IdempotencyFingerprint.ofDepositOrWithdraw(body.amount(), body.currency())
        );
        DepositResult depositResult;
        try {
            depositResult = depositCommandHandler.deposit(
                userId, requestedCurrency, body.amount(), idempotencyContext, AuditContextSupport.forPathUser(userId)
            );
        } catch (RuntimeException e) {
            meterRegistry.counter("wallet_internal_errors_total", "operation", "deposit").increment();
            throw e;
        }
        meterRegistry.counter("wallet_money_flow_total",
            "operation", "deposit",
            "outcome", depositResult.isSuccess() ? "success" : "business_reject"
        ).increment();
        throwIfError(depositResult.toError());
        return new LedgerEntryResponse(depositResult.ledgerEntryId().toString());
    }

    @PostMapping("/withdrawals")
    @ResponseStatus(HttpStatus.CREATED)
    public LedgerEntryResponse withdraw(
        @PathVariable UUID userId,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @Valid @RequestBody AmountCurrencyRequest body
    ) {
        if (!operationalSwitchPolicy.isWithdrawalsEnabled()) {
            meterRegistry.counter("wallet_money_flow_total", "operation", "withdrawal", "outcome", "disabled").increment();
            throw new WalletApiException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_DISABLED", "Withdrawal operations are temporarily disabled");
        }
        SupportedCurrency requestedCurrency = SupportedCurrency.fromCode(body.currency());
        IdempotencyContext idempotencyContext = IdempotencyContext.scopedForUser(
            userId,
            idempotencyKey,
            IdempotencyFingerprint.ofDepositOrWithdraw(body.amount(), body.currency())
        );
        WithdrawResult withdrawResult;
        try {
            withdrawResult = withdrawalCommandHandler.withdraw(
                userId, requestedCurrency, body.amount(), idempotencyContext, AuditContextSupport.forPathUser(userId)
            );
        } catch (RuntimeException e) {
            meterRegistry.counter("wallet_internal_errors_total", "operation", "withdrawal").increment();
            throw e;
        }
        meterRegistry.counter("wallet_money_flow_total",
            "operation", "withdrawal",
            "outcome", withdrawResult.isSuccess() ? "success" : "business_reject"
        ).increment();
        throwIfError(withdrawResult.toError());
        return new LedgerEntryResponse(withdrawResult.ledgerEntryId().toString());
    }

    @GetMapping("/balances")
    public Map<String, BigDecimal> getBalances(@PathVariable UUID userId) {
        BalancesResult result = walletQueryService.getBalances(userId);
        throwIfError(result.toError());
        return result.amountsByCurrency();
    }

    @GetMapping("/transactions")
    public TransactionPage getTransactions(
        @PathVariable UUID userId,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
        @RequestParam(required = false) String cursor
    ) {
        TransactionCursor transactionCursor = TransactionCursorCodec.decode(cursor).orElse(null);
        // Ask for one extra row so we can tell if another page exists (if we get N+1, hasMore = true) without a separate count query.
        int fetchLimit = limit + 1;
        TransactionsResult transactionsResult = walletQueryService.getTransactions(userId, fetchLimit, transactionCursor);
        throwIfError(transactionsResult.toError());
        List<LedgerEntryHistoryItem> fetched = transactionsResult.entries();

        boolean hasMore = fetched.size() > limit;
        List<LedgerEntryHistoryItem> page = hasMore ? fetched.subList(0, limit) : fetched;
        String nextCursor = null;
        if (hasMore) {
            nextCursor = TransactionCursorCodec.encode(new TransactionCursor(page.getLast().entryId()));
        }
        List<TransactionResponse> items = page.stream()
            .map(entry -> new TransactionResponse(
                entry.entryId().toString(),
                entry.entryType(),
                entry.createdAtUtc(),
                entry.lines().stream().map(line -> new LineResponse(line.currency(), line.amount())).toList()
            ))
            .collect(Collectors.toList());
        return new TransactionPage(items, nextCursor, hasMore);
    }

    /**
     * Returns {@code pending_amount} per currency (uncleared inbound). Most relevant when
     * {@code POST /batch-transfers} has credited this user in pending, or for future pending-credit flows.
     */
    @GetMapping("/pending-balances")
    public Map<String, BigDecimal> getPendingBalances(@PathVariable UUID userId) {
        BalancesResult result = walletQueryService.getPendingBalances(userId);
        throwIfError(result.toError());
        return result.amountsByCurrency();
    }

    /**
     * Moves all {@code pending_amount} into spendable {@code amount} for this user. Most commonly run on
     * a schedule (or by ops) after batch/payroll-style flows; {@code POST /batch-transfers} is what creates
     * pending recipient credits in this codebase.
     */
    @PostMapping("/settle")
    public Map<String, BigDecimal> settle(@PathVariable UUID userId) {
        SettleResult result = settlementCommandHandler.settle(userId, AuditContextSupport.forPathUser(userId));
        throwIfError(result.toError());
        return result.settledAmounts();
    }

    private static void throwIfError(java.util.Optional<ResultError> error) {
        error.ifPresent(e -> { throw new WalletApiException(HttpStatus.valueOf(e.httpStatus()), e.errorCode(), e.message()); });
    }

    public record AmountCurrencyRequest(
        @NotNull @ValidMoneyAmount @Schema(example = "100.00") BigDecimal amount,
        @NotBlank @Schema(example = "USD") String currency
    ) {}

    public record LineResponse(
        @Schema(example = "USD") String currency,
        @Schema(example = "100.00") BigDecimal amount
    ) {}

    public record TransactionResponse(
        @Schema(example = "7c9e6679-7425-40de-944b-e07fc1f90ae7") String ledgerEntryId,
        @Schema(example = "DEPOSIT") String entryType,
        @Schema(example = "2026-01-15T12:00:00Z") String createdAt,
        List<LineResponse> lines
    ) {}

    public record TransactionPage(
        List<TransactionResponse> items,
        @Schema(example = "0193c91e8f3d7b2a9e8f4c1d2b3a4e5f") String nextCursor,
        @Schema(example = "false") boolean hasMore
    ) {}
}
