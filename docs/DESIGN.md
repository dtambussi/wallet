# DESIGN — Cross-border wallet (concise)

> Extended detail on load behaviour, application structure, and scale path reasoning: [DESIGN_EXTRAS.md](DESIGN_EXTRAS.md).

## Known limitations (reviewer shortcut)

More detail appears below; **gaps are deliberate challenge scope**.

- **No Auth/AuthZ:** path-based `userId` only — add OAuth2/mTLS + authorization before production.
- **No compliance stack:** PCI/PSP, fraud/AML/KYC, regulatory reporting absent.
- **FX cache per JVM:** replicas may disagree on freshness — replace with a **shared rate store** (e.g. Redis + refresher job) at scale.
- **Single-region / single DB:** no geo active-active — add regions + reconciliation when needed.
- **Currency enum:** `SupportedCurrency` is compile-time — production wants lifecycle policy per currency.
- **Precision:** `NUMERIC(38,18)` + `MoneyScale` aligned end-to-end; production adds **per-currency posting/display** rules — **intentionally out of scope here** (focus stays ledger + idempotency + FX behavior).
- **Edge abuse:** no gateway rate limits / WAF yet.
- **Outbox:** payout only — extend async/outbox + reconciliation for future provider integrations.

## Guarantees

#### 1. Ledger integrity

Balances change only via `ledger_entries` / `ledger_lines`. **Debits:** `SELECT … FOR UPDATE` on `balance_projections` in **deterministic sort order**, validate non‑negative, then commit entry + projection updates **in one transaction**. **Credits:** atomic `amount` / `pending_amount` increments — no prior read lock (increment cannot overdraft). Lock ordering covers both sides of every transfer in the same sorted pass — opposing concurrent transfers (A→B and B→A) contend for the same first key rather than forming a deadlock cycle.

#### 2. Idempotent writes

Unique `ledger_entries.idempotency_key`; scoped **`{userId}|{header or fingerprint}`** — same key replays same outcome; cross-user collision impossible.

#### 3. Two FX surfaces (by design)

- **Quote → exchange (`/fx/quotes`, `/fx/exchanges`):** stored quote, `expires_at` (~30s default), **`FOR UPDATE`** until post marks consumed; failed post rolls back → quote reusable.

- **`POST /transfers` + optional `toCurrency`:** **no `quoteId`** for transfer — live provider rate **at commit**, bound to idempotency fingerprint on replay. Same-currency transfer is baseline challenge; cross-currency transfer is documented extension.

#### 4. Withdrawals and FX provider integration

- **Withdrawals:** Debit + `payout_outbox` row commit atomically. `PayoutWorker` claims rows with `SELECT … FOR UPDATE SKIP LOCKED` (safe with multiple workers). On retry exhaustion, `WITHDRAWAL_REVERSAL` credits the amount back to available balance — funds are never stranded.
  - **Killswitch** (`runtime_config` key `wallet.withdrawals.enabled`): set `false` to block new intakes with **503 SERVICE_DISABLED** during a PSP outage, compliance pause, or incident — no redeploy, takes effect in ~5 s. Already-queued outbox rows are **not** cancelled; the worker keeps retrying them independently. Re-enable by setting `true`.

- **FX provider (`ResilientFxRateProvider`):** Circuit breaker → **503** on repeated failures. Optional stale-rate fallback: `wallet.fx.stale-rate-ttl-seconds` in `runtime_config` (`0` = never serve stale; `> 0` = serve cached rate within that window — opt-in financial tradeoff for short outages).
  - **Killswitch** (`runtime_config` key `wallet.fx.enabled`): set `false` to disable quotes, exchanges, and cross-currency transfers (**503 SERVICE_DISABLED**) during a prolonged FX provider outage or when stale rates are too old to be safe. Same-currency transfers are unaffected. Takes effect in ~5 s, no redeploy. Re-enable by setting `true`.

#### 5. Payload binding

SHA-256 **fingerprint** on `ledger_entries`; same key + different body → **409** (*Conflict*). No header → suffix defaults to fingerprint so blind retries dedupe.

#### 6. IDs / cursors

**UUIDv7** writers; pagination `ORDER BY ledger_entries.id DESC`, opaque `cursor` — no composite time+id cursor.

## Data model
| Artifact | Role |
|---|---|
| `users` | `id`, `created_at`; no PII in wallet DB. |
| `balance_projections` | PK `(user_id, currency)`; `amount` (available), `pending_amount` (uncleared), `version`. |
| `ledger_entries` | Unique `idempotency_key`; fingerprint, `entry_type`, `metadata`, `correlation_id`. |
| `ledger_lines` | Signed per-user/per-currency lines (credit `+`, debit `-`). |
| `fx_quotes` | Quote snapshot + lifecycle (`expires_at`, `consumed_at`) + pricing metadata. |
| `financial_audit_events` | Append-only outcomes (success/replay/reject) for ops reconciliation. |
| `payout_outbox` | Withdrawal dispatch queue (`status`, `attempts`, `next_attempt_at`), worker uses `SKIP LOCKED`. |
| `runtime_config` | Runtime knobs (e.g. `wallet.fx.stale-rate-ttl-seconds`), short cache refresh, no redeploy. |

Typed/indexed columns for primary queries; JSON for non-authoritative context. Future read projection (e.g. `transfer_relations`) can be added without changing ledger authority.

## Failure modes
| Risk | Mitigation |
|---|---|
| Concurrent spend | Row locks + single transaction |
| Duplicate POST | Idempotency key + unique constraint + replay |
| Stale FX quote | TTL + row lock + consume-after-post |
| Withdrawal provider down | Outbox + retries + `WITHDRAWAL_REVERSAL` |
| FX provider down | Breaker → 503; optional stale TTL via `runtime_config`; per-JVM cache can evolve to shared store |
| Key reuse, different body | Fingerprint mismatch → **409** (*Conflict*) |
| Bad input | Global validation → 400 |
| Hot balance row | Writers serialize (correct); batch API reduces lock cycles for payroll shape |
| Pending misuse | Batch credits stay in `pending_amount` until `/settle`; normal P2P credits go to `amount` |

Out of scope: external providers lying/contradicting (mocks only), HTTP rate limiting, fraud detection.

## Scale path

| Tier | Focus |
|------|--------|
| **~10K** | Single Postgres monolith (current); optional read replica for reporting. |
| **~1M** | Shard hot `(user_id, currency)` rows; async read models; **shared FX rate store**; external HTTP notifications/callbacks dispatched from an **outbox worker** *(like already implemented for payouts)*. |
| **~10M** | Regional ledgers + reconciliation; **separate scalable capacity for payouts vs FX** (e.g. different workers / queues — a spike on one doesn't stall the other); gateway + Redis idempotency for herd traffic. |

See [DESIGN_EXTRAS.md](DESIGN_EXTRAS.md) for detailed reasoning on lock ordering under sharding, hot-account throughput ceilings, and suggested ops levers.
