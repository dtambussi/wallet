package com.wallet.application.audit;

import com.wallet.application.port.out.AuditTrailRepository;
import com.wallet.application.result.BatchTransferResult;
import com.wallet.application.result.DepositResult;
import com.wallet.application.result.FxExchangeResult;
import com.wallet.application.result.FxQuoteResult;
import com.wallet.application.result.SettleResult;
import com.wallet.application.result.TransferResult;
import com.wallet.application.result.WithdrawResult;
import com.wallet.application.command.fx.FxCommandHandler;
import com.wallet.domain.audit.AuditCommandTypes;
import com.wallet.domain.audit.AuditContext;
import com.wallet.domain.audit.AuditOutcomes;
import com.wallet.domain.audit.FinancialAuditEvent;
import com.wallet.domain.idempotency.IdempotencyContext;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import com.wallet.infrastructure.id.UuidV7Generator;
import org.springframework.stereotype.Component;

/**
 * Records one {@link FinancialAuditEvent} per command outcome so operators and auditors can reconcile
 * HTTP requests, idempotency keys, and ledger entries (including rejections).
 */
@Component
public class WalletCommandAudit {

    private final AuditTrailRepository auditTrailRepository;

    public WalletCommandAudit(AuditTrailRepository auditTrailRepository) {
        this.auditTrailRepository = auditTrailRepository;
    }

    public void recordDepositAttemptOutcome(
        DepositResult result,
        AuditContext ctx,
        IdempotencyContext idempotency,
        String currencyCode,
        BigDecimal amount
    ) {
        // e.g. "f47ac10b-58cc-4372-a567-0e02b2c3d479|client-key-123"
        String scopedIdempotencyKey = idempotency.scopedKey();
        switch (result) {
            case DepositResult.Success success -> auditTrailRepository.append(
                new FinancialAuditEvent(
                    ctx.correlationId(),
                    ctx.subjectUserId(),
                    AuditCommandTypes.DEPOSIT,
                    AuditOutcomes.SUCCESS,
                    success.ledgerEntryId(),
                    scopedIdempotencyKey,
                    details(
                        "currency", currencyCode,
                        "amount", amount.toPlainString()
                    )
                )
            );
            case DepositResult.UserNotFound userNotFound -> auditTrailRepository.append(
                new FinancialAuditEvent(
                    ctx.correlationId(),
                    ctx.subjectUserId(),
                    AuditCommandTypes.DEPOSIT,
                    AuditOutcomes.USER_NOT_FOUND,
                    null,
                    scopedIdempotencyKey,
                    details("message", userNotFound.message())
                )
            );
            case DepositResult.InvalidAmount invalid -> auditTrailRepository.append(
                new FinancialAuditEvent(
                    ctx.correlationId(),
                    ctx.subjectUserId(),
                    AuditCommandTypes.DEPOSIT,
                    AuditOutcomes.INVALID_AMOUNT,
                    null,
                    scopedIdempotencyKey,
                    details(
                        "message", invalid.message(),
                        "currency", currencyCode,
                        "amount", amount.toPlainString()
                    )
                )
            );
            case DepositResult.IdempotencyKeyConflict conflict -> auditTrailRepository.append(
                new FinancialAuditEvent(
                    ctx.correlationId(),
                    ctx.subjectUserId(),
                    AuditCommandTypes.DEPOSIT,
                    AuditOutcomes.IDEMPOTENCY_CONFLICT,
                    null,
                    scopedIdempotencyKey,
                    details("message", conflict.message())
                )
            );
        }
    }

    public void recordDepositAttemptIdempotencyReplay(
        DepositResult.Success success,
        AuditContext ctx,
        String currencyCode,
        BigDecimal amount,
        String idempotencyKey
    ) {
        auditTrailRepository.append(
            new FinancialAuditEvent(
                ctx.correlationId(),
                ctx.subjectUserId(),
                AuditCommandTypes.DEPOSIT,
                AuditOutcomes.IDEMPOTENCY_REPLAY,
                success.ledgerEntryId(),
                idempotencyKey,
                details(
                    "currency", currencyCode,
                    "amount", amount.toPlainString()
                )
            )
        );
    }

