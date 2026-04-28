package com.wallet.adapter.in.web;

import com.wallet.application.command.batch.BatchTransferCommandHandler;
import com.wallet.application.command.batch.BatchTransferCommandHandler.BatchItem;
import com.wallet.application.result.BatchTransferResult;
import com.wallet.application.result.ResultError;
import com.wallet.domain.SupportedCurrency;
import com.wallet.domain.idempotency.IdempotencyContext;
import com.wallet.domain.idempotency.IdempotencyFingerprint;
import com.wallet.infrastructure.audit.AuditContextSupport;
import com.wallet.infrastructure.exception.WalletApiException;
import com.wallet.infrastructure.validation.ValidMoneyAmount;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/users/{userId}/batch-transfers")
public class BatchTransferController {

    private final BatchTransferCommandHandler batchTransferCommandHandler;

    public BatchTransferController(BatchTransferCommandHandler batchTransferCommandHandler) {
        this.batchTransferCommandHandler = batchTransferCommandHandler;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LedgerEntryResponse batchTransfer(
        @PathVariable UUID userId,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @Valid @RequestBody BatchTransferRequest body
    ) {
        List<BatchItem> items = body.transfers().stream()
            .map(transferItem -> new BatchItem(
                UUID.fromString(transferItem.toUserId()),
                transferItem.amount(),
                SupportedCurrency.fromCode(transferItem.currency())
            ))
            .toList();

        // Fingerprint is a hash of sorted (toUserId, amount, currency) tuples so order doesn't matter.
        List<String> fingerprintLines = body.transfers().stream()
            .map(transferItem -> transferItem.toUserId() + "|" + transferItem.amount().stripTrailingZeros().toPlainString() + "|" + transferItem.currency().toUpperCase())
            .toList();
        IdempotencyContext idempotencyContext = IdempotencyContext.scopedForUser(
            userId,
            idempotencyKey,
            IdempotencyFingerprint.ofLines(fingerprintLines)
        );

        BatchTransferResult result = batchTransferCommandHandler.batchTransfer(
            userId, items, idempotencyContext, AuditContextSupport.forPathUser(userId)
        );
        throwIfError(result.toError());
        return new LedgerEntryResponse(result.ledgerEntryId().toString());
    }

    private static void throwIfError(java.util.Optional<ResultError> error) {
        error.ifPresent(e -> { throw new WalletApiException(HttpStatus.valueOf(e.httpStatus()), e.errorCode(), e.message()); });
    }

    @Schema(description = "Batch of recipient credits from one sender debit")
    public record BatchTransferRequest(@Valid @NotEmpty List<TransferItem> transfers) {

        public record TransferItem(
            @NotBlank @Schema(example = "660e8400-e29b-41d4-a716-446655440099") String toUserId,
            @NotNull @ValidMoneyAmount @Schema(example = "10.00") BigDecimal amount,
            @NotBlank @Schema(example = "USD") String currency
        ) {}
    }
}
