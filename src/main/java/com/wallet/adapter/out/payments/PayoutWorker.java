package com.wallet.adapter.out.payments;

import com.wallet.application.audit.WalletCommandAudit;
import com.wallet.application.port.out.LedgerRepository;
import com.wallet.application.port.out.PayoutOutboxRepository;
import com.wallet.application.port.out.PayoutOutboxRepository.PayoutOutboxRecord;
import com.wallet.application.port.out.WithdrawalProvider;
import com.wallet.domain.SupportedCurrency;
import com.wallet.domain.ledger.LedgerEntryTypes;
import com.wallet.domain.ledger.LedgerLine;
import com.wallet.infrastructure.config.WalletProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Flow explanation for context:
 * <p>
 * Example withdrawal (7 USD out of 50), tables involved
 * 0) User has balance_projections: (user_id, USD) amount=50.
 * 1) POST /withdrawals
 *    - Inserts ledger_entries (type WITHDRAWAL) + ledger_lines: user, USD, -7.
 *    - Updates balance_projections amount 50 -> 43.
 *    - Inserts payout_outbox: PENDING, amount=7, links ledger_entry_id, user_id, currency=USD.
 * 2) Before this worker runs: money is already debited; the provider part via outbox is still PENDING.
 * 3) This worker: claims payout_outbox (SKIP LOCKED), calls provider.
 * 4) Success: payout_outbox SUCCEEDED, provider_ref set, ledger_entries.metadata gets real provider ref.
 * 5) Retries exhausted: payout_outbox FAILED, new ledger entry WITHDRAWAL_REVERSAL line +7 so balance is restored.
 * <p>
 * What each outbox run does, in order:
 * 1) Call provider.
 * 2) On success: mark SUCCEEDED, patch ledger metadata with provider ref.
 * 3) On failure with retries left: bump attempts, schedule next attempt.
 * 4) On final failure: mark FAILED, post WITHDRAWAL_REVERSAL to restore the debit.
 * SKIP LOCKED benefits: several workers can run in parallel without double-claiming the same row.
 * <p>
 * Why we use an outbox and a separate worker:
 * When the user hits POST /withdrawals, we do everything that must be atomic in one database transaction:
 * write the withdrawal to ledger + ledger_lines, move balance_projections, and add one payout_outbox row.
 * The slow part — calling the bank/PSP — is not in that request. The worker picks up PENDING rows and does
 * the call later. That way the HTTP request stays short, and we are not keeping balance rows locked while
 * waiting on a flaky network or provider. Outbox row is the reminder: "this debit still needs a real payout."
 */
@Component
public class PayoutWorker {

    private static final Logger log = LoggerFactory.getLogger(PayoutWorker.class);

    private final PayoutOutboxRepository outboxRepository;
    private final WithdrawalProvider withdrawalProvider;
    private final LedgerRepository ledgerRepository;
    private final WalletCommandAudit commandAudit;
    private final TransactionTemplate tx;
    private final int maxAttempts;
    private final int backoffBaseSeconds;
    private final Counter payoutProviderRequests;
    private final Counter payoutProviderDegraded;
    private final Counter payoutWorkerInternalErrors;

    public PayoutWorker(
        PayoutOutboxRepository outboxRepository,
        WithdrawalProvider withdrawalProvider,
        LedgerRepository ledgerRepository,
        WalletCommandAudit commandAudit,
        PlatformTransactionManager txManager,
        WalletProperties walletProperties,
        MeterRegistry meterRegistry
    ) {
        this.outboxRepository = outboxRepository;
        this.withdrawalProvider = withdrawalProvider;
        this.ledgerRepository = ledgerRepository;
        this.commandAudit = commandAudit;
        this.tx = new TransactionTemplate(txManager);
        this.maxAttempts = walletProperties.payout().maxAttempts();
        this.backoffBaseSeconds = walletProperties.payout().backoffBaseSeconds();
        this.payoutProviderRequests = Counter.builder("wallet_provider_health_total")
            .description("Provider health outcomes by provider and outcome")
            .tag("provider", "payments")
            .tag("outcome", "request")
            .register(meterRegistry);
        this.payoutProviderDegraded = Counter.builder("wallet_provider_health_total")
            .description("Provider health outcomes by provider and outcome")
            .tag("provider", "payments")
            .tag("outcome", "degraded")
            .register(meterRegistry);
        this.payoutWorkerInternalErrors = Counter.builder("wallet_internal_errors_total")
            .description("Internal errors by operation")
            .tag("operation", "payout_worker")
            .register(meterRegistry);
    }

