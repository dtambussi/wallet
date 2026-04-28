package com.wallet.application.command.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.wallet.application.audit.WalletCommandAudit;
import com.wallet.application.command.batch.BatchTransferCommandHandler.BatchItem;
import com.wallet.application.port.out.LedgerRepository;
import com.wallet.application.port.out.LedgerRepository.PostLedgerEntryResult;
import com.wallet.application.result.BatchTransferResult;
import com.wallet.application.service.UserQueryService;
import com.wallet.domain.SupportedCurrency;
import com.wallet.domain.audit.AuditContext;
import com.wallet.domain.idempotency.IdempotencyContext;
import com.wallet.domain.idempotency.IdempotencyFingerprint;
import com.wallet.domain.idempotency.LedgerEntryIdempotency;
import com.wallet.domain.ledger.LedgerLine;
import java.math.BigDecimal;
import java.util.List;
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
@DisplayName("Batch Transfer Command Handler")
class BatchTransferCommandHandlerTest {

    @Mock
    private LedgerRepository ledgerRepository;

    @Mock
    private UserQueryService userQueryService;

    @Mock
    private WalletCommandAudit commandAudit;

    private BatchTransferCommandHandler handler;

    @BeforeEach
    void setUp() {
        handler = new BatchTransferCommandHandler(ledgerRepository, userQueryService, commandAudit);
    }

    private static IdempotencyContext context(UUID senderId, List<String> fingerprintLines, String headerKey) {
        return IdempotencyContext.scopedForUser(senderId, headerKey, IdempotencyFingerprint.ofLines(fingerprintLines));
    }

    private static AuditContext audit(UUID userId) {
        return new AuditContext("corr-test", userId);
    }

    @Nested
    @DisplayName("batchTransfer")
    class BatchTransferTests {

