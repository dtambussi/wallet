package com.wallet.application.command.fx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.wallet.application.audit.WalletCommandAudit;
import com.wallet.application.port.out.FxQuoteRepository;
import com.wallet.application.port.out.FxQuoteRepository.QuoteRow;
import com.wallet.application.port.out.FxRateProvider;
import com.wallet.application.port.out.LedgerRepository;
import com.wallet.application.port.out.LedgerRepository.PostLedgerEntryResult;
import com.wallet.application.result.FxExchangeResult;
import com.wallet.application.result.FxQuoteResult;
import com.wallet.application.service.UserQueryService;
import com.wallet.domain.FxRateSnapshot;
import com.wallet.domain.SellBuyRate;
import com.wallet.domain.SupportedCurrency;
import com.wallet.domain.audit.AuditContext;
import com.wallet.domain.idempotency.IdempotencyContext;
import com.wallet.domain.idempotency.IdempotencyFingerprint;
import com.wallet.domain.idempotency.LedgerEntryIdempotency;
import com.wallet.infrastructure.config.WalletProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("FX Command Handler")
class FxCommandHandlerTest {

    @Mock private FxQuoteRepository fxQuoteRepository;
    @Mock private FxRateProvider fxRateProvider;
    @Mock private LedgerRepository ledgerRepository;
    @Mock private UserQueryService userQueryService;
    @Mock private WalletCommandAudit commandAudit;

    private FxCommandHandler handler;

    private static final WalletProperties PROPS = new WalletProperties(
        new WalletProperties.Fx(30, 0),
        new WalletProperties.Payout(3, 60000, 3600000, 2)
    );

    private static final SellBuyRate USD_ARS_RATE = SellBuyRate.of(
        SupportedCurrency.USD, SupportedCurrency.ARS, new BigDecimal("1390"));

    @BeforeEach
    void setUp() {
        handler = new FxCommandHandler(
            fxQuoteRepository, fxRateProvider, ledgerRepository, userQueryService, PROPS, commandAudit
        );
    }

    private static AuditContext audit(UUID userId) {
        return new AuditContext("corr-test", userId);
    }

    @Nested
    @DisplayName("createQuote")
    class CreateQuoteTests {

        @Test
        @DisplayName("Should return UserNotFound when user does not exist")
        void rejectsWhenUserMissing() {
            UUID userId = UUID.randomUUID();
            when(userQueryService.existsById(userId)).thenReturn(false);

            FxQuoteResult result = handler.createQuote(
                userId, SupportedCurrency.USD, SupportedCurrency.ARS,
                new BigDecimal("100.00"), audit(userId)
            );

            assertThat(result).isInstanceOf(FxQuoteResult.UserNotFound.class);
            verify(fxRateProvider, never()).rate(any(), any());
            verify(fxQuoteRepository, never()).insertQuote(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(Boolean.TYPE));
        }

        @Test
        @DisplayName("Should insert quote with snapshot fields on success")
        void insertsQuoteWithSnapshotFieldsOnSuccess() {
            UUID userId = UUID.randomUUID();
            Instant pricedAt = Instant.now().minusSeconds(5);
            FxRateSnapshot snapshot = FxRateSnapshot.live(USD_ARS_RATE, pricedAt, "MockFx");

            when(userQueryService.existsById(userId)).thenReturn(true);
            when(fxRateProvider.rate(SupportedCurrency.USD, SupportedCurrency.ARS)).thenReturn(snapshot);

            FxQuoteResult result = handler.createQuote(
                userId, SupportedCurrency.USD, SupportedCurrency.ARS,
                new BigDecimal("50.00"), audit(userId)
            );

            assertThat(result).isInstanceOf(FxQuoteResult.Success.class);
            FxQuoteResult.Success success = (FxQuoteResult.Success) result;
            assertThat(success.value().sellCurrency()).isEqualTo("USD");
            assertThat(success.value().buyCurrency()).isEqualTo("ARS");
            assertThat(success.value().buyAmount()).isEqualByComparingTo("69500.00");
            assertThat(success.value().pricingSource()).isEqualTo("MockFx");
            assertThat(success.value().servedFromStale()).isFalse();

            verify(fxQuoteRepository).insertQuote(
                any(), eq(userId), eq("USD"), eq("ARS"),
                eq(new BigDecimal("50.00")), any(),
                any(), eq(pricedAt), eq("MockFx"), eq(false)
            );
        }

