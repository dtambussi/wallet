# DECISIONS

## 1) Architecture & Delivery Posture

| Decision | Alternatives | Why |
|----------|--------------|-----|
| **Implementation stack: Java 21 + Spring Boot + PostgreSQL + Docker** | Older Java LTS or different language stack | Mature, well-supported stack that runs easily locally, freeing challenge time for system guarantees rather than platform friction. |
| **JDBC over JPA (close to plain SQL)** | Hibernate | Explicit SQL and transactions for money; fewer ORM footguns. |
| **Hexagonal-lite (domain + application ports + adapters)** | Layered MVC with framework coupling | Isolates business rules from transport and infrastructure, making money logic easier to test and evolve. |
| **Split `DepositCommandHandler` / `WithdrawalCommandHandler` / `TransferCommandHandler`** | One `WalletCommandService` | Clearer change boundaries per use case; matches "one reason to change." |
| **Path `userId` instead of `X-User-Id`** | Header principal | More obvious in OpenAPI and curl; no header discovery issue. |

## 2) Core Ledger Correctness & Concurrency

| Decision | Alternatives | Why |
|----------|--------------|-----|
| **Pessimistic lock on `balance_projections`** | Optimistic only | Simpler correctness story under concurrent transfers on the same wallet. |
| **Scoped idempotency key (`{userId}\|{key}`)** | Global key only | Keys from different users do not collide, giving each user an independent idempotency namespace. |
| **Idempotency conflicts as `Result.IdempotencyKeyConflict`** | Only throw `IdempotencyKeyConflictException` | Keeps conflicts as explicit domain outcomes so handlers stay deterministic and controllers map them to a stable HTTP **409** (*Conflict*). |
| **`IdempotencyFingerprint` (domain) + `LedgerEntryTypes` (domain)** | Stringly typed in controllers | Centralizes allowed ledger entry type values in one canonical place instead of scattered string literals. |
| **Fingerprint binds key to payload** | Idempotency key alone | Same key + same body replays safely; same key + different body is rejected with conflict, preventing semantic drift on retries. |
| **Per-row `SELECT ... FOR UPDATE` debit locking in sorted key order** | Bulk `IN (...) FOR UPDATE` | Deterministic global lock order (`user_id`, then currency) makes concurrent commands acquire shared rows in the same sequence, reducing deadlock risk. |
| **Atomic credit increment (`amount + delta`) — no prior read lock on credit rows** | Lock all touched rows (debit and credit) | An increment cannot overdraft so no prior read is needed; concurrent credit writers serialize only at the `UPDATE`, narrowing the contention window. |
| **Single-sided deposits in ledger** | Full double-entry + suspense | Faster delivery; breaks strict per-entry sum-to-zero rule until a system counter-account is introduced. |
| **UUIDv7 for generated IDs + id-only transaction cursor** | UUIDv4 + timestamp/id composite cursor | Time-ordered IDs allow opaque cursor pagination with `ORDER BY id DESC` and `id < cursor` while retaining stable recency semantics. |

## 3) FX Pricing & Provider Reliability

| Decision | Alternatives | Why |
|----------|--------------|-----|
| **Mock FX with USD pivot (`ARS->BRL` is `ARS->USD` then `USD->BRL`)** | Live FX provider | Challenge scope; keeps tests deterministic. |
| **`POST /transfers` supports optional `toCurrency` for cross-currency transfers** | Separate endpoint or require explicit FX exchange first | Real cross-border wallets send in one currency and receive in another in a single step; `toCurrency` extends the existing endpoint without breaking same-currency callers, with the rate bound to the idempotency fingerprint to prevent re-pricing on replays. |
| **Two FX surfaces: quote+exchange vs transfer `toCurrency`** | One unified quote for every FX | `/fx/quotes` + `/fx/exchanges` give a locked snapshot with `quoteId` and TTL; `/transfers` + `toCurrency` uses a live rate at commit time — intentionally different guarantees. |
| **Circuit breaker on `FxRateProvider`; fail-fast 503 by default + runtime stale policy** | Always serve stale cache on failure | Serving stale silently is a financial risk; 503 is the safe default, and stale serving is opt-in via `wallet.fx.stale-rate-ttl-seconds` in `runtime_config`, effective without redeploy. |
| **Single FX provider for now; multi-provider routing/fallback deferred** | Integrate multiple FX providers from day one | Ideally we would route across multiple providers for resilience and cost control (for example, one provider may be more expensive but a strong fallback during outages). For challenge scope we keep one provider interface/implementation and defer smart provider selection/failover policy. |

## 4) External Side Effects & Payout Resilience

| Decision | Alternatives | Why |
|----------|--------------|-----|
| **Outbox pattern for withdrawal payout** | Inline provider call in same DB transaction | Keeps DB transactions short; provider retries happen outside lock windows, and `WITHDRAWAL_REVERSAL` restores balance on retry exhaustion. |
| **Retry-inside-transaction rejected for payouts** | Spring `@Retryable` on `WithdrawalCommandHandler` | A retry loop inside the DB transaction holds the balance row lock for the full retry+backoff duration, causing lock buildup under load. |
| **Reversal scope kept intentionally narrow (payout failure only)** | Full reversal lifecycle across all event types | Only the highest-value case is included: payout retry exhaustion triggers `WITHDRAWAL_REVERSAL`; broader reversal families are acknowledged as future production work. |

