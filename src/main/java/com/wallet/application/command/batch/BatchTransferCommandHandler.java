package com.wallet.application.command.batch;

import com.wallet.application.audit.WalletCommandAudit;
import com.wallet.application.port.out.LedgerRepository;
import com.wallet.application.result.BatchTransferResult;
import com.wallet.application.service.UserQueryService;
import com.wallet.domain.SupportedCurrency;
import com.wallet.domain.WalletIdempotency;
import com.wallet.domain.audit.AuditContext;
import com.wallet.domain.idempotency.IdempotencyContext;
import com.wallet.domain.idempotency.LedgerEntryIdempotency;
import com.wallet.domain.ledger.LedgerEntryTypes;
import com.wallet.domain.ledger.LedgerLine;
import com.wallet.infrastructure.exception.IdempotencyKeyConflictException;
import com.wallet.infrastructure.exception.InsufficientFundsException;
import com.wallet.infrastructure.idempotency.IdempotencyFingerprintPolicy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BatchTransferCommandHandler {

    private final LedgerRepository ledgerRepository;
    private final UserQueryService userQueryService;
    private final WalletCommandAudit commandAudit;

    public BatchTransferCommandHandler(
        LedgerRepository ledgerRepository,
        UserQueryService userQueryService,
        WalletCommandAudit commandAudit
    ) {
        this.ledgerRepository = ledgerRepository;
        this.userQueryService = userQueryService;
        this.commandAudit = commandAudit;
    }

    @Transactional
    public BatchTransferResult batchTransfer(
        UUID senderId,
        List<BatchItem> items,
        IdempotencyContext idempotencyContext,
        AuditContext audit
    ) {
        String scopedIdempotencyKey = idempotencyContext.scopedKey();
        String fingerprint = idempotencyContext.fingerprint().value();

        Optional<LedgerEntryIdempotency> existing = ledgerRepository.findIdempotencyByScopedKey(scopedIdempotencyKey);
        if (existing.isPresent()) {
            boolean fingerprintMatches = IdempotencyFingerprintPolicy.storedMatchesRequest(
                existing.get().idempotencyFingerprint(), fingerprint
            );
            if (!fingerprintMatches) {
                BatchTransferResult result = new BatchTransferResult.IdempotencyKeyConflict(
                    WalletIdempotency.IDEMPOTENCY_KEY_CONFLICT_MESSAGE
                );
                commandAudit.recordBatchTransferOutcome(result, audit, idempotencyContext, items.size());
                return result;
            }
            BatchTransferResult.Success replay = new BatchTransferResult.Success(existing.get().ledgerEntryId());
            commandAudit.recordBatchTransferIdempotencyReplay(replay, audit, idempotencyContext, items.size());
            return replay;
        }

        if (!userQueryService.existsById(senderId)) {
            BatchTransferResult result = new BatchTransferResult.UserNotFound("Sender not found: " + senderId);
            commandAudit.recordBatchTransferOutcome(result, audit, idempotencyContext, items.size());
            return result;
        }

        if (items == null || items.isEmpty()) {
            BatchTransferResult result = new BatchTransferResult.InvalidBatch("Batch must contain at least one transfer");
            commandAudit.recordBatchTransferOutcome(result, audit, idempotencyContext, 0);
            return result;
        }

        for (BatchItem item : items) {
            if (item.amount().compareTo(BigDecimal.ZERO) <= 0) {
                BatchTransferResult result = new BatchTransferResult.InvalidBatch("All amounts must be positive");
                commandAudit.recordBatchTransferOutcome(result, audit, idempotencyContext, items.size());
                return result;
            }
            if (senderId.equals(item.toUserId())) {
                BatchTransferResult result = new BatchTransferResult.InvalidBatch("Cannot transfer to self");
                commandAudit.recordBatchTransferOutcome(result, audit, idempotencyContext, items.size());
                return result;
            }
            if (!userQueryService.existsById(item.toUserId())) {
                BatchTransferResult result = new BatchTransferResult.UserNotFound("Recipient not found: " + item.toUserId());
                commandAudit.recordBatchTransferOutcome(result, audit, idempotencyContext, items.size());
                return result;
            }
        }

        Map<String, BigDecimal> totalDebitByCurrency = new LinkedHashMap<>();
        for (BatchItem item : items) {
            String currencyCode = item.currency().name();
            BigDecimal currentTotal = totalDebitByCurrency.get(currencyCode);
            if (currentTotal == null) {
                totalDebitByCurrency.put(currencyCode, item.amount());
            } else {
                totalDebitByCurrency.put(currencyCode, currentTotal.add(item.amount()));
            }
        }

        List<LedgerLine> lines = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> debit : totalDebitByCurrency.entrySet()) {
            lines.add(LedgerLine.available(senderId, debit.getKey(), debit.getValue().negate()));
        }
        for (BatchItem item : items) {
            lines.add(LedgerLine.pending(item.toUserId(), item.currency().name(), item.amount()));
        }

        try {
            UUID ledgerEntryId = ledgerRepository.postLedgerEntry(
                scopedIdempotencyKey,
                LedgerEntryTypes.BATCH_TRANSFER,
                lines,
                Map.of("recipientCount", items.size(), "senderId", senderId.toString()),
                fingerprint,
                audit.correlationId()
            ).ledgerEntryId();
            BatchTransferResult successResult = new BatchTransferResult.Success(ledgerEntryId);
            commandAudit.recordBatchTransferOutcome(successResult, audit, idempotencyContext, items.size());
            return successResult;
        } catch (InsufficientFundsException insufficientFundsException) {
            BatchTransferResult result = new BatchTransferResult.InsufficientFunds(insufficientFundsException.getMessage());
            commandAudit.recordBatchTransferOutcome(result, audit, idempotencyContext, items.size());
            return result;
        } catch (IdempotencyKeyConflictException idempotencyConflictException) {
            BatchTransferResult result = new BatchTransferResult.IdempotencyKeyConflict(idempotencyConflictException.getMessage());
            commandAudit.recordBatchTransferOutcome(result, audit, idempotencyContext, items.size());
            return result;
        }
    }

    public record BatchItem(UUID toUserId, BigDecimal amount, SupportedCurrency currency) {}
}
