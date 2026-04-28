# Guarantee Coverage

Maps each guarantee stated in **DESIGN.md** to the test(s) that exercise it — including under concurrent load, failure injection, and retry conditions. The intent is to show that guarantees are proven, not just described.

---

## Ledger correctness

| Guarantee | Test | What it exercises |
|-----------|------|-------------------|
| Balance never goes negative under concurrent withdrawals | `WalletGuaranteeStressIntegrationTest` · `eightConcurrentWithdrawals_exactly5Succeed_balanceNeverNegative` | 8 threads race to withdraw from a balance that covers 5; asserts exactly 5 succeed and final balance is exactly 0 |
| Insufficient funds rejected immediately | `WalletIntegrationTest` · `insufficientFundsRejected` | Withdrawal exceeding balance returns 409 with no ledger entry created |
| Single concurrent withdrawal wins when only one can fit | `WalletIntegrationTest` · `concurrentWithdrawalsOnlyOneCanSucceed` | Concurrent same-amount withdrawals from a balance covering exactly one; asserts exactly one succeeds |
| Debit and outbox entry are atomic — no orphaned debits | `WithdrawalCommandHandlerTest` · `postsLedgerAndOutboxOnSuccess` | Verifies both the ledger post and outbox insert are issued in the same unit of work |
| Withdrawal balance restored on payout retry exhaustion | `WalletIntegrationTest` · `withdrawalProviderFailureReversesBalanceAfterRetryExhaustion` | Provider always fails; after max retries, `WITHDRAWAL_REVERSAL` ledger entry is posted and balance is fully restored |
| Payout outbox row processed exactly once under concurrent workers | `WalletGuaranteeStressIntegrationTest` · `threeConcurrentOutboxDrains_eachPayoutRowProcessedExactlyOnce` | 3 worker threads race to claim and process the same outbox rows; each row is processed exactly once with no double dispatch |
| Settlement moves pending credits to available and clears pending | `WalletIntegrationTest` · `settleMovesPendingToAvailableAndClearsPending` | After batch transfer, settle moves all `pending_amount` to `amount` with nothing left pending |
| Batch transfer debits sender once and creates N pending recipient credits | `WalletIntegrationTest` · `batchTransferDebitsSenderAndCreatesPendingRecipientBalances` | Single sender debit; each recipient balance shows the correct pending credit |
| Concurrent batch transfers from same sender: only one can succeed if funds are insufficient | `WalletIntegrationTest` · `concurrentBatchTransfersFromSameSenderOnlyOneCanSucceed` | Races two batch transfers against a balance covering only one; asserts exactly one committed |

---

## Idempotency

| Guarantee | Test | What it exercises |
|-----------|------|-------------------|
| Same key + same body replays without a second ledger entry | `WalletIntegrationTest` · `idempotentDepositSingleEntry` | Second identical request returns 200 with original entry ID and no second row in the ledger |
| Concurrent identical requests produce a single ledger effect | `WalletIntegrationTest` · `concurrentIdempotentDepositCreatesSingleLedgerEffect` | Concurrent threads submit the same deposit; only one ledger entry is created |
| Concurrent identical deposits at scale produce a single ledger effect | `WalletGuaranteeStressIntegrationTest` · `eightConcurrentDeposits_sameIdempotencyKey_all201_singleLedgerEffect` | 8 concurrent threads; all return 201 but only one row exists in the ledger |
| Same key + different body is rejected as a conflict | `WalletIntegrationTest` · `idempotencyKeyWithDifferentBodyReturnsConflict` | Second request with same key but different amount returns 409 |
| Fingerprint mismatch rejected at the handler level | `WithdrawalCommandHandlerTest` · `rejectsFingerprintMismatch` · `DepositCommandHandlerTest` · `rejectsFingerprintMismatch` | Handler returns `IdempotencyKeyConflict` result when fingerprint does not match stored value |
| Blind retry with no idempotency header, same body twice: single ledger effect | `WalletGuaranteeStressIntegrationTest` · `blindRetryDeduplication_noHeader_sameBodyTwice_singleLedgerEffect` | Body fingerprint alone deduplicates the retry; no header required |
| Batch transfer idempotency applies the effect exactly once | `WalletIntegrationTest` · `batchTransferIdempotencyAppliesEffectOnce` | Replayed batch transfer returns original result; only one set of ledger entries exists |
| Cross-currency transfer replay produces a single ledger effect | `WalletIntegrationTest` · `crossCurrencyTransferIdempotencyReplaysSingleEffect` | FX rate is not re-fetched on replay; original converted amounts are returned |
| Replay is audited with `IDEMPOTENCY_REPLAY` outcome | `FinancialAuditEventsIntegrationTest` · `depositIdempotencyReplayIsAudited` | `financial_audit_events` row for the replay has outcome `IDEMPOTENCY_REPLAY` |

---

## Concurrency and deadlock prevention

