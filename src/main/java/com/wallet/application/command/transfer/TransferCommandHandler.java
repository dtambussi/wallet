package com.wallet.application.command.transfer;

import com.wallet.application.audit.WalletCommandAudit;
import com.wallet.domain.WalletIdempotency;
import com.wallet.application.port.out.FxRateProvider;
import com.wallet.application.port.out.LedgerRepository;
import com.wallet.application.result.TransferResult;
import com.wallet.application.service.UserQueryService;
import com.wallet.domain.FxRateSnapshot;
import com.wallet.domain.SupportedCurrency;
import com.wallet.domain.audit.AuditContext;
import com.wallet.domain.idempotency.IdempotencyContext;
import com.wallet.domain.idempotency.LedgerEntryIdempotency;
import com.wallet.domain.ledger.LedgerEntryTypes;
import com.wallet.domain.ledger.LedgerLine;
import com.wallet.domain.money.MoneyScale;
import com.wallet.infrastructure.exception.IdempotencyKeyConflictException;
import com.wallet.infrastructure.exception.InsufficientFundsException;
import com.wallet.infrastructure.idempotency.IdempotencyFingerprintPolicy;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferCommandHandler {

    private final LedgerRepository ledgerRepository;
    private final UserQueryService userQueryService;
    private final WalletCommandAudit commandAudit;
    private final FxRateProvider fxRateProvider;

    public TransferCommandHandler(
        LedgerRepository ledgerRepository,
        UserQueryService userQueryService,
        WalletCommandAudit commandAudit,
        FxRateProvider fxRateProvider
    ) {
        this.ledgerRepository = ledgerRepository;
        this.userQueryService = userQueryService;
        this.commandAudit = commandAudit;
        this.fxRateProvider = fxRateProvider;
    }

    @Transactional
    public TransferResult transfer(
        UUID fromUserId,
        UUID toUserId,
        SupportedCurrency fromCurrency,
        SupportedCurrency toCurrency,
        BigDecimal amount,
        IdempotencyContext idempotencyContext,
        AuditContext audit
    ) {
        String scopedIdempotencyKey = idempotencyContext.scopedKey();
        String fingerprint = idempotencyContext.fingerprint().value();
        TransferValidationResult idempotencyValidationResult = validateTransferIdempotency(
            scopedIdempotencyKey, fingerprint, audit, idempotencyContext, fromCurrency, amount, toUserId
        );
        if (!idempotencyValidationResult.shouldContinue()) return idempotencyValidationResult.result();

        TransferValidationResult requestValidationResult = validateTransferRequest(
            fromUserId, toUserId, amount, audit, idempotencyContext, fromCurrency, toUserId
        );
        if (!requestValidationResult.shouldContinue()) return requestValidationResult.result();

        try {
            UUID ledgerEntryId;
            if (fromCurrency == toCurrency) {
                ledgerEntryId = ledgerRepository.postLedgerEntry(
                    scopedIdempotencyKey,
                    LedgerEntryTypes.TRANSFER,
                    List.of(
                        LedgerLine.available(fromUserId, fromCurrency.name(), amount.negate()),
                        LedgerLine.available(toUserId, toCurrency.name(), amount)
                    ),
                    Map.of("currency", fromCurrency.name(), "toUser", toUserId.toString()),
                    fingerprint,
                    audit.correlationId()
                ).ledgerEntryId();
            } else {
                FxRateSnapshot rateSnapshot = fxRateProvider.rate(fromCurrency, toCurrency);
                BigDecimal appliedRate = rateSnapshot.buyUnitsPerOneSell();
                BigDecimal recipientAmount = MoneyScale.round(amount.multiply(appliedRate));
                ledgerEntryId = ledgerRepository.postLedgerEntry(
                    scopedIdempotencyKey,
                    LedgerEntryTypes.CROSS_CURRENCY_TRANSFER,
                    List.of(
                        LedgerLine.available(fromUserId, fromCurrency.name(), amount.negate()),
                        LedgerLine.available(toUserId, toCurrency.name(), recipientAmount)
                    ),
                    buildCrossCurrencyTransferMetadata(fromCurrency, toCurrency, amount, recipientAmount, appliedRate, rateSnapshot, toUserId),
                    fingerprint,
                    audit.correlationId()
                ).ledgerEntryId();
            }
            TransferResult successResult = new TransferResult.Success(ledgerEntryId);
            commandAudit.recordTransferAttemptOutcome(
                successResult, audit, idempotencyContext, fromCurrency.name(), amount, toUserId
            );
            return successResult;
        } catch (InsufficientFundsException insufficientFundsException) {
            TransferResult result = new TransferResult.InsufficientFunds(insufficientFundsException.getMessage());
            commandAudit.recordTransferAttemptOutcome(
                result, audit, idempotencyContext, fromCurrency.name(), amount, toUserId
            );
            return result;
        } catch (IdempotencyKeyConflictException idempotencyConflictException) {
            TransferResult result = new TransferResult.IdempotencyKeyConflict(idempotencyConflictException.getMessage());
            commandAudit.recordTransferAttemptOutcome(
                result, audit, idempotencyContext, fromCurrency.name(), amount, toUserId
            );
            return result;
        }
    }

    private TransferValidationResult validateTransferIdempotency(
        String scopedIdempotencyKey,
        String fingerprint,
        AuditContext audit,
        IdempotencyContext idempotencyContext,
        SupportedCurrency fromCurrency,
        BigDecimal amount,
        UUID toUserId
    ) {
        Optional<LedgerEntryIdempotency> existingLedgerIdempotency = ledgerRepository
            .findIdempotencyByScopedKey(scopedIdempotencyKey);
        if (existingLedgerIdempotency.isEmpty()) {
            return TransferValidationResult.continueFlow();
        }
        LedgerEntryIdempotency priorLedgerIdempotency = existingLedgerIdempotency.get();
        boolean fingerprintMatches = IdempotencyFingerprintPolicy.storedMatchesRequest(
            priorLedgerIdempotency.idempotencyFingerprint(), fingerprint
        );
        if (!fingerprintMatches) {
            return TransferValidationResult.returnResult(recordAndReturnTransferBusinessReject(
                new TransferResult.IdempotencyKeyConflict(WalletIdempotency.IDEMPOTENCY_KEY_CONFLICT_MESSAGE),
                audit,
                idempotencyContext,
                fromCurrency,
                amount,
                toUserId
            ));
        }
        TransferResult.Success replayResult = new TransferResult.Success(priorLedgerIdempotency.ledgerEntryId());
        commandAudit.recordTransferAttemptIdempotencyReplay(replayResult, audit, scopedIdempotencyKey);
        return TransferValidationResult.returnResult(replayResult);
    }

    private TransferValidationResult validateTransferRequest(
        UUID fromUserId,
        UUID toUserId,
        BigDecimal amount,
        AuditContext audit,
        IdempotencyContext idempotencyContext,
        SupportedCurrency fromCurrency,
        UUID toUserIdForAudit
    ) {
        if (!userQueryService.existsById(fromUserId)) {
            return TransferValidationResult.returnResult(recordAndReturnTransferBusinessReject(
                new TransferResult.UserNotFound("User not found: " + fromUserId),
                audit,
                idempotencyContext,
                fromCurrency,
                amount,
                toUserIdForAudit
            ));
        }
        if (!userQueryService.existsById(toUserId)) {
            return TransferValidationResult.returnResult(recordAndReturnTransferBusinessReject(
                new TransferResult.UserNotFound("User not found: " + toUserId),
                audit,
                idempotencyContext,
                fromCurrency,
                amount,
                toUserIdForAudit
            ));
        }
        if (fromUserId.equals(toUserId)) {
            return TransferValidationResult.returnResult(recordAndReturnTransferBusinessReject(
                new TransferResult.SameAccountTransfer("Cannot transfer to self"),
                audit,
                idempotencyContext,
                fromCurrency,
                amount,
                toUserIdForAudit
            ));
        }
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            return TransferValidationResult.returnResult(recordAndReturnTransferBusinessReject(
                new TransferResult.InvalidAmount("amount must be positive"),
                audit,
                idempotencyContext,
                fromCurrency,
                amount,
                toUserIdForAudit
            ));
        }
        return TransferValidationResult.continueFlow();
    }

    private TransferResult recordAndReturnTransferBusinessReject(
        TransferResult businessRejectResult,
        AuditContext audit,
        IdempotencyContext idempotencyContext,
        SupportedCurrency fromCurrency,
        BigDecimal amount,
        UUID toUserId
    ) {
        commandAudit.recordTransferAttemptOutcome(
            businessRejectResult, audit, idempotencyContext, fromCurrency.name(), amount, toUserId
        );
        return businessRejectResult;
    }

    private Map<String, Object> buildCrossCurrencyTransferMetadata(
        SupportedCurrency fromCurrency,
        SupportedCurrency toCurrency,
        BigDecimal sentAmount,
        BigDecimal receivedAmount,
        BigDecimal appliedRate,
        FxRateSnapshot rateSnapshot,
        UUID toUserId
    ) {
        Map<String, Object> crossCurrencyMetadata = new LinkedHashMap<>();
        crossCurrencyMetadata.put("fromCurrency", fromCurrency.name());
        crossCurrencyMetadata.put("toCurrency", toCurrency.name());
        crossCurrencyMetadata.put("sentAmount", sentAmount.stripTrailingZeros().toPlainString());
        crossCurrencyMetadata.put("receivedAmount", receivedAmount.stripTrailingZeros().toPlainString());
        crossCurrencyMetadata.put("appliedRate", appliedRate.stripTrailingZeros().toPlainString());
        crossCurrencyMetadata.put("toUser", toUserId.toString());
        crossCurrencyMetadata.put("fxPricedAt", rateSnapshot.pricedAt().toString());
        crossCurrencyMetadata.put("fxSource", rateSnapshot.source());
        crossCurrencyMetadata.put("fxServedFromStale", rateSnapshot.servedFromStaleCache());
        return crossCurrencyMetadata;
    }

    private record TransferValidationResult(boolean shouldContinue, TransferResult result) {
        static TransferValidationResult continueFlow() {
            return new TransferValidationResult(true, null);
        }

        static TransferValidationResult returnResult(TransferResult result) {
            return new TransferValidationResult(false, result);
        }
    }
}
