package com.wallet.application.command.withdrawal;

import com.wallet.application.audit.WalletCommandAudit;
import com.wallet.domain.WalletIdempotency;
import com.wallet.application.port.out.LedgerRepository;
import com.wallet.application.port.out.PayoutOutboxRepository;
import com.wallet.application.result.WithdrawResult;
import com.wallet.application.service.UserQueryService;
import com.wallet.domain.SupportedCurrency;
import com.wallet.domain.audit.AuditContext;
import com.wallet.domain.idempotency.IdempotencyContext;
import com.wallet.domain.idempotency.LedgerEntryIdempotency;
import com.wallet.domain.ledger.LedgerEntryTypes;
import com.wallet.domain.ledger.LedgerLine;
import com.wallet.infrastructure.exception.IdempotencyKeyConflictException;
import com.wallet.infrastructure.exception.InsufficientFundsException;
import com.wallet.infrastructure.idempotency.IdempotencyFingerprintPolicy;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WithdrawalCommandHandler {

    private final LedgerRepository ledgerRepository;
    private final PayoutOutboxRepository payoutOutboxRepository;
    private final UserQueryService userQueryService;
    private final WalletCommandAudit commandAudit;

    public WithdrawalCommandHandler(
        LedgerRepository ledgerRepository,
        PayoutOutboxRepository payoutOutboxRepository,
        UserQueryService userQueryService,
        WalletCommandAudit commandAudit
    ) {
        this.ledgerRepository = ledgerRepository;
        this.payoutOutboxRepository = payoutOutboxRepository;
        this.userQueryService = userQueryService;
        this.commandAudit = commandAudit;
    }

    @Transactional
    public WithdrawResult withdraw(
        UUID userId,
        SupportedCurrency currency,
        BigDecimal amount,
        IdempotencyContext idempotencyContext,
        AuditContext audit
    ) {
        if (!userQueryService.existsById(userId)) {
            return recordAndReturnWithdrawalBusinessReject(
                new WithdrawResult.UserNotFound("User not found: " + userId),
                audit,
                idempotencyContext,
                currency,
                amount
            );
        }
        String scopedIdempotencyKey = idempotencyContext.scopedKey();
        String fingerprint = idempotencyContext.fingerprint().value();
        WithdrawalValidationResult idempotencyValidationResult = validateWithdrawalIdempotency(
            scopedIdempotencyKey, fingerprint, audit, idempotencyContext, currency, amount
        );
        if (!idempotencyValidationResult.shouldContinue()) return idempotencyValidationResult.result();
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            return recordAndReturnWithdrawalBusinessReject(
                new WithdrawResult.InvalidAmount("amount must be positive"),
                audit,
                idempotencyContext,
                currency,
                amount
            );
        }
        try {
            UUID ledgerEntryId = ledgerRepository.postLedgerEntry(
                scopedIdempotencyKey,
                LedgerEntryTypes.WITHDRAWAL,
                List.of(LedgerLine.available(userId, currency.name(), amount.negate())),
                Map.of("currency", currency.name(), "providerRef", "pending"),
                fingerprint,
                audit.correlationId()
            ).ledgerEntryId();
            payoutOutboxRepository.insert(ledgerEntryId, userId, currency.name(), amount);
            WithdrawResult successResult = new WithdrawResult.Success(ledgerEntryId);
            commandAudit.recordWithdrawalAttemptOutcome(
                successResult, audit, idempotencyContext, currency.name(), amount
            );
            return successResult;
        } catch (InsufficientFundsException insufficientFundsException) {
            return recordAndReturnWithdrawalBusinessReject(
                new WithdrawResult.InsufficientFunds(insufficientFundsException.getMessage()),
                audit,
                idempotencyContext,
                currency,
                amount
            );
        } catch (IdempotencyKeyConflictException idempotencyConflictException) {
            return recordAndReturnWithdrawalBusinessReject(
                new WithdrawResult.IdempotencyKeyConflict(idempotencyConflictException.getMessage()),
                audit,
                idempotencyContext,
                currency,
                amount
            );
        }
    }

    private WithdrawalValidationResult validateWithdrawalIdempotency(
        String scopedIdempotencyKey,
        String fingerprint,
        AuditContext audit,
        IdempotencyContext idempotencyContext,
        SupportedCurrency currency,
        BigDecimal amount
    ) {
        Optional<LedgerEntryIdempotency> existingLedgerIdempotency = ledgerRepository
            .findIdempotencyByScopedKey(scopedIdempotencyKey);
        if (existingLedgerIdempotency.isEmpty()) {
            return WithdrawalValidationResult.continueFlow();
        }
        LedgerEntryIdempotency priorLedgerIdempotency = existingLedgerIdempotency.get();
        boolean fingerprintMatches = IdempotencyFingerprintPolicy.storedMatchesRequest(
            priorLedgerIdempotency.idempotencyFingerprint(), fingerprint
        );
        if (!fingerprintMatches) {
            return WithdrawalValidationResult.returnResult(recordAndReturnWithdrawalBusinessReject(
                new WithdrawResult.IdempotencyKeyConflict(WalletIdempotency.IDEMPOTENCY_KEY_CONFLICT_MESSAGE),
                audit,
                idempotencyContext,
                currency,
                amount
            ));
        }
        WithdrawResult.Success replayResult = new WithdrawResult.Success(priorLedgerIdempotency.ledgerEntryId());
        commandAudit.recordWithdrawalAttemptIdempotencyReplay(
            replayResult, audit, currency.name(), amount, scopedIdempotencyKey
        );
        return WithdrawalValidationResult.returnResult(replayResult);
    }

    private WithdrawResult recordAndReturnWithdrawalBusinessReject(
        WithdrawResult businessRejectResult,
        AuditContext audit,
        IdempotencyContext idempotencyContext,
        SupportedCurrency currency,
        BigDecimal amount
    ) {
        commandAudit.recordWithdrawalAttemptOutcome(
            businessRejectResult, audit, idempotencyContext, currency.name(), amount
        );
        return businessRejectResult;
    }

    private record WithdrawalValidationResult(boolean shouldContinue, WithdrawResult result) {
        static WithdrawalValidationResult continueFlow() {
            return new WithdrawalValidationResult(true, null);
        }

        static WithdrawalValidationResult returnResult(WithdrawResult result) {
            return new WithdrawalValidationResult(false, result);
        }
    }
}