        @Test
        @DisplayName("Should replay prior entry when idempotency key matches same fingerprint")
        void replaysWhenSameFingerprint() {
            UUID sender = UUID.randomUUID();
            UUID priorEntryId = UUID.randomUUID();
            UUID recipient = UUID.randomUUID();
            List<String> lines = List.of(recipient + "|50.00|USD");
            IdempotencyContext ctx = context(sender, lines, "key-1");
            List<BatchItem> items = List.of(new BatchItem(recipient, new BigDecimal("50.00"), SupportedCurrency.USD));

            when(ledgerRepository.findIdempotencyByScopedKey(ctx.scopedKey()))
                .thenReturn(Optional.of(new LedgerEntryIdempotency(priorEntryId, ctx.fingerprint().value())));

            BatchTransferResult result = handler.batchTransfer(sender, items, ctx, audit(sender));

            assertThat(result).isEqualTo(new BatchTransferResult.Success(priorEntryId));
            verify(ledgerRepository, never()).postLedgerEntry(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Should reject with IdempotencyKeyConflict when fingerprints differ")
        void rejectsFingerprintMismatch() {
            UUID sender = UUID.randomUUID();
            UUID recipient = UUID.randomUUID();
            List<String> lines = List.of(recipient + "|50.00|USD");
            IdempotencyContext ctx = context(sender, lines, "key-conflict");
            List<BatchItem> items = List.of(new BatchItem(recipient, new BigDecimal("50.00"), SupportedCurrency.USD));

            when(ledgerRepository.findIdempotencyByScopedKey(ctx.scopedKey()))
                .thenReturn(Optional.of(new LedgerEntryIdempotency(UUID.randomUUID(), "different-digest")));

            BatchTransferResult result = handler.batchTransfer(sender, items, ctx, audit(sender));

            assertThat(result).isInstanceOf(BatchTransferResult.IdempotencyKeyConflict.class);
            verify(ledgerRepository, never()).postLedgerEntry(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Should reject when sender does not exist")
        void rejectsSenderNotFound() {
            UUID sender = UUID.randomUUID();
            UUID recipient = UUID.randomUUID();
            IdempotencyContext ctx = context(sender, List.of(recipient + "|50.00|USD"), "key-nf");
            List<BatchItem> items = List.of(new BatchItem(recipient, new BigDecimal("50.00"), SupportedCurrency.USD));

            when(ledgerRepository.findIdempotencyByScopedKey(any())).thenReturn(Optional.empty());
            when(userQueryService.existsById(sender)).thenReturn(false);

            BatchTransferResult result = handler.batchTransfer(sender, items, ctx, audit(sender));

            assertThat(result).isInstanceOf(BatchTransferResult.UserNotFound.class);
            verify(ledgerRepository, never()).postLedgerEntry(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Should reject when a recipient does not exist")
        void rejectsRecipientNotFound() {
            UUID sender = UUID.randomUUID();
            UUID recipient = UUID.randomUUID();
            IdempotencyContext ctx = context(sender, List.of(recipient + "|50.00|USD"), "key-rnf");
            List<BatchItem> items = List.of(new BatchItem(recipient, new BigDecimal("50.00"), SupportedCurrency.USD));

            when(ledgerRepository.findIdempotencyByScopedKey(any())).thenReturn(Optional.empty());
            when(userQueryService.existsById(sender)).thenReturn(true);
            when(userQueryService.existsById(recipient)).thenReturn(false);

            BatchTransferResult result = handler.batchTransfer(sender, items, ctx, audit(sender));

            assertThat(result).isInstanceOf(BatchTransferResult.UserNotFound.class);
            verify(ledgerRepository, never()).postLedgerEntry(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("Should reject self-transfer item")
        void rejectsSelfTransfer() {
            UUID sender = UUID.randomUUID();
            IdempotencyContext ctx = context(sender, List.of(sender + "|50.00|USD"), "key-self");
            List<BatchItem> items = List.of(new BatchItem(sender, new BigDecimal("50.00"), SupportedCurrency.USD));

            when(ledgerRepository.findIdempotencyByScopedKey(any())).thenReturn(Optional.empty());
            when(userQueryService.existsById(sender)).thenReturn(true);

            BatchTransferResult result = handler.batchTransfer(sender, items, ctx, audit(sender));

            assertThat(result).isInstanceOf(BatchTransferResult.InvalidBatch.class);
        }

        @Test
        @DisplayName("Should post one aggregated debit line and one pending credit per recipient")
        void postsCorrectLedgerLinesOnSuccess() {
            UUID sender = UUID.randomUUID();
            UUID recipientA = UUID.randomUUID();
            UUID recipientB = UUID.randomUUID();
            UUID newEntryId = UUID.randomUUID();
            List<BatchItem> items = List.of(
                new BatchItem(recipientA, new BigDecimal("30.00"), SupportedCurrency.USD),
                new BatchItem(recipientB, new BigDecimal("20.00"), SupportedCurrency.USD)
            );
            IdempotencyContext ctx = context(sender,
                List.of(recipientA + "|30.00|USD", recipientB + "|20.00|USD"), "key-ok");

            when(ledgerRepository.findIdempotencyByScopedKey(any())).thenReturn(Optional.empty());
            when(userQueryService.existsById(sender)).thenReturn(true);
            when(userQueryService.existsById(recipientA)).thenReturn(true);
            when(userQueryService.existsById(recipientB)).thenReturn(true);
            when(ledgerRepository.postLedgerEntry(any(), any(), any(), any(), any(), any()))
                .thenReturn(new PostLedgerEntryResult.Created(newEntryId));

            BatchTransferResult result = handler.batchTransfer(sender, items, ctx, audit(sender));

            assertThat(result).isEqualTo(new BatchTransferResult.Success(newEntryId));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<LedgerLine>> linesCaptor = ArgumentCaptor.forClass(List.class);
            verify(ledgerRepository).postLedgerEntry(any(), any(), linesCaptor.capture(), any(), any(), any());
            // 1 aggregated debit for sender + 2 pending credits (one per recipient)
            assertThat(linesCaptor.getValue()).hasSize(3);
        }

        @Test
        @DisplayName("Fingerprint is order-independent: same items in different order produce the same fingerprint")
        void fingerprintIsOrderIndependent() {
            UUID sender = UUID.randomUUID();
            UUID r1 = UUID.randomUUID();
            UUID r2 = UUID.randomUUID();

            // Same items, different order in the fingerprint lines list
            IdempotencyContext ctxAB = IdempotencyContext.scopedForUser(sender, "same-key",
                IdempotencyFingerprint.ofLines(List.of(r1 + "|10.00|USD", r2 + "|20.00|USD")));
            IdempotencyContext ctxBA = IdempotencyContext.scopedForUser(sender, "same-key",
                IdempotencyFingerprint.ofLines(List.of(r2 + "|20.00|USD", r1 + "|10.00|USD")));

            assertThat(ctxAB.fingerprint().value()).isEqualTo(ctxBA.fingerprint().value());
        }
    }
}
