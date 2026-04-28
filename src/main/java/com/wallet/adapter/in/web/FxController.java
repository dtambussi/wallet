package com.wallet.adapter.in.web;

import com.wallet.application.command.fx.FxCommandHandler;
import com.wallet.application.command.fx.FxCommandHandler.FxQuoteResponse;
import com.wallet.application.result.FxExchangeResult;
import com.wallet.application.result.FxQuoteResult;
import com.wallet.application.result.ResultError;
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
@RequestMapping("/users/{userId}/fx")
public class FxController {

    private final FxCommandHandler fxCommandHandler;
    private final MeterRegistry meterRegistry;
    private final OperationalSwitchPolicy operationalSwitchPolicy;

    public FxController(FxCommandHandler fxCommandHandler, MeterRegistry meterRegistry, OperationalSwitchPolicy operationalSwitchPolicy) {
        this.fxCommandHandler = fxCommandHandler;
        this.meterRegistry = meterRegistry;
        this.operationalSwitchPolicy = operationalSwitchPolicy;
    }

    @PostMapping("/quotes")
    @ResponseStatus(HttpStatus.CREATED)
    public FxQuoteResponse createQuote(
        @PathVariable UUID userId,
        @Valid @RequestBody FxQuoteRequest body
    ) {
        if (!operationalSwitchPolicy.isFxEnabled()) {
            throw new WalletApiException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_DISABLED", "FX operations are temporarily disabled");
        }
        SupportedCurrency sellCurrency = SupportedCurrency.fromCode(body.sellCurrency());
        SupportedCurrency buyCurrency = SupportedCurrency.fromCode(body.buyCurrency());
        FxQuoteResult quoteResult = fxCommandHandler.createQuote(
            userId, sellCurrency, buyCurrency, body.sellAmount(), AuditContextSupport.forPathUser(userId)
        );
        throwIfError(quoteResult.toError());
        return quoteResult.quoteResponse();
    }

    @PostMapping("/exchanges")
    @ResponseStatus(HttpStatus.CREATED)
    public LedgerEntryResponse executeExchange(
        @PathVariable UUID userId,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
        @Valid @RequestBody FxExchangeRequest body
    ) {
        if (!operationalSwitchPolicy.isFxEnabled()) {
            meterRegistry.counter("wallet_money_flow_total", "operation", "fx_exchange", "outcome", "disabled").increment();
            throw new WalletApiException(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_DISABLED", "FX operations are temporarily disabled");
        }
        UUID quoteUuid = UUID.fromString(body.quoteId());
        IdempotencyContext idempotencyContext = IdempotencyContext.scopedForUser(
            userId,
            idempotencyKey,
            IdempotencyFingerprint.ofFxExchange(quoteUuid)
        );
        FxExchangeResult exchangeResult;
        try {
            exchangeResult = fxCommandHandler.executeExchange(
                userId, quoteUuid, idempotencyContext, AuditContextSupport.forPathUser(userId)
            );
        } catch (RuntimeException e) {
            meterRegistry.counter("wallet_internal_errors_total", "operation", "fx_exchange").increment();
            throw e;
        }
        meterRegistry.counter("wallet_money_flow_total",
            "operation", "fx_exchange",
            "outcome", exchangeResult.isSuccess() ? "success" : "business_reject"
        ).increment();
        throwIfError(exchangeResult.toError());
        return new LedgerEntryResponse(exchangeResult.ledgerEntryId().toString());
    }

    private static void throwIfError(java.util.Optional<ResultError> error) {
        error.ifPresent(e -> { throw new WalletApiException(HttpStatus.valueOf(e.httpStatus()), e.errorCode(), e.message()); });
    }

    public record FxQuoteRequest(
        @NotBlank @Schema(example = "USD") String sellCurrency,
        @NotBlank @Schema(example = "ARS") String buyCurrency,
        @NotNull @ValidMoneyAmount @Schema(example = "100.00") BigDecimal sellAmount
    ) {}

    public record FxExchangeRequest(
        @NotBlank @Schema(example = "a1b2c3d4-e5f6-7890-abcd-ef1234567890") String quoteId
    ) {}
}