    @Scheduled(
        initialDelayString = "${wallet.payout.worker-initial-delay-ms:1000}",
        fixedDelayString   = "${wallet.payout.worker-interval-ms:5000}"
    )
    public void drainOutbox() {
        Boolean processedOnePayout;
        do {
            processedOnePayout = tx.execute(txStatus -> {
                Optional<PayoutOutboxRecord> maybePayoutOutbox = outboxRepository.claimNextPending();
                if (maybePayoutOutbox.isEmpty()) return false;
                try {
                    process(maybePayoutOutbox.get());
                } catch (RuntimeException internalError) {
                    payoutWorkerInternalErrors.increment();
                    throw internalError;
                }
                // handled one payout outbox row
                return true;
            });
        } while (Boolean.TRUE.equals(processedOnePayout));
    }

    // All steps run within the TransactionTemplate transaction started by drainOutbox().
    // Provider exceptions are caught here — the transaction commits the outcome (attempt increment or reversal).
    private void process(PayoutOutboxRecord outboxPayout) {
        SupportedCurrency payoutCurrency;
        try {
            payoutCurrency = SupportedCurrency.fromCode(outboxPayout.currency());
        } catch (IllegalArgumentException invalidCurrencyError) {
            handleInvalidOutboxCurrency(outboxPayout, invalidCurrencyError);
            return;
        }
        String providerRef;
        try {
            // Step 1 — dispatch to external provider
            payoutProviderRequests.increment();
            providerRef = withdrawalProvider.initiatePayout(
                outboxPayout.userId(), payoutCurrency, outboxPayout.amount()
            );
        } catch (Exception providerException) {
            payoutProviderDegraded.increment();
            // Step 3 — failure: retry or reverse
            int newAttempts = outboxPayout.attempts() + 1;
            log.warn("Payout attempt {}/{} failed for ledgerEntry={}: {}",
                newAttempts, maxAttempts, outboxPayout.ledgerEntryId(), providerException.getMessage());

            if (newAttempts >= maxAttempts) {
                // Exhausted — reverse the original debit so the user's balance is restored.
                outboxRepository.markFailed(outboxPayout.id());
                UUID reversalId = postReversal(outboxPayout);
                ledgerRepository.mergeEntryMetadata(
                    outboxPayout.ledgerEntryId(),
                    Map.of("payoutStatus", "REVERSED", "reversalLedgerEntryId", reversalId.toString())
                );
                commandAudit.recordPayoutReversed(outboxPayout.userId(), outboxPayout.ledgerEntryId(), reversalId);
            } else {
                // Exponential backoff: base * 2^newAttempts, capped at 5 minutes.
                long delaySecs = Math.min((long) backoffBaseSeconds << newAttempts, 300L);
                outboxRepository.incrementAttempts(outboxPayout.id(), Instant.now().plusSeconds(delaySecs));
                commandAudit.recordPayoutRetrying(outboxPayout.userId(), outboxPayout.ledgerEntryId(), newAttempts, providerException.getMessage());
            }
            return;
        }
        // Step 2 — success: mark done, patch ledger, audit
        outboxRepository.markSucceeded(outboxPayout.id(), providerRef);
        ledgerRepository.mergeEntryMetadata(
            outboxPayout.ledgerEntryId(),
            Map.of("providerRef", providerRef, "payoutStatus", "SETTLED")
        );
        commandAudit.recordPayoutDispatched(outboxPayout.userId(), outboxPayout.ledgerEntryId(), providerRef);
    }

    private void handleInvalidOutboxCurrency(PayoutOutboxRecord outboxPayout, IllegalArgumentException invalidCurrencyError) {
        payoutWorkerInternalErrors.increment();
        log.error(
            "Invalid payout currency '{}' for ledgerEntry={}. Marking FAILED and reversing withdrawal: {}",
            outboxPayout.currency(),
            outboxPayout.ledgerEntryId(),
            invalidCurrencyError.getMessage()
        );
        outboxRepository.markFailed(outboxPayout.id());
        UUID reversalId = postReversal(outboxPayout);
        ledgerRepository.mergeEntryMetadata(
            outboxPayout.ledgerEntryId(),
            Map.of(
                "payoutStatus", "REVERSED",
                "payoutErrorType", "INVALID_CURRENCY",
                "payoutError", invalidCurrencyError.getMessage(),
                "reversalLedgerEntryId", reversalId.toString()
            )
        );
        commandAudit.recordPayoutReversed(outboxPayout.userId(), outboxPayout.ledgerEntryId(), reversalId);
    }

    private UUID postReversal(PayoutOutboxRecord outboxPayout) {
        // Scoped key and fingerprint are deterministic — if the worker crashes after posting but
        // before marking FAILED, the next run finds the reversal already committed and replays it safely.
        String reversalKey = "system|reversal:" + outboxPayout.ledgerEntryId();
        return ledgerRepository.postLedgerEntry(
            reversalKey,
            LedgerEntryTypes.WITHDRAWAL_REVERSAL,
            List.of(LedgerLine.available(outboxPayout.userId(), outboxPayout.currency(), outboxPayout.amount())),
            Map.of("originalLedgerEntryId", outboxPayout.ledgerEntryId().toString()),
            "fp:" + reversalKey,
            null
        ).ledgerEntryId();
    }
}
