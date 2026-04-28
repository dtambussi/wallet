package com.wallet.application.command.deposit;

import com.wallet.application.audit.WalletCommandAudit;
import com.wallet.domain.WalletIdempotency;
import com.wallet.application.port.out.LedgerRepository;
import com.wallet.application.result.DepositResult;
import com.wallet.application.service.UserQueryService;
import com.wallet.domain.SupportedCurrency;
import com.wallet.domain.audit.AuditContext;
import com.wallet.domain.idempotency.IdempotencyContext;
import com.wallet.domain.idempotency.LedgerEntryIdempotency;
import com.wallet.domain.ledger.LedgerEntryTypes;
import com.wallet.domain.ledger.LedgerLine;
import com.wallet.infrastructure.exception.IdempotencyKeyConflictException;
import com.wallet.infrastructure.idempotency.IdempotencyFingerprintPolicy;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DepositCommandHandler {

    private final LedgerRepository ledgerRepository;
    private final UserQueryService userQueryService;
    private final WalletCommandAudit commandAudit;

    public DepositCommandHandler(
        LedgerRepository ledgerRepository, UserQueryService userQueryService, WalletCommandAudit commandAudit
    ) {
        this.ledgerRepository = ledgerRepository;
        this.userQueryService = userQueryService;
        this.commandAudit = commandAudit;
    }

    @Transactional
    public DepositResult deposit(
        UUID userId,
        SupportedCurrency currency,
        BigDecimal amount,
        IdempotencyContext idempotencyContext,
        AuditContext audit
    ) {
        if (!userQueryService.existsById(userId)) {
            DepositResult result = new DepositResult.UserNotFound("User not found: " + userId);
            commandAudit.recordDepositAttemptOutcome(
                result, audit, idempotencyContext, currency.name(), amount
            );
            return result;
        }
        String scopedIdempotencyKey = idempotencyContext.scopedKey();
        String fingerprint = idempotencyContext.fingerprint().value();
        Optional<LedgerEntryIdempotency> existingLedgerIdempotency = ledgerRepository
            .findIdempotencyByScopedKey(scopedIdempotencyKey);
        if (existingLedgerIdempotency.isPresent()) {
            LedgerEntryIdempotency priorLedgerIdempotency = existingLedgerIdempotency.get();
            boolean fingerprintMatches = IdempotencyFingerprintPolicy.storedMatchesRequest(
                priorLedgerIdempotency.idempotencyFingerprint(), fingerprint
            );
            if (!fingerprintMatches) {
                DepositResult result = new DepositResult.IdempotencyKeyConflict(
                    WalletIdempotency.IDEMPOTENCY_KEY_CONFLICT_MESSAGE
                );
                commandAudit.recordDepositAttemptOutcome(
                    result, audit, idempotencyContext, currency.name(), amount
                );
                return result;
            }
            DepositResult.Success replay = new DepositResult.Success(priorLedgerIdempotency.ledgerEntryId());
            commandAudit.recordDepositAttemptIdempotencyReplay(
                replay, audit, currency.name(), amount, scopedIdempotencyKey
            );
            return replay;
        }
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            DepositResult result = new DepositResult.InvalidAmount("amount must be positive");
            commandAudit.recordDepositAttemptOutcome(
                result, audit, idempotencyContext, currency.name(), amount
            );
            return result;
        }
        try {
            UUID ledgerEntryId = ledgerRepository.postLedgerEntry(
                scopedIdempotencyKey,
                LedgerEntryTypes.DEPOSIT,
                List.of(LedgerLine.available(userId, currency.name(), amount)),
                Map.of("currency", currency.name()),
                fingerprint,
                audit.correlationId()
            ).ledgerEntryId();
            DepositResult successResult = new DepositResult.Success(ledgerEntryId);
            commandAudit.recordDepositAttemptOutcome(
                successResult, audit, idempotencyContext, currency.name(), amount
            );
            return successResult;
        } catch (IdempotencyKeyConflictException idempotencyConflictException) {
            DepositResult result = new DepositResult.IdempotencyKeyConflict(idempotencyConflictException.getMessage());
            commandAudit.recordDepositAttemptOutcome(
                result, audit, idempotencyContext, currency.name(), amount
            );
            return result;
        }
    }
}