    public void recordWithdrawalAttemptOutcome(
        WithdrawResult result,
        AuditContext ctx,
        IdempotencyContext idempotency,
        String currencyCode,
        BigDecimal amount
    ) {
        // e.g. "f47ac10b-58cc-4372-a567-0e02b2c3d479|client-key-123"
        String scopedIdempotencyKey = idempotency.scopedKey();
        switch (result) {
            case WithdrawResult.Success success -> auditTrailRepository.append(
                new FinancialAuditEvent(
                    ctx.correlationId(),
                    ctx.subjectUserId(),
                    AuditCommandTypes.WITHDRAWAL,
                    AuditOutcomes.SUCCESS,
                    success.ledgerEntryId(),
                    scopedIdempotencyKey,
                    details(
                        "currency", currencyCode,
                        "amount", amount.toPlainString()
                    )
                )
            );
            case WithdrawResult.UserNotFound userNotFound -> auditTrailRepository.append(
                new FinancialAuditEvent(
                    ctx.correlationId(),
                    ctx.subjectUserId(),
                    AuditCommandTypes.WITHDRAWAL,
                    AuditOutcomes.USER_NOT_FOUND,
                    null,
                    scopedIdempotencyKey,
                    details("message", userNotFound.message())
                )
            );
            case WithdrawResult.InvalidAmount invalid -> auditTrailRepository.append(
                new FinancialAuditEvent(
                    ctx.correlationId(),
                    ctx.subjectUserId(),
                    AuditCommandTypes.WITHDRAWAL,
                    AuditOutcomes.INVALID_AMOUNT,
                    null,
                    scopedIdempotencyKey,
                    details(
                        "message", invalid.message(),
                        "currency", currencyCode,
                        "amount", amount.toPlainString()
                    )
                )
            );
            case WithdrawResult.InsufficientFunds ins -> auditTrailRepository.append(
                new FinancialAuditEvent(
                    ctx.correlationId(),
                    ctx.subjectUserId(),
                    AuditCommandTypes.WITHDRAWAL,
                    AuditOutcomes.INSUFFICIENT_FUNDS,
                    null,
                    scopedIdempotencyKey,
                    details(
                        "message", ins.message(),
                        "currency", currencyCode,
                        "amount", amount.toPlainString()
                    )
                )
            );
            case WithdrawResult.IdempotencyKeyConflict conflict -> auditTrailRepository.append(
                new FinancialAuditEvent(
                    ctx.correlationId(),
                    ctx.subjectUserId(),
                    AuditCommandTypes.WITHDRAWAL,
                    AuditOutcomes.IDEMPOTENCY_CONFLICT,
                    null,
                    scopedIdempotencyKey,
                    details("message", conflict.message())
                )
            );
        }
    }

    public void recordWithdrawalAttemptIdempotencyReplay(
        WithdrawResult.Success success,
        AuditContext ctx,
        String currencyCode,
        BigDecimal amount,
        String idempotencyKey
    ) {
        auditTrailRepository.append(
            new FinancialAuditEvent(
                ctx.correlationId(),
                ctx.subjectUserId(),
                AuditCommandTypes.WITHDRAWAL,
                AuditOutcomes.IDEMPOTENCY_REPLAY,
                success.ledgerEntryId(),
                idempotencyKey,
                details(
                    "currency", currencyCode,
                    "amount", amount.toPlainString()
                )
            )
        );
    }