## 5) Scalability Path (When Load Grows)

| Decision | Alternatives | Why |
|----------|--------------|-----|
| **`pending_amount` column for pending-credit flows; explicit settlement** | Credit directly to available | Explicitly delays spendability for flows like payroll; settlement is a single `UPDATE` with no saga or distributed transaction. |
| **`POST /users/{userId}/batch-transfers` endpoint (extra)** | Caller issues N individual transfers | One sender lock cycle regardless of recipient count; N atomic pending credits in one transaction. |
| **Skip dedicated sender↔receiver transfer projection in baseline** | `transfer_relations` read model from day one | Keeps write model minimal and correctness-focused; pair-query projections can be added later as non-authoritative read models if they become hot. |
| **Promote hot filters to typed/indexed fields** | Keep frequent filter keys in JSON metadata | Hot filters modeled as indexed columns (`user_id`, `currency`, `entry_type`, `created_at`, `correlation_id`); long-tail context stays in metadata JSON. |

## 6) Observability & Operations

| Decision | Alternatives | Why |
|----------|--------------|-----|
| **Top 3 metrics selected per challenge scope** | Instrument all operations exhaustively | Challenge scope required picking the 3 metrics that most directly answer the first operational questions: is money moving, is the platform broken, are providers healthy. |
| **`wallet_money_flow_total{operation,outcome}`** | Default framework metrics only | Counts every money operation (deposit, transfer, withdrawal, fx_exchange) by outcome (success, business_reject, disabled); directly answers whether users can complete core actions. |
| **`wallet_internal_errors_total{operation}`** | Lump internal and provider failures together | Counts unexpected platform failures separately from provider failures so the source — platform vs provider — is immediately distinguishable by operation. |
| **`wallet_provider_health_total{provider,outcome}`** | Generic HTTP error counters | Counts provider call outcomes by provider (fx, payments), separating hard degraded from stale-served so FX incidents can be triaged without reading logs. |
| **Metrics emitted in controllers (adapter layer), not in command handlers** | Increment counters inside command handlers | Command handlers must stay infrastructure-free; controllers already know the operation name and outcome, making them the natural place to record metrics. |
| **No reconciliation jobs in current scope** | Scheduled reconciliation from day one | Challenge correctness is enforced synchronously; reconciliation is required at production scale to detect drift between ledger and provider state. |
| **Explicit operator killswitches for FX and withdrawals via `runtime_config`** | Rely solely on the circuit breaker | Circuit breaker is automatic; killswitches (`wallet.fx.enabled`, `wallet.withdrawals.enabled`) add deliberate operator control for maintenance, prolonged outages, or compliance holds — effective within 5 seconds without redeploy. |
| **Operational knobs (`runtime_config`, including killswitches) assumed single authoritative DB** | Shard-local config tables, global config replication, external feature-flag service | Today flags live in one Postgres (`runtime_config`) refreshed by the app on a short interval — fine for one deployment talking to one ledger DB. If wallets were split across many databases or regions we would revisit this: centralize controls in one store, drive flags from a dedicated config service, or another consistency model — **we are aware of that gap; out of scope for this exercise.** |

## 7) Deliberate Non-Goals (Challenge Scope)

| Decision | Alternatives | Why |
|----------|--------------|-----|
| **No authentication/authorization in challenge scope** | Full auth stack (OAuth2/JWT/mTLS) | Keeps focus on money correctness and resilience; explicitly non-production-safe without this hardening. |
| **Plain defaults for DB URLs, users, and passwords** | Secrets manager, injected runtime config, least-privilege DB roles | Prioritizes trivial clone-and-run for reviewers; Spring supports env-override but production secret management is deliberately out of scope. |
| **Minimal user model in current scope** | Rich user profile + KYC/AML lifecycle | User data kept small to focus on wallet correctness; production systems need compliance lifecycle states and risk metadata. |
| **No risk/fraud engine in current scope** | Real-time and batch fraud controls from day one | Challenge prioritizes ledger correctness; production systems need risk scoring, velocity rules, and anomaly detection. |
| **No country-level operating switch controls in current scope** | Runtime allow/deny policy per country | Geography policy is out of scope; production wallets need runtime country-level enable/disable with audit trail. |
| **No generic compensation workflow in current scope** | Saga-style compensations for every rejected path | Commands fail fast without partial state on rejected paths, so no compensating step is needed in those cases. |
| **`SupportedCurrency` as enum in challenge scope** | Runtime currency catalog with lifecycle states | Enum keeps code simple for a bounded exercise; production wallets need lifecycle-managed currency support. |
| **No alert delivery to on-call in current scope** | Prometheus Alertmanager + PagerDuty / OpsGenie / Slack | Metrics and thresholds are defined but no delivery pipeline is wired; thresholds are documentation-only without Alertmanager routing to an on-call rotation. |
| **No user notification on payout reversal** | Email / push when `WITHDRAWAL_REVERSAL` is posted | The system is financially self-healing (balance always restored) but not UX self-healing — users receive no signal that their withdrawal failed and funds are back, so they must check proactively. |