        @Test
        @DisplayName("Should propagate servedFromStale flag when snapshot comes from stale cache")
        void propagatesStaleFlag() {
            UUID userId = UUID.randomUUID();
            FxRateSnapshot liveSnapshot = FxRateSnapshot.live(USD_ARS_RATE, Instant.now(), "MockFx");
            FxRateSnapshot staleSnapshot = liveSnapshot.withServedFromStale();

            when(userQueryService.existsById(userId)).thenReturn(true);
            when(fxRateProvider.rate(SupportedCurrency.USD, SupportedCurrency.ARS)).thenReturn(staleSnapshot);

            FxQuoteResult result = handler.createQuote(
                userId, SupportedCurrency.USD, SupportedCurrency.ARS,
                new BigDecimal("10.00"), audit(userId)
            );

            assertThat(result).isInstanceOf(FxQuoteResult.Success.class);
            assertThat(((FxQuoteResult.Success) result).value().servedFromStale()).isTrue();

            ArgumentCaptor<Boolean> staleCaptor = ArgumentCaptor.forClass(Boolean.class);
            verify(fxQuoteRepository).insertQuote(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), staleCaptor.capture()
            );
            assertThat(staleCaptor.getValue()).isTrue();
        }
    }

    @Nested
    @DisplayName("executeExchange")
    class ExecuteExchangeTests {

        private IdempotencyContext exchangeContext(UUID userId, UUID quoteId, String headerKey) {
            return IdempotencyContext.scopedForUser(userId, headerKey, IdempotencyFingerprint.ofFxExchange(quoteId));
        }

        @Test
        @DisplayName("Should return UserNotFound when user does not exist")
        void rejectsWhenUserMissing() {
            UUID userId = UUID.randomUUID();
            UUID quoteId = UUID.randomUUID();
            IdempotencyContext ctx = exchangeContext(userId, quoteId, "idem-1");

            when(userQueryService.existsById(userId)).thenReturn(false);

            FxExchangeResult result = handler.executeExchange(userId, quoteId, ctx, audit(userId));

            assertThat(result).isInstanceOf(FxExchangeResult.UserNotFound.class);
            verify(ledgerRepository, never()).postLedgerEntry(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Should replay prior entry when idempotency key matches")
        void replaysWhenSameFingerprint() {
            UUID userId = UUID.randomUUID();
            UUID quoteId = UUID.randomUUID();
            UUID priorEntryId = UUID.randomUUID();
            IdempotencyContext ctx = exchangeContext(userId, quoteId, "stable-key");

            when(userQueryService.existsById(userId)).thenReturn(true);
            when(ledgerRepository.findIdempotencyByScopedKey(ctx.scopedKey()))
                .thenReturn(Optional.of(new LedgerEntryIdempotency(priorEntryId, ctx.fingerprint().value())));

            FxExchangeResult result = handler.executeExchange(userId, quoteId, ctx, audit(userId));

            assertThat(result).isEqualTo(new FxExchangeResult.Success(priorEntryId));
            verify(ledgerRepository, never()).postLedgerEntry(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Should return QuoteNotFound when no open quote exists for user")
        void returnsQuoteNotFound() {
            UUID userId = UUID.randomUUID();
            UUID quoteId = UUID.randomUUID();
            IdempotencyContext ctx = exchangeContext(userId, quoteId, "idem-qnf");

            when(userQueryService.existsById(userId)).thenReturn(true);
            when(ledgerRepository.findIdempotencyByScopedKey(any())).thenReturn(Optional.empty());
            when(fxQuoteRepository.lockOpenQuoteForUser(quoteId, userId)).thenReturn(Optional.empty());
            when(fxQuoteRepository.findQuoteByIdForUser(quoteId, userId)).thenReturn(Optional.empty());

            FxExchangeResult result = handler.executeExchange(userId, quoteId, ctx, audit(userId));

            assertThat(result).isInstanceOf(FxExchangeResult.QuoteNotFound.class);
        }

        @Test
        @DisplayName("Should return QuoteExpired when quote is past its expiry")
        void returnsQuoteExpired() {
            UUID userId = UUID.randomUUID();
            UUID quoteId = UUID.randomUUID();
            IdempotencyContext ctx = exchangeContext(userId, quoteId, "idem-qexp");
            QuoteRow expiredQuote = new QuoteRow(
                quoteId, userId, "USD", "ARS",
                new BigDecimal("100.00"), new BigDecimal("139000.00"),
                Instant.now().minusSeconds(60), null,
                Instant.now().minusSeconds(120), "MockFx", false
            );

            when(userQueryService.existsById(userId)).thenReturn(true);
            when(ledgerRepository.findIdempotencyByScopedKey(any())).thenReturn(Optional.empty());
            when(fxQuoteRepository.lockOpenQuoteForUser(quoteId, userId)).thenReturn(Optional.empty());
            when(fxQuoteRepository.findQuoteByIdForUser(quoteId, userId)).thenReturn(Optional.of(expiredQuote));

            FxExchangeResult result = handler.executeExchange(userId, quoteId, ctx, audit(userId));

            assertThat(result).isInstanceOf(FxExchangeResult.QuoteExpired.class);
        }

        @Test
        @DisplayName("Should post ledger entry and mark quote consumed on success")
        void postsLedgerAndConsumesQuoteOnSuccess() {
            UUID userId = UUID.randomUUID();
            UUID quoteId = UUID.randomUUID();
            UUID newEntryId = UUID.randomUUID();
            IdempotencyContext ctx = exchangeContext(userId, quoteId, "idem-ok");
            QuoteRow openQuote = new QuoteRow(
                quoteId, userId, "USD", "ARS",
                new BigDecimal("50.00"), new BigDecimal("69500.00"),
                Instant.now().plusSeconds(30), null,
                Instant.now().minusSeconds(2), "MockFx", false
            );

            when(userQueryService.existsById(userId)).thenReturn(true);
            when(ledgerRepository.findIdempotencyByScopedKey(any())).thenReturn(Optional.empty());
            when(fxQuoteRepository.lockOpenQuoteForUser(quoteId, userId)).thenReturn(Optional.of(openQuote));
            when(ledgerRepository.postLedgerEntry(any(), any(), any(), any(), any(), any()))
                .thenReturn(new PostLedgerEntryResult.Created(newEntryId));

            FxExchangeResult result = handler.executeExchange(userId, quoteId, ctx, audit(userId));

            assertThat(result).isEqualTo(new FxExchangeResult.Success(newEntryId));
            verify(fxQuoteRepository).markQuoteConsumed(quoteId);
        }
    }
}