    public void recordTransferAttemptOutcome(
        TransferResult result,
        AuditContext ctx,
        IdempotencyContext idempotency,
        String currencyCode,
        BigDecimal amount,
        UUID toUserId
    ) {
        // e.g. "f47ac10b-58cc-4372-a567-0e02b2c3d479|client-key-123"
        String scopedIdempotencyKey = idempotency.scopedKey();
        switch (result) {
            case TransferResult.Success success -> auditTrailRepository.append(
                new FinancialAuditEvent(
                    ctx.correlationId(),
                    ctx.subjectUserId(),
                    AuditCommandTypes.TRANSFER,
                    AuditOutcomes.SUCCESS,
                    success.ledgerEntryId(),
                    scopedIdempotencyKey,
                    details(
                        "currency", currencyCode,
                        "amount", amount.toPlainString(),
                        "toUserId", toUserId.toString()
                    )
                )
            );
            case TransferResult.UserNotFound userNotFound -> auditTrailRepository.append(
                new FinancialAuditEvent(
                    ctx.correlationId(),
                    ctx.subjectUserId(),
                    AuditCommandTypes.TRANSFER,
                    AuditOutcomes.USER_NOT_FOUND,
                    null,
                    scopedIdempotencyKey,
                    details("message", userNotFound.message())
                )
            );
            case TransferResult.InvalidAmount invalid -> auditTrailRepository.append(
                new FinancialAuditEvent(
                    ctx.correlationId(),
                    ctx.subjectUserId(),
                    AuditCommandTypes.TRANSFER,
                    AuditOutcomes.INVALID_AMOUNT,
                    null,
                    scopedIdempotencyKey,
                    details(
                        "message", invalid.message(),
                        "currency", currencyCode,
                        "amount", amount.toPlainString()
                    )
                )
            );
            case TransferResult.SameAccountTransfer same -> auditTrailRepository.append(
                new FinancialAuditEvent(
                    ctx.correlationId(),
                    ctx.subjectUserId(),
                    AuditCommandTypes.TRANSFER,
                    AuditOutcomes.SAME_ACCOUNT_TRANSFER,
                    null,
                    scopedIdempotencyKey,
                    details("message", same.message())
                )
            );
            case TransferResult.InsufficientFunds ins -> auditTrailRepository.append(
                new FinancialAuditEvent(
                    ctx.correlationId(),
                    ctx.subjectUserId(),
                    AuditCommandTypes.TRANSFER,
                    AuditOutcomes.INSUFFICIENT_FUNDS,
                    null,
                    scopedIdempotencyKey,
                    details(
                        "message", ins.message(),
                        "currency", currencyCode,
                        "amount", amount.toPlainString()
                    )
                )
            );
            case TransferResult.IdempotencyKeyConflict conflict -> auditTrailRepository.append(
                new FinancialAuditEvent(
                    ctx.correlationId(),
                    ctx.subjectUserId(),
                    AuditCommandTypes.TRANSFER,
                    AuditOutcomes.IDEMPOTENCY_CONFLICT,
                    null,
                    scopedIdempotencyKey,
                    details("message", conflict.message())
                )
            );
        }
    }

    public void recordTransferAttemptIdempotencyReplay(
        TransferResult.Success success,
        AuditContext ctx,
        String idempotencyKey
    ) {
        auditTrailRepository.append(
            new FinancialAuditEvent(
                ctx.correlationId(),
                ctx.subjectUserId(),
                AuditCommandTypes.TRANSFER,
                AuditOutcomes.IDEMPOTENCY_REPLAY,
                success.ledgerEntryId(),
                idempotencyKey,
                Map.of("note", "same idempotency key and body as prior request")
            )
        );
    }