| Guarantee | Test | What it exercises |
|-----------|------|-------------------|
| Opposing concurrent transfers (A→B and B→A) never deadlock | `WalletGuaranteeStressIntegrationTest` · `bidirectionalTransfers_concurrentOpposingDirections_noDeadlock` | Threads simultaneously transfer in both directions between the same two wallets; both complete without deadlock or timeout |
| FX quote can only be consumed once under concurrency | `WalletIntegrationTest` · `concurrentFxExchangeSameQuoteSingleConsume` | Multiple threads race to execute the same quote; exactly one succeeds and the rest receive 409 |

---

## FX provider resilience

| Guarantee | Test | What it exercises |
|-----------|------|-------------------|
| Provider unavailable returns 503 — no silent failure | `FxResilienceIntegrationTest` · `fxProviderUnavailable_postQuote_returns503` | Provider stubbed to fail; quote creation returns 503, no quote row created |
| Stale rate served when provider is degraded and TTL allows | `FxResilienceIntegrationTest` · `fxProviderDegraded_withinStaleTtl_servesStaleRateWithFlag` | Provider degraded; a previously cached rate within the configured TTL is returned with `servedFromStale=true` |
| Expired quote is rejected at exchange time | `FxResilienceIntegrationTest` · `expiredFxQuote_executeExchange_returns410Gone` | Quote TTL is set very short; exchange attempt after expiry returns 410 |
| Stale rate TTL is tunable at runtime from the database | `WalletIntegrationTest` · `fxStaleRateTtlCanBeUpdatedAtRuntimeFromDatabase` | `runtime_config` row is updated mid-test; policy picks up the new value within the refresh window |

---

## Audit trail

| Guarantee | Test | What it exercises |
|-----------|------|-------------------|
| Every command outcome is appended to `financial_audit_events` | `FinancialAuditEventsIntegrationTest` · `depositThenTransferThenFxAppendsChainedAudits` | Deposit → transfer → FX exchange chain; all three audit rows exist with correct outcomes and linked ledger entry IDs |
| Business rejection is audited with the correct outcome | `WalletIntegrationTest` · `insufficientFundsWritesAuditEvent` | Insufficient-funds rejection writes an `INSUFFICIENT_FUNDS` audit row with no linked ledger entry |
| Batch and settlement operations are audited | `FinancialAuditEventsIntegrationTest` · `batchAndSettlementAppendsBatchAndSettleSuccess` | Both the batch transfer and the subsequent settlement produce audit rows |
| Withdrawal and payout worker outcomes are audited end to end | `FinancialAuditEventsIntegrationTest` · `withdrawalAndPayoutWorkerAppendsWithdrawalAndPayoutRows` | Withdrawal row followed by payout dispatch row; outcomes chain correctly |
| Correlation ID links the ledger entry to the audit row | `WalletIntegrationTest` · `xRequestIdLinksLedgerRowAndAuditLog` | `X-Request-Id` header propagates to `ledger_entries.correlation_id` and `financial_audit_events.correlation_id` |

---

## Observability

| Guarantee | Test | What it exercises |
|-----------|------|-------------------|
| Money operations emit `wallet_money_flow_total` with correct labels | `WalletMetricsIntegrationTest` · `moneyOperations_emitMoneyFlowMetric` | Deposit, withdrawal, transfer, and FX exchange are performed; asserts counter incremented in `MeterRegistry` with correct `operation` and `outcome` tag combinations |
| FX quote creation emits `wallet_provider_health_total` | `WalletMetricsIntegrationTest` · `fxQuoteCreation_emitsProviderHealthMetric` | Quote request emits a `request` outcome for the `fx` provider; asserted via `MeterRegistry` in-process |
| Metrics are exported in Prometheus text format with correct labels via `/actuator/prometheus` | `PrometheusExportIntegrationTest` · `prometheusEndpoint_exportsWalletMetricsWithLabels` | Runs real operations against a full `RANDOM_PORT` context, scrapes the actuator endpoint, and asserts `wallet_money_flow_total` and `wallet_provider_health_total` appear with the expected label combinations in the Prometheus text output |

---

## Scope note

The coverage above proves every business guarantee end-to-end against a real database. The one area that remains at the infrastructure level rather than the test level is **alert pipeline fidelity**: the Grafana alert rules are validated by configuration, and the metrics that would trigger them are verified to be emitted and scraped correctly — but the alerts themselves are not exercised under simulated provider failure conditions.

The natural next step would be dedicated provider mock servers (e.g. WireMock containers) that can be programmatically set to return 503s, timeouts, or degraded responses, enabling tests that drive the system into each alert state and confirm the alert fires with the correct severity and labels. Within the scope of this challenge, the combination of integration tests covering failure injection (retry exhaustion, FX provider unavailability, stale rate serving) and the Prometheus scrape verification provides sufficient confidence that the observability layer behaves as designed.
