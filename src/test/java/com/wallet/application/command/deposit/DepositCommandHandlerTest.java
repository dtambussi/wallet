package com.wallet.application.command.deposit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.wallet.application.audit.WalletCommandAudit;
import com.wallet.application.port.out.LedgerRepository;
import com.wallet.application.port.out.LedgerRepository.PostLedgerEntryResult;
import com.wallet.application.result.DepositResult;
import com.wallet.application.service.UserQueryService;
import com.wallet.domain.SupportedCurrency;
import com.wallet.domain.audit.AuditContext;
import com.wallet.domain.idempotency.IdempotencyContext;
import com.wallet.domain.idempotency.IdempotencyFingerprint;
import com.wallet.domain.idempotency.LedgerEntryIdempotency;
import com.wallet.domain.ledger.LedgerLine;
import com.wallet.domain.ledger.LedgerEntryTypes;
import com.wallet.infrastructure.exception.IdempotencyKeyConflictException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
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
@DisplayName("Deposit Command Handler")
class DepositCommandHandlerTest {

    @Mock
    private LedgerRepository ledgerRepository;

    @Mock
    private UserQueryService userQueryService;

    @Mock
    private WalletCommandAudit commandAudit;

    private DepositCommandHandler depositCommandHandler;

    @BeforeEach
    void setUp() {
        depositCommandHandler = new DepositCommandHandler(ledgerRepository, userQueryService, commandAudit);
    }

    private static IdempotencyContext depositContext(UUID userId, BigDecimal amount, String currencyCode, String headerKey) {
        IdempotencyFingerprint fingerprint = IdempotencyFingerprint.ofDepositOrWithdraw(amount, currencyCode);
        return IdempotencyContext.scopedForUser(userId, headerKey, fingerprint);
    }

    private static AuditContext audit(UUID userId) {
        return new AuditContext("corr-test", userId);
    }

    @Nested
    @DisplayName("deposit")
    class DepositTests {

        @Test
        @DisplayName("Should reject when wallet user does not exist")
        void rejectsWhenUserMissing() {
            UUID userId = UUID.randomUUID();
            BigDecimal amount = new BigDecimal("10.00");
            IdempotencyContext ctx = depositContext(userId, amount, "USD", "idem-1");

            when(userQueryService.existsById(userId)).thenReturn(false);

            DepositResult result =
                depositCommandHandler.deposit(userId, SupportedCurrency.USD, amount, ctx, audit(userId));

            assertThat(result).isInstanceOf(DepositResult.UserNotFound.class);
            verifyNoInteractions(ledgerRepository);
            verify(commandAudit).recordDepositAttemptOutcome(eq(result), eq(audit(userId)), eq(ctx), eq("USD"), eq(amount));
        }