    public void recordFxQuoteAttemptOutcome(
        FxQuoteResult result,
        AuditContext ctx,
        BigDecimal sellAmount
    ) {
        switch (result) {
            case FxQuoteResult.Success success -> {
                FxCommandHandler.FxQuoteResponse quoteResponse = success.value();
                auditTrailRepository.append(
                    new FinancialAuditEvent(
                        ctx.correlationId(),
                        ctx.subjectUserId(),
                        AuditCommandTypes.FX_QUOTE,
                        AuditOutcomes.SUCCESS,
                        null,
                        null,
                        details(
                            "quoteId", quoteResponse.quoteId(),
                            "sellCurrency", quoteResponse.sellCurrency(),
                            "buyCurrency", quoteResponse.buyCurrency(),
                            "sellAmount", quoteResponse.sellAmount().toPlainString(),
                            "buyAmount", quoteResponse.buyAmount().toPlainString(),
                            "fxPricedAt", quoteResponse.pricedAt(),
                            "fxSource", quoteResponse.pricingSource(),
                            "fxServedFromStale", Boolean.toString(quoteResponse.servedFromStale())
                        )
                    )
                );
            }
            case FxQuoteResult.UserNotFound notFound -> auditTrailRepository.append(
                new FinancialAuditEvent(
                    ctx.correlationId(),
                    ctx.subjectUserId(),
                    AuditCommandTypes.FX_QUOTE,
                    AuditOutcomes.USER_NOT_FOUND,
                    null,
                    null,
                    details("message", notFound.message())
                )
            );
            case FxQuoteResult.InvalidSellAmount invalid -> auditTrailRepository.append(
                new FinancialAuditEvent(
                    ctx.correlationId(),
                    ctx.subjectUserId(),
                    AuditCommandTypes.FX_QUOTE,
                    AuditOutcomes.INVALID_SELL_AMOUNT,
                    null,
                    null,
                    details(
                        "message", invalid.message(),
                        "sellAmount", sellAmount.toPlainString()
                    )
                )
            );
        }
    }

    public void recordFxExchangeAttemptOutcome(
        FxExchangeResult result,
        AuditContext ctx,
        IdempotencyContext idempotency,
        UUID quoteId
    ) {
        // e.g. "f47ac10b-58cc-4372-a567-0e02b2c3d479|client-key-123"
        String scopedIdempotencyKey = idempotency.scopedKey();
        switch (result) {
            case FxExchangeResult.Success success -> auditTrailRepository.append(
                new FinancialAuditEvent(
                    ctx.correlationId(),
                    ctx.subjectUserId(),
                    AuditCommandTypes.FX_EXCHANGE,
                    AuditOutcomes.SUCCESS,
                    success.ledgerEntryId(),
                    scopedIdempotencyKey,
                    details("quoteId", quoteId.toString())
                )
            );
            case FxExchangeResult.UserNotFound notFound -> auditTrailRepository.append(
                new FinancialAuditEvent(
                    ctx.correlationId(),
                    ctx.subjectUserId(),
                    AuditCommandTypes.FX_EXCHANGE,
                    AuditOutcomes.USER_NOT_FOUND,
                    null,
                    scopedIdempotencyKey,
                    details("message", notFound.message())
                )
            );
            case FxExchangeResult.QuoteNotFound notFound -> auditTrailRepository.append(
                new FinancialAuditEvent(
                    ctx.correlationId(),
                    ctx.subjectUserId(),
                    AuditCommandTypes.FX_EXCHANGE,
                    AuditOutcomes.QUOTE_NOT_FOUND,
                    null,
                    scopedIdempotencyKey,
                    details("message", notFound.message(), "quoteId", quoteId.toString())
                )
            );
            case FxExchangeResult.QuoteUsed used -> auditTrailRepository.append(
                new FinancialAuditEvent(
                    ctx.correlationId(),
                    ctx.subjectUserId(),
                    AuditCommandTypes.FX_EXCHANGE,
                    AuditOutcomes.QUOTE_USED,
                    null,
                    scopedIdempotencyKey,
                    details("message", used.message(), "quoteId", quoteId.toString())
                )
            );
            case FxExchangeResult.QuoteExpired expired -> auditTrailRepository.append(
                new FinancialAuditEvent(
                    ctx.correlationId(),
                    ctx.subjectUserId(),
                    AuditCommandTypes.FX_EXCHANGE,
                    AuditOutcomes.QUOTE_EXPIRED,
                    null,
                    scopedIdempotencyKey,
                    details("message", expired.message(), "quoteId", quoteId.toString())
                )
            );
            case FxExchangeResult.QuoteUnavailable quoteUnavailable -> auditTrailRepository.append(
                new FinancialAuditEvent(
                    ctx.correlationId(),
                    ctx.subjectUserId(),
                    AuditCommandTypes.FX_EXCHANGE,
                    AuditOutcomes.QUOTE_UNAVAILABLE,
                    null,
                    scopedIdempotencyKey,
                    details("message", quoteUnavailable.message(), "quoteId", quoteId.toString())
                )
            );
            case FxExchangeResult.InsufficientFunds ins -> auditTrailRepository.append(
                new FinancialAuditEvent(
                    ctx.correlationId(),
                    ctx.subjectUserId(),
                    AuditCommandTypes.FX_EXCHANGE,
                    AuditOutcomes.INSUFFICIENT_FUNDS,
                    null,
                    scopedIdempotencyKey,
                    details("message", ins.message(), "quoteId", quoteId.toString())
                )
            );
            case FxExchangeResult.IdempotencyKeyConflict conflict -> auditTrailRepository.append(
                new FinancialAuditEvent(
                    ctx.correlationId(),
                    ctx.subjectUserId(),
                    AuditCommandTypes.FX_EXCHANGE,
                    AuditOutcomes.IDEMPOTENCY_CONFLICT,
                    null,
                    scopedIdempotencyKey,
                    details("message", conflict.message())
                )
            );
        }
    }

