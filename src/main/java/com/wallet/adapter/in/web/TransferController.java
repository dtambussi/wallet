package com.wallet.adapter.in.web;

import com.wallet.application.command.transfer.TransferCommandHandler;
import com.wallet.application.result.ResultError;
import com.wallet.application.result.TransferResult;
import com.wallet.domain.SupportedCurrency;
import com.wallet.domain.idempotency.IdempotencyContext;
import com.wallet.domain.idempotency.IdempotencyFingerprint;
import com.wallet.infrastructure.audit.AuditContextSupport;
import com.wallet.infrastructure.config.OperationalSwitchPolicy;
import com.wallet.infrastructure.exception.WalletApiException;
import com.wallet.infrastructure.validation.ValidMoneyAmount;
import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
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
@RequestMapping("/users/{userId}/transfers")
public class TransferController {

    private final TransferCommandHandler transferCommandHandler;
    private final MeterRegistry meterRegistry;
    private final OperationalSwitchPolicy operationalSwitchPolicy;

    public TransferController(TransferCommandHandler transferCommandHandler, MeterRegistry meterRegistry, OperationalSwitchPolicy operationalSwitchPolicy) {
        this.transferCommandHandler = transferCommandHandler;
        this.meterRegistry = meterRegistry;
        this.operationalSwitchPolicy = operationalSwitchPolicy;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LedgerEntryResponse transfer(
        @PathVariable UUID userId,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @Valid @RequestBody TransferRequest body
    ) {
        SupportedCurrency fromCurrency = SupportedCurrency.fromCode(body.currency());
        // toCurrency defaults to currency (from) when absent — backwards-compatible same-currency transfer.
        String rawToCurrency = body.toCurrency() != null ? body.toCurrency() : body.currency();
        boolean isCrossCurrency = !rawToCurrency.equals(body.currency());
        if (isCrossCurrency && !operationalSwitchPolicy.isFxEnabled()) {
            meterRegistry.counter("wallet_money_flow_total", "operation", "transfer", "outcome", "disabled").increment();
            throw new WalletApiException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_DISABLED", "FX operations are temporarily disabled");
        }
        SupportedCurrency toCurrency = SupportedCurrency.fromCode(rawToCurrency);
        UUID toUser = UUID.fromString(body.toUserId());
        IdempotencyContext idempotencyContext = IdempotencyContext.scopedForUser(
            userId,
            idempotencyKey,
            IdempotencyFingerprint.ofTransfer(toUser, body.amount(), body.currency(), rawToCurrency)
        );
        TransferResult transferResult;
        try {
            transferResult = transferCommandHandler.transfer(
                userId,
                toUser,
                fromCurrency,
                toCurrency,
                body.amount(),
                idempotencyContext,
                AuditContextSupport.forPathUser(userId)
            );
        } catch (RuntimeException e) {
            meterRegistry.counter("wallet_internal_errors_total", "operation", "transfer").increment();
            throw e;
        }
        meterRegistry.counter("wallet_money_flow_total",
            "operation", "transfer",
            "outcome", transferResult.isSuccess() ? "success" : "business_reject"
        ).increment();
        throwIfError(transferResult.toError());
        return new LedgerEntryResponse(transferResult.ledgerEntryId().toString());
    }

    private static void throwIfError(java.util.Optional<ResultError> error) {
        error.ifPresent(e -> { throw new WalletApiException(HttpStatus.valueOf(e.httpStatus()), e.errorCode(), e.message()); });
    }

    public record TransferRequest(
        @NotBlank @Schema(example = "660e8400-e29b-41d4-a716-446655440099") String toUserId,
        @NotNull @ValidMoneyAmount @Schema(example = "25.00") BigDecimal amount,
        @NotBlank @Schema(example = "USD") String currency,
        @Schema(
            example = "ARS",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED,
            description = "Optional; omit for same-currency transfer"
        )
        String toCurrency
    ) {}
}