        @Test
        @DisplayName("Should replay prior ledger entry when idempotency key matches same fingerprint")
        void replaysWhenSameFingerprint() {
            UUID userId = UUID.randomUUID();
            UUID priorEntryId = UUID.randomUUID();
            BigDecimal amount = new BigDecimal("25.00");
            IdempotencyFingerprint fingerprint = IdempotencyFingerprint.ofDepositOrWithdraw(amount, "USD");
            IdempotencyContext ctx = IdempotencyContext.scopedForUser(userId, "stable-key", fingerprint);

            when(userQueryService.existsById(userId)).thenReturn(true);
            when(ledgerRepository.findIdempotencyByScopedKey(ctx.scopedKey()))
                .thenReturn(Optional.of(new LedgerEntryIdempotency(priorEntryId, fingerprint.value())));

            DepositResult result =
                depositCommandHandler.deposit(userId, SupportedCurrency.USD, amount, ctx, audit(userId));

            assertThat(result).isEqualTo(new DepositResult.Success(priorEntryId));
            verify(commandAudit).recordDepositAttemptIdempotencyReplay(
                eq(new DepositResult.Success(priorEntryId)),
                eq(audit(userId)),
                eq("USD"),
                eq(amount),
                eq(ctx.scopedKey())
            );
            verify(ledgerRepository, never()).postLedgerEntry(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Should reject idempotency conflict when fingerprint differs from stored row")
        void rejectsFingerprintMismatch() {
            UUID userId = UUID.randomUUID();
            BigDecimal amount = new BigDecimal("25.00");
            IdempotencyContext ctx = depositContext(userId, amount, "USD", "same-header");

            when(userQueryService.existsById(userId)).thenReturn(true);
            when(ledgerRepository.findIdempotencyByScopedKey(ctx.scopedKey()))
                .thenReturn(Optional.of(new LedgerEntryIdempotency(UUID.randomUUID(), "different-digest")));

            DepositResult result =
                depositCommandHandler.deposit(userId, SupportedCurrency.USD, amount, ctx, audit(userId));

            assertThat(result).isInstanceOf(DepositResult.IdempotencyKeyConflict.class);
            verify(ledgerRepository, never()).postLedgerEntry(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Should reject non-positive amounts")
        void rejectsNonPositiveAmount() {
            UUID userId = UUID.randomUUID();
            BigDecimal amount = BigDecimal.ZERO;
            IdempotencyContext ctx = depositContext(userId, amount, "USD", "idem-z");

            when(userQueryService.existsById(userId)).thenReturn(true);
            when(ledgerRepository.findIdempotencyByScopedKey(any())).thenReturn(Optional.empty());

            DepositResult result =
                depositCommandHandler.deposit(userId, SupportedCurrency.USD, amount, ctx, audit(userId));

            assertThat(result).isInstanceOf(DepositResult.InvalidAmount.class);
            verify(ledgerRepository, never()).postLedgerEntry(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Should post ledger entry on first successful deposit")
        void postsLedgerEntryOnSuccess() {
            UUID userId = UUID.randomUUID();
            UUID newEntryId = UUID.randomUUID();
            BigDecimal amount = new BigDecimal("100.00");
            IdempotencyContext ctx = depositContext(userId, amount, "ARS", "idem-new");

            when(userQueryService.existsById(userId)).thenReturn(true);
            when(ledgerRepository.findIdempotencyByScopedKey(any())).thenReturn(Optional.empty());
            when(ledgerRepository.postLedgerEntry(
                eq(ctx.scopedKey()),
                eq(LedgerEntryTypes.DEPOSIT),
                any(),
                eq(Map.of("currency", "ARS")),
                eq(ctx.fingerprint().value()),
                eq("corr-test")
            )).thenReturn(new PostLedgerEntryResult.Created(newEntryId));

            DepositResult result =
                depositCommandHandler.deposit(userId, SupportedCurrency.ARS, amount, ctx, audit(userId));

            assertThat(result).isEqualTo(new DepositResult.Success(newEntryId));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<LedgerLine>> linesCaptor = ArgumentCaptor.forClass(List.class);
            verify(ledgerRepository).postLedgerEntry(
                eq(ctx.scopedKey()),
                eq(LedgerEntryTypes.DEPOSIT),
                linesCaptor.capture(),
                eq(Map.of("currency", "ARS")),
                eq(ctx.fingerprint().value()),
                eq("corr-test")
            );
            assertThat(linesCaptor.getValue()).hasSize(1);

            verify(commandAudit).recordDepositAttemptOutcome(
                eq(new DepositResult.Success(newEntryId)),
                eq(audit(userId)),
                eq(ctx),
                eq("ARS"),
                eq(amount)
            );
        }

        @Test
        @DisplayName("Should map ledger idempotency conflict to deposit conflict result")
        void mapsPostConflictToResult() {
            UUID userId = UUID.randomUUID();
            BigDecimal amount = new BigDecimal("1.00");
            IdempotencyContext ctx = depositContext(userId, amount, "USD", "idem-conflict");

            when(userQueryService.existsById(userId)).thenReturn(true);
            when(ledgerRepository.findIdempotencyByScopedKey(any())).thenReturn(Optional.empty());
            when(ledgerRepository.postLedgerEntry(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IdempotencyKeyConflictException("race"));

            DepositResult result =
                depositCommandHandler.deposit(userId, SupportedCurrency.USD, amount, ctx, audit(userId));

            assertThat(result).isInstanceOf(DepositResult.IdempotencyKeyConflict.class);
        }
    }
}
