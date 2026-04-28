package com.wallet.application.command.withdrawal;

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
import com.wallet.application.port.out.PayoutOutboxRepository;
import com.wallet.application.result.WithdrawResult;
import com.wallet.application.service.UserQueryService;
import com.wallet.domain.SupportedCurrency;
import com.wallet.domain.audit.AuditContext;
import com.wallet.domain.idempotency.IdempotencyContext;
import com.wallet.domain.idempotency.IdempotencyFingerprint;
import com.wallet.domain.idempotency.LedgerEntryIdempotency;
import com.wallet.infrastructure.exception.InsufficientFundsException;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("Withdrawal Command Handler")
class WithdrawalCommandHandlerTest {

    @Mock
    private LedgerRepository ledgerRepository;

    @Mock
    private PayoutOutboxRepository payoutOutboxRepository;

    @Mock
    private UserQueryService userQueryService;

    @Mock
    private WalletCommandAudit commandAudit;

    private WithdrawalCommandHandler handler;

    @BeforeEach
    void setUp() {
        handler = new WithdrawalCommandHandler(
            ledgerRepository, payoutOutboxRepository, userQueryService, commandAudit
        );
    }

    private static IdempotencyContext withdrawContext(UUID userId, BigDecimal amount, String currency, String headerKey) {
        return IdempotencyContext.scopedForUser(userId, headerKey,
            IdempotencyFingerprint.ofDepositOrWithdraw(amount, currency));
    }

    private static AuditContext audit(UUID userId) {
        return new AuditContext("corr-test", userId);
    }

    @Nested
    @DisplayName("withdraw")
    class WithdrawTests {

        @Test
        @DisplayName("Should reject when user does not exist")
        void rejectsWhenUserMissing() {
            UUID userId = UUID.randomUUID();
            BigDecimal amount = new BigDecimal("10.00");
            IdempotencyContext ctx = withdrawContext(userId, amount, "USD", "idem-1");

            when(userQueryService.existsById(userId)).thenReturn(false);

            WithdrawResult result = handler.withdraw(userId, SupportedCurrency.USD, amount, ctx, audit(userId));

            assertThat(result).isInstanceOf(WithdrawResult.UserNotFound.class);
            verifyNoInteractions(ledgerRepository);
            verifyNoInteractions(payoutOutboxRepository);
        }

        @Test
        @DisplayName("Should replay prior entry when idempotency key matches same fingerprint")
        void replaysWhenSameFingerprint() {
            UUID userId = UUID.randomUUID();
            UUID priorEntryId = UUID.randomUUID();
            BigDecimal amount = new BigDecimal("15.00");
            IdempotencyContext ctx = withdrawContext(userId, amount, "USD", "stable-key");

            when(userQueryService.existsById(userId)).thenReturn(true);
            when(ledgerRepository.findIdempotencyByScopedKey(ctx.scopedKey()))
                .thenReturn(Optional.of(new LedgerEntryIdempotency(priorEntryId, ctx.fingerprint().value())));

            WithdrawResult result = handler.withdraw(userId, SupportedCurrency.USD, amount, ctx, audit(userId));

            assertThat(result).isEqualTo(new WithdrawResult.Success(priorEntryId));
            verify(ledgerRepository, never()).postLedgerEntry(any(), any(), any(), any(), any(), any());
            verifyNoInteractions(payoutOutboxRepository);
        }

        @Test
        @DisplayName("Should reject with IdempotencyKeyConflict when fingerprints differ")
        void rejectsFingerprintMismatch() {
            UUID userId = UUID.randomUUID();
            BigDecimal amount = new BigDecimal("25.00");
            IdempotencyContext ctx = withdrawContext(userId, amount, "USD", "same-header");

            when(userQueryService.existsById(userId)).thenReturn(true);
            when(ledgerRepository.findIdempotencyByScopedKey(ctx.scopedKey()))
                .thenReturn(Optional.of(new LedgerEntryIdempotency(UUID.randomUUID(), "different-digest")));

            WithdrawResult result = handler.withdraw(userId, SupportedCurrency.USD, amount, ctx, audit(userId));

            assertThat(result).isInstanceOf(WithdrawResult.IdempotencyKeyConflict.class);
            verify(ledgerRepository, never()).postLedgerEntry(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Should reject non-positive amounts")
        void rejectsNonPositiveAmount() {
            UUID userId = UUID.randomUUID();
            BigDecimal amount = BigDecimal.ZERO;
            IdempotencyContext ctx = withdrawContext(userId, amount, "USD", "idem-z");

            when(userQueryService.existsById(userId)).thenReturn(true);
            when(ledgerRepository.findIdempotencyByScopedKey(any())).thenReturn(Optional.empty());

            WithdrawResult result = handler.withdraw(userId, SupportedCurrency.USD, amount, ctx, audit(userId));

            assertThat(result).isInstanceOf(WithdrawResult.InvalidAmount.class);
            verify(ledgerRepository, never()).postLedgerEntry(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Should post ledger entry and insert outbox record on success")
        void postsLedgerAndOutboxOnSuccess() {
            UUID userId = UUID.randomUUID();
            UUID newEntryId = UUID.randomUUID();
            BigDecimal amount = new BigDecimal("7.00");
            IdempotencyContext ctx = withdrawContext(userId, amount, "USD", "idem-new");

            when(userQueryService.existsById(userId)).thenReturn(true);
            when(ledgerRepository.findIdempotencyByScopedKey(any())).thenReturn(Optional.empty());
            when(ledgerRepository.postLedgerEntry(any(), any(), any(), any(), any(), any()))
                .thenReturn(new PostLedgerEntryResult.Created(newEntryId));

            WithdrawResult result = handler.withdraw(userId, SupportedCurrency.USD, amount, ctx, audit(userId));

            assertThat(result).isEqualTo(new WithdrawResult.Success(newEntryId));
            verify(payoutOutboxRepository).insert(eq(newEntryId), eq(userId), eq("USD"), eq(amount));
        }

        @Test
        @DisplayName("Should map InsufficientFundsException to InsufficientFunds result")
        void mapsInsufficientFundsException() {
            UUID userId = UUID.randomUUID();
            BigDecimal amount = new BigDecimal("999.00");
            IdempotencyContext ctx = withdrawContext(userId, amount, "USD", "idem-broke");

            when(userQueryService.existsById(userId)).thenReturn(true);
            when(ledgerRepository.findIdempotencyByScopedKey(any())).thenReturn(Optional.empty());
            when(ledgerRepository.postLedgerEntry(any(), any(), any(), any(), any(), any()))
                .thenThrow(new InsufficientFundsException("Insufficient funds"));

            WithdrawResult result = handler.withdraw(userId, SupportedCurrency.USD, amount, ctx, audit(userId));

            assertThat(result).isInstanceOf(WithdrawResult.InsufficientFunds.class);
            verifyNoInteractions(payoutOutboxRepository);
        }
    }
}
