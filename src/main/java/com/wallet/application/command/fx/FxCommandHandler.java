package com.wallet.application.command.fx;

import com.wallet.application.audit.WalletCommandAudit;
import com.wallet.application.port.out.FxQuoteRepository;
import com.wallet.application.port.out.FxQuoteRepository.QuoteRow;
import com.wallet.application.port.out.FxRateProvider;
import com.wallet.application.port.out.LedgerRepository;
import com.wallet.application.result.FxExchangeResult;
import com.wallet.application.result.FxQuoteResult;
import com.wallet.application.service.UserQueryService;
import com.wallet.domain.FxRateSnapshot;
import com.wallet.domain.SupportedCurrency;
import com.wallet.domain.WalletIdempotency;
import com.wallet.domain.audit.AuditContext;
import com.wallet.domain.idempotency.IdempotencyContext;
import com.wallet.domain.idempotency.LedgerEntryIdempotency;
import com.wallet.domain.ledger.LedgerEntryTypes;
import com.wallet.domain.ledger.LedgerLine;
import com.wallet.domain.money.MoneyScale;
import com.wallet.infrastructure.config.WalletProperties;
import com.wallet.infrastructure.exception.IdempotencyKeyConflictException;
import com.wallet.infrastructure.exception.InsufficientFundsException;
import com.wallet.infrastructure.id.UuidV7Generator;
import com.wallet.infrastructure.idempotency.IdempotencyFingerprintPolicy;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FxCommandHandler {

    private final FxQuoteRepository fxQuoteRepository;
    private final FxRateProvider fxRateProvider;
    private final LedgerRepository ledgerRepository;
    private final UserQueryService userQueryService;
    private final WalletProperties walletProperties;
    private final WalletCommandAudit commandAudit;

    public FxCommandHandler(
        FxQuoteRepository fxQuoteRepository,
        FxRateProvider fxRateProvider,
        LedgerRepository ledgerRepository,
        UserQueryService userQueryService,
        WalletProperties walletProperties,
        WalletCommandAudit commandAudit
    ) {
        this.fxQuoteRepository = fxQuoteRepository;
        this.fxRateProvider = fxRateProvider;
        this.ledgerRepository = ledgerRepository;
        this.userQueryService = userQueryService;
        this.walletProperties = walletProperties;
        this.commandAudit = commandAudit;
    }

    @Transactional
    public FxQuoteResult createQuote(
        UUID userId,
        SupportedCurrency sellCurrency,
        SupportedCurrency buyCurrency,
        BigDecimal sellAmount,
        AuditContext audit
    ) {
        if (!userQueryService.existsById(userId)) {
            FxQuoteResult result = new FxQuoteResult.UserNotFound("User not found: " + userId);
            commandAudit.recordFxQuoteAttemptOutcome(result, audit, sellAmount);
            return result;
        }
        if (sellAmount.compareTo(BigDecimal.ZERO) <= 0) {
            FxQuoteResult result = new FxQuoteResult.InvalidSellAmount("sellAmount must be positive");
            commandAudit.recordFxQuoteAttemptOutcome(result, audit, sellAmount);
            return result;
        }
        FxRateSnapshot rateSnapshot = fxRateProvider.rate(sellCurrency, buyCurrency);
        BigDecimal buyAmount = MoneyScale.round(sellAmount.multiply(rateSnapshot.buyUnitsPerOneSell()));
        UUID quoteId = UuidV7Generator.next();
        Instant expiresAt = Instant.now().plusSeconds(walletProperties.fx().quoteTtlSeconds());
        fxQuoteRepository.insertQuote(
            quoteId, userId, sellCurrency.name(), buyCurrency.name(), sellAmount, buyAmount, expiresAt,
            rateSnapshot.pricedAt(), rateSnapshot.source(), rateSnapshot.servedFromStaleCache()
        );
        FxQuoteResult result = new FxQuoteResult.Success(
            new FxQuoteResponse(
                quoteId.toString(), sellCurrency.name(), buyCurrency.name(), sellAmount, buyAmount, expiresAt.toString(),
                rateSnapshot.pricedAt().toString(), rateSnapshot.source(), rateSnapshot.servedFromStaleCache()
            )
        );
        commandAudit.recordFxQuoteAttemptOutcome(result, audit, sellAmount);
        return result;
    }

    @Transactional
    public FxExchangeResult executeExchange(
        UUID userId, UUID quoteId, IdempotencyContext idempotencyContext, AuditContext audit
    ) {
        if (!userQueryService.existsById(userId)) {
            return recordAndReturnExchangeBusinessReject(
                new FxExchangeResult.UserNotFound("User not found: " + userId),
                audit,
                idempotencyContext,
                quoteId
            );
        }
        String scopedIdempotencyKey = idempotencyContext.scopedKey();
        String fingerprint = idempotencyContext.fingerprint().value();

        ExchangeValidationResult idempotencyValidationResult = validateExchangeIdempotency(
            scopedIdempotencyKey, fingerprint, audit, idempotencyContext, quoteId
        );
        if (!idempotencyValidationResult.shouldContinue()) return idempotencyValidationResult.result();

        LockedQuoteResolution lockedQuoteResolution = resolveLockedQuoteForExchange(userId, quoteId, audit, idempotencyContext);
        if (lockedQuoteResolution.businessReject() != null) {
            return lockedQuoteResolution.businessReject();
        }
        QuoteRow lockedQuoteRow = lockedQuoteResolution.lockedQuote();
        try {
            UUID ledgerEntryId = ledgerRepository.postLedgerEntry(
                scopedIdempotencyKey,
                LedgerEntryTypes.FX_EXCHANGE,
                List.of(
                    LedgerLine.available(userId, lockedQuoteRow.sellCurrency(), lockedQuoteRow.sellAmount().negate()),
                    LedgerLine.available(userId, lockedQuoteRow.buyCurrency(), lockedQuoteRow.buyAmount())
                ),
                Map.of("quoteId", quoteId.toString()),
                fingerprint,
                audit.correlationId()
            ).ledgerEntryId();
            fxQuoteRepository.markQuoteConsumed(quoteId);
            FxExchangeResult successResult = new FxExchangeResult.Success(ledgerEntryId);
            commandAudit.recordFxExchangeAttemptOutcome(
                successResult, audit, idempotencyContext, quoteId
            );
            return successResult;
        } catch (InsufficientFundsException insufficientFundsException) {
            FxExchangeResult result = new FxExchangeResult.InsufficientFunds(
                insufficientFundsException.getMessage()
            );
            commandAudit.recordFxExchangeAttemptOutcome(
                result, audit, idempotencyContext, quoteId
            );
            return result;
        } catch (IdempotencyKeyConflictException idempotencyConflictException) {
            FxExchangeResult result = new FxExchangeResult.IdempotencyKeyConflict(idempotencyConflictException.getMessage());
            commandAudit.recordFxExchangeAttemptOutcome(
                result, audit, idempotencyContext, quoteId
            );
            return result;
        }
    }

    private ExchangeValidationResult validateExchangeIdempotency(
        String scopedIdempotencyKey,
        String fingerprint,
        AuditContext audit,
        IdempotencyContext idempotencyContext,
        UUID quoteId
    ) {
        Optional<LedgerEntryIdempotency> existingLedgerIdempotency = ledgerRepository
            .findIdempotencyByScopedKey(scopedIdempotencyKey);
        if (existingLedgerIdempotency.isEmpty()) {
            return ExchangeValidationResult.continueFlow();
        }
        LedgerEntryIdempotency priorLedgerIdempotency = existingLedgerIdempotency.get();
        boolean fingerprintMatches = IdempotencyFingerprintPolicy.storedMatchesRequest(
            priorLedgerIdempotency.idempotencyFingerprint(), fingerprint
        );
        if (!fingerprintMatches) {
            return ExchangeValidationResult.returnResult(recordAndReturnExchangeBusinessReject(
                new FxExchangeResult.IdempotencyKeyConflict(WalletIdempotency.IDEMPOTENCY_KEY_CONFLICT_MESSAGE),
                audit,
                idempotencyContext,
                quoteId
            ));
        }
        FxExchangeResult.Success replayResult = new FxExchangeResult.Success(priorLedgerIdempotency.ledgerEntryId());
        commandAudit.recordFxExchangeAttemptIdempotencyReplay(
            replayResult, audit, scopedIdempotencyKey, quoteId
        );
        return ExchangeValidationResult.returnResult(replayResult);
    }

    private LockedQuoteResolution resolveLockedQuoteForExchange(
        UUID userId,
        UUID quoteId,
        AuditContext audit,
        IdempotencyContext idempotencyContext
    ) {
        Optional<QuoteRow> lockedQuote = fxQuoteRepository.lockOpenQuoteForUser(quoteId, userId);
        if (lockedQuote.isPresent()) {
            return new LockedQuoteResolution(lockedQuote.get(), null);
        }

        Optional<QuoteRow> quoteForUser = fxQuoteRepository.findQuoteByIdForUser(quoteId, userId);
        if (quoteForUser.isEmpty()) {
            return new LockedQuoteResolution(
                null,
                recordAndReturnExchangeBusinessReject(
                    new FxExchangeResult.QuoteNotFound("FX quote not found"),
                    audit,
                    idempotencyContext,
                    quoteId
                )
            );
        }
        QuoteRow existingQuote = quoteForUser.get();
        if (existingQuote.consumedAt() != null) {
            return new LockedQuoteResolution(
                null,
                recordAndReturnExchangeBusinessReject(
                    new FxExchangeResult.QuoteUsed("Quote already consumed"),
                    audit,
                    idempotencyContext,
                    quoteId
                )
            );
        }
        if (existingQuote.expiresAt().isBefore(Instant.now())) {
            return new LockedQuoteResolution(
                null,
                recordAndReturnExchangeBusinessReject(
                    new FxExchangeResult.QuoteExpired("FX quote expired"),
                    audit,
                    idempotencyContext,
                    quoteId
                )
            );
        }
        return new LockedQuoteResolution(
            null,
            recordAndReturnExchangeBusinessReject(
                new FxExchangeResult.QuoteUnavailable("Quote cannot be executed"),
                audit,
                idempotencyContext,
                quoteId
            )
        );
    }

    private FxExchangeResult recordAndReturnExchangeBusinessReject(
        FxExchangeResult businessRejectResult,
        AuditContext audit,
        IdempotencyContext idempotencyContext,
        UUID quoteId
    ) {
        commandAudit.recordFxExchangeAttemptOutcome(
            businessRejectResult, audit, idempotencyContext, quoteId
        );
        return businessRejectResult;
    }

    public record FxQuoteResponse(
        @Schema(example = "b2c3d4e5-f6a7-8901-bcde-f12345678901") String quoteId,
        @Schema(example = "USD") String sellCurrency,
        @Schema(example = "ARS") String buyCurrency,
        @Schema(example = "100.00") BigDecimal sellAmount,
        @Schema(example = "950000.00") BigDecimal buyAmount,
        @Schema(example = "2026-01-15T12:05:00Z") String expiresAt,
        @Schema(example = "2026-01-15T12:00:00Z") String pricedAt,
        @Schema(example = "MockFx") String pricingSource,
        @Schema(example = "false") boolean servedFromStale
    ) {}

    private record LockedQuoteResolution(QuoteRow lockedQuote, FxExchangeResult businessReject) {}

    private record ExchangeValidationResult(boolean shouldContinue, FxExchangeResult result) {
        static ExchangeValidationResult continueFlow() {
            return new ExchangeValidationResult(true, null);
        }

        static ExchangeValidationResult returnResult(FxExchangeResult result) {
            return new ExchangeValidationResult(false, result);
        }
    }
}
