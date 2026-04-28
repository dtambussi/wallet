package com.wallet.application.command.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.wallet.application.audit.WalletCommandAudit;
import com.wallet.application.port.out.FxRateProvider;
import com.wallet.application.port.out.LedgerRepository;
import com.wallet.application.port.out.LedgerRepository.PostLedgerEntryResult;
import com.wallet.application.result.TransferResult;
import com.wallet.application.service.UserQueryService;
import com.wallet.domain.FxRateSnapshot;
import com.wallet.domain.SellBuyRate;
import com.wallet.domain.SupportedCurrency;
import com.wallet.domain.audit.AuditContext;
import com.wallet.domain.idempotency.IdempotencyContext;
import com.wallet.domain.idempotency.IdempotencyFingerprint;
import com.wallet.domain.ledger.LedgerEntryTypes;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("Transfer Command Handler")
class TransferCommandHandlerTest {

    @Mock
    private LedgerRepository ledgerRepository;

    @Mock
    private UserQueryService userQueryService;

    @Mock
    private WalletCommandAudit commandAudit;

    @Mock
    private FxRateProvider fxRateProvider;

    private TransferCommandHandler transferCommandHandler;

    @BeforeEach
    void setUp() {
        transferCommandHandler = new TransferCommandHandler(
            ledgerRepository,
            userQueryService,
            commandAudit,
            fxRateProvider
        );
    }

    private static AuditContext audit(UUID subjectUserId) {
        return new AuditContext("corr-transfer", subjectUserId);
    }

    @Test
    @DisplayName("Should reject transfer to same account")
    void rejectsSelfTransfer() {
        UUID userId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("10.00");
        IdempotencyFingerprint fingerprint = IdempotencyFingerprint.ofTransfer(userId, amount, "USD", "USD");
        IdempotencyContext ctx = IdempotencyContext.scopedForUser(userId, "idem", fingerprint);

        when(userQueryService.existsById(userId)).thenReturn(true);

        TransferResult result = transferCommandHandler.transfer(
            userId,
            userId,
            SupportedCurrency.USD,
            SupportedCurrency.USD,
            amount,
            ctx,
            audit(userId)
        );

        assertThat(result).isInstanceOf(TransferResult.SameAccountTransfer.class);
        verify(ledgerRepository, never()).postLedgerEntry(any(), any(), any(), any(), any(), any());
        verifyNoInteractions(fxRateProvider);
    }

    @Test
    @DisplayName("Should reject when sender user does not exist")
    void rejectsWhenSenderMissing() {
        UUID fromUserId = UUID.randomUUID();
        UUID toUserId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("5.00");
        IdempotencyFingerprint fingerprint = IdempotencyFingerprint.ofTransfer(toUserId, amount, "USD", "USD");
        IdempotencyContext ctx = IdempotencyContext.scopedForUser(fromUserId, "idem", fingerprint);

        when(userQueryService.existsById(fromUserId)).thenReturn(false);

        TransferResult result = transferCommandHandler.transfer(
            fromUserId,
            toUserId,
            SupportedCurrency.USD,
            SupportedCurrency.USD,
            amount,
            ctx,
            audit(fromUserId)
        );

        assertThat(result).isInstanceOf(TransferResult.UserNotFound.class);
        verify(ledgerRepository, never()).postLedgerEntry(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Should post same-currency transfer when both users exist")
    void postsSameCurrencyTransfer() {
        UUID fromUserId = UUID.randomUUID();
        UUID toUserId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("7.50");
        IdempotencyFingerprint fingerprint = IdempotencyFingerprint.ofTransfer(toUserId, amount, "CLP", "CLP");
        IdempotencyContext ctx = IdempotencyContext.scopedForUser(fromUserId, "idem-clp", fingerprint);
        UUID ledgerEntryId = UUID.randomUUID();

        when(userQueryService.existsById(fromUserId)).thenReturn(true);
        when(userQueryService.existsById(toUserId)).thenReturn(true);
        when(ledgerRepository.findIdempotencyByScopedKey(any())).thenReturn(Optional.empty());
        when(ledgerRepository.postLedgerEntry(
            eq(ctx.scopedKey()),
            eq(LedgerEntryTypes.TRANSFER),
            any(),
            eq(java.util.Map.of("currency", "CLP", "toUser", toUserId.toString())),
            eq(fingerprint.value()),
            eq("corr-transfer")
        )).thenReturn(new PostLedgerEntryResult.Created(ledgerEntryId));

        TransferResult result = transferCommandHandler.transfer(
            fromUserId,
            toUserId,
            SupportedCurrency.CLP,
            SupportedCurrency.CLP,
            amount,
            ctx,
            audit(fromUserId)
        );

        assertThat(result).isEqualTo(new TransferResult.Success(ledgerEntryId));
        verifyNoInteractions(fxRateProvider);
        verify(commandAudit).recordTransferAttemptOutcome(
            eq(new TransferResult.Success(ledgerEntryId)),
            eq(audit(fromUserId)),
            eq(ctx),
            eq("CLP"),
            eq(amount),
            eq(toUserId)
        );
    }

    @Test
    @DisplayName("Should use FX snapshot for cross-currency transfer")
    void postsCrossCurrencyUsingFxSnapshot() {
        UUID fromUserId = UUID.randomUUID();
        UUID toUserId = UUID.randomUUID();
        BigDecimal amount = new BigDecimal("100.00");
        IdempotencyFingerprint fingerprint =
            IdempotencyFingerprint.ofTransfer(toUserId, amount, "USD", "ARS");
        IdempotencyContext ctx = IdempotencyContext.scopedForUser(fromUserId, "idem-fx", fingerprint);
        UUID ledgerEntryId = UUID.randomUUID();

        SellBuyRate rate = SellBuyRate.of(SupportedCurrency.USD, SupportedCurrency.ARS, new BigDecimal("950"));
        FxRateSnapshot snapshot = FxRateSnapshot.live(rate, Instant.parse("2026-04-01T12:00:00Z"), "MockFx");

        when(userQueryService.existsById(fromUserId)).thenReturn(true);
        when(userQueryService.existsById(toUserId)).thenReturn(true);
        when(ledgerRepository.findIdempotencyByScopedKey(any())).thenReturn(Optional.empty());
        when(fxRateProvider.rate(SupportedCurrency.USD, SupportedCurrency.ARS)).thenReturn(snapshot);
        when(ledgerRepository.postLedgerEntry(eq(ctx.scopedKey()), eq(LedgerEntryTypes.CROSS_CURRENCY_TRANSFER), any(), any(), eq(fingerprint.value()), eq("corr-transfer")))
            .thenReturn(new PostLedgerEntryResult.Created(ledgerEntryId));

        TransferResult result = transferCommandHandler.transfer(
            fromUserId,
            toUserId,
            SupportedCurrency.USD,
            SupportedCurrency.ARS,
            amount,
            ctx,
            audit(fromUserId)
        );

        assertThat(result).isEqualTo(new TransferResult.Success(ledgerEntryId));
        verify(fxRateProvider).rate(SupportedCurrency.USD, SupportedCurrency.ARS);
    }
}