    public void recordFxExchangeAttemptIdempotencyReplay(
        FxExchangeResult.Success success,
        AuditContext ctx,
        String idempotencyKey,
        UUID quoteId
    ) {
        auditTrailRepository.append(
            new FinancialAuditEvent(
                ctx.correlationId(),
                ctx.subjectUserId(),
                AuditCommandTypes.FX_EXCHANGE,
                AuditOutcomes.IDEMPOTENCY_REPLAY,
                success.ledgerEntryId(),
                idempotencyKey,
                details("quoteId", quoteId.toString())
            )
        );
    }

    public void recordPayoutDispatched(UUID userId, UUID ledgerEntryId, String providerRef) {
        auditTrailRepository.append(new FinancialAuditEvent(
            UuidV7Generator.next().toString(), userId,
            AuditCommandTypes.PAYOUT_WORKER, AuditOutcomes.PAYOUT_DISPATCHED,
            ledgerEntryId, null,
            details("providerRef", providerRef)
        ));
    }

    public void recordPayoutRetrying(UUID userId, UUID ledgerEntryId, int attempt, String error) {
        auditTrailRepository.append(new FinancialAuditEvent(
            UuidV7Generator.next().toString(), userId,
            AuditCommandTypes.PAYOUT_WORKER, AuditOutcomes.PAYOUT_RETRYING,
            ledgerEntryId, null,
            details("attempt", String.valueOf(attempt), "error", error != null ? error : "unknown")
        ));
    }

    public void recordPayoutReversed(UUID userId, UUID originalLedgerEntryId, UUID reversalLedgerEntryId) {
        auditTrailRepository.append(new FinancialAuditEvent(
            UuidV7Generator.next().toString(), userId,
            AuditCommandTypes.PAYOUT_WORKER, AuditOutcomes.PAYOUT_REVERSED,
            originalLedgerEntryId, null,
            details("reversalLedgerEntryId", reversalLedgerEntryId.toString())
        ));
    }

    public void recordBatchTransferIdempotencyReplay(
        BatchTransferResult.Success success,
        AuditContext ctx,
        IdempotencyContext idempotency,
        int recipientCount
    ) {
        auditTrailRepository.append(
            new FinancialAuditEvent(
                ctx.correlationId(), ctx.subjectUserId(),
                AuditCommandTypes.BATCH_TRANSFER, AuditOutcomes.IDEMPOTENCY_REPLAY,
                success.ledgerEntryId(), idempotency.scopedKey(),
                details("recipientCount", String.valueOf(recipientCount))
            )
        );
    }

    public void recordBatchTransferOutcome(
        BatchTransferResult result,
        AuditContext ctx,
        IdempotencyContext idempotency,
        int recipientCount
    ) {
        // e.g. "f47ac10b-58cc-4372-a567-0e02b2c3d479|client-key-123"
        String scopedIdempotencyKey = idempotency.scopedKey();
        switch (result) {
            case BatchTransferResult.Success success -> auditTrailRepository.append(
                new FinancialAuditEvent(
                    ctx.correlationId(), ctx.subjectUserId(),
                    AuditCommandTypes.BATCH_TRANSFER, AuditOutcomes.SUCCESS,
                    success.ledgerEntryId(), scopedIdempotencyKey,
                    details("recipientCount", String.valueOf(recipientCount))
                )
            );
            case BatchTransferResult.UserNotFound userNotFound -> auditTrailRepository.append(
                new FinancialAuditEvent(
                    ctx.correlationId(), ctx.subjectUserId(),
                    AuditCommandTypes.BATCH_TRANSFER, AuditOutcomes.USER_NOT_FOUND,
                    null, scopedIdempotencyKey, details("message", userNotFound.message())
                )
            );
            case BatchTransferResult.InvalidBatch invalidBatch -> auditTrailRepository.append(
                new FinancialAuditEvent(
                    ctx.correlationId(), ctx.subjectUserId(),
                    AuditCommandTypes.BATCH_TRANSFER, AuditOutcomes.INVALID_BATCH,
                    null, scopedIdempotencyKey, details("message", invalidBatch.message())
                )
            );
            case BatchTransferResult.InsufficientFunds insufficientFunds -> auditTrailRepository.append(
                new FinancialAuditEvent(
                    ctx.correlationId(), ctx.subjectUserId(),
                    AuditCommandTypes.BATCH_TRANSFER, AuditOutcomes.INSUFFICIENT_FUNDS,
                    null, scopedIdempotencyKey, details("message", insufficientFunds.message())
                )
            );
            case BatchTransferResult.IdempotencyKeyConflict idempotencyKeyConflict -> auditTrailRepository.append(
                new FinancialAuditEvent(
                    ctx.correlationId(), ctx.subjectUserId(),
                    AuditCommandTypes.BATCH_TRANSFER, AuditOutcomes.IDEMPOTENCY_CONFLICT,
                    null, scopedIdempotencyKey, details("message", idempotencyKeyConflict.message())
                )
            );
        }
    }

    public void recordSettlementOutcome(SettleResult result, AuditContext ctx) {
        switch (result) {
            case SettleResult.Success success -> auditTrailRepository.append(
                new FinancialAuditEvent(
                    ctx.correlationId(), ctx.subjectUserId(),
                    AuditCommandTypes.SETTLEMENT, AuditOutcomes.SUCCESS,
                    null, null,
                    Map.of("settled", success.settled().toString())
                )
            );
            case SettleResult.NothingToSettle nothingToSettle -> auditTrailRepository.append(
                new FinancialAuditEvent(
                    ctx.correlationId(), ctx.subjectUserId(),
                    AuditCommandTypes.SETTLEMENT, AuditOutcomes.NOTHING_TO_SETTLE,
                    null, null, details("message", nothingToSettle.message())
                )
            );
            case SettleResult.UserNotFound userNotFound -> auditTrailRepository.append(
                new FinancialAuditEvent(
                    ctx.correlationId(), ctx.subjectUserId(),
                    AuditCommandTypes.SETTLEMENT, AuditOutcomes.USER_NOT_FOUND,
                    null, null, details("message", userNotFound.message())
                )
            );
        }
    }

    private static Map<String, Object> details(String... detailKeyValuePairs) {
        if (detailKeyValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("even number of key/value entries required");
        }
        LinkedHashMap<String, Object> detailMap = new LinkedHashMap<>();
        for (int detailPairIndex = 0; detailPairIndex < detailKeyValuePairs.length; detailPairIndex += 2) {
            detailMap.put(detailKeyValuePairs[detailPairIndex], detailKeyValuePairs[detailPairIndex + 1]);
        }
        return detailMap;
    }
}
