# DESIGN — Extended notes

Overflow from [DESIGN.md](DESIGN.md), kept separate to respect the 3-page challenge constraint. Nothing here contradicts DESIGN.md — it expands on reasoning that would bloat the summary.

---

## Correctness vs load

Load changes **latency and saturation**, not invariants:

| Invariant | Meaning |
|-----------|---------|
| Money trail | Immutable `ledger_lines`; `balance_projections` updated **in the same commit** as the ledger insert. |
| At-least-once → exactly-once | Duplicate **requests** are normal (retries); the API still guarantees **one** posting **effect** — scoped idempotency key + fingerprint + unique ledger row → replay returns the prior `ledger_entry_id`; same key + different body → **409** (*Conflict*). |
| Solvency | No durable negative balance for backed currencies — enforced before commit. |
| Audit | Append-only audit + correlation id — rejections/replays reconstructable without scraping logs alone. |

### Under crush load

**Behavior today (same code path, higher load)**

- **[contention]** Writers that touch the **same** `balance_projections` row **take turns** (row-level locking). **Correctness is preserved**; under overload the symptom is **latency**, **timeouts**, or **pool exhaustion** — not silent wrong balances.

- **[API usage pattern]** **`N`** separate **`POST …/transfers`** from the **same sender** each debit that sender's row again ⇒ **`N`** sequential lock passes. Throughput for that payer **drops** even though the implementation is "working as designed."

- **[retail / shopping burst ("Black Friday" shape)]** A surge is often **spread** across **many** unrelated `(user_id, currency)` rows — each row still serializes its own writers, and total throughput can rise with **more** DB capacity and **stateless** app instances **until** traffic collapses onto **one** hot balance (e.g. a marketplace settlement account, a mega-merchant, or a payroll payer). That **single-row** case is the same bottleneck as the payroll pattern above, not a different failure mode.

**Caller / product choice (already implemented in this repo)**

- **[Use `batch-transfers` instead]** For payroll-shaped payouts, **`POST …/batch-transfers`** posts **one** `ledger_entries` row with **many** `ledger_lines` (**one** debit + **`N`** credits) under **one** idempotency scope ⇒ **one** sender lock cycle. Same ledger rules; different **call pattern**.

**Suggested operations & tuning (optional levers — same deployment, no semantics change)**

- **[ops / capacity]** *Not something the app enforces for you* — these are **typical** production levers: tune pool sizes, statement/`lock_timeout`, add back-pressure, queues, or throttles for known-hot accounts; scale **stateless** app replicas. They improve **predictability and headroom** but **do not remove** the hard ceiling of **one contended balance row**.

**Suggested architecture evolution (when the *model* or *topology* must change)**

*The monolith in this repo is a **single-DB** baseline. The points below are **design direction** for when you outgrow that — not things the current code "turns on" for you.*

- **[hot row / throughput ceiling]** If you need **higher** sustained throughput **through one real-world payer** (one hot `balance_projections` row), the usual answer is a **product / data-model** change: e.g. **split** balance views, **dedicated** accounts, or stricter **batch** contracts — **not** relaxing solvency rules.

- **[multi-partition / shards]** Once data or traffic spans partitions, typical patterns are **shard-local transactions**, **deterministic lock order inside a shard**, and **async / saga / outbox** when one **logical** command touches **multiple** shards. The Scale path table in DESIGN.md summarizes that roadmap-style sketch — concrete implementation is future work relative to this challenge scope.

**Explicit non-goals:** real auth, PCI posture, multi-region ledger — no full double-entry suspense world line; deposits stay **single-sided** by design here.

Gateway/WAF/idempotency edge patterns and operator triage: limitations in DESIGN.md; overload runbook → [RUNBOOK.md](RUNBOOK.md).

---

## Scale path — extended reasoning

Every balance update goes through **`lockAllAndValidateDebits`** / **`lockAndLoadCurrentBalance`**, so **lock order** is implemented **once**. When you shard, you **route** each `(user_id, currency)` to a database partition, **repeat the same sorted lock sequence inside that shard**, and **finish with one transaction per shard** — no **two-phase commit** across databases for the usual case. If one API command needs rows on **several** shards, a **single** distributed ACID transaction is the wrong tool; use **shard-local commits** plus **async coordination** (**outbox / saga**) instead.

For future PSP/card integrations, keep external provider calls **out** of long DB transactions; persist intent first, then dispatch/retry via outbox-style workers with reconciliation/compensation, while DB idempotency remains authoritative.

**Hot merchant/payroll accounts:** **for current scope**, one balance row serialization is the right correctness-first tradeoff. **At higher sustained scale**, that same row becomes a throughput ceiling; then raise limits by **changing the model** (split accounts, explicit batch semantics), not by weakening solvency checks.

---

## Application structure

- **Commands:** `com.wallet.application.command.*` — `*CommandHandler` per use case (`CreateUser`, `Deposit`, `Withdrawal`, `Transfer`, `BatchTransfer`, `Settlement`, `Fx`). **`com.wallet.application.service.*`** = shared queries/helpers only.
- **Outcomes:** sealed `com.wallet.application.result.*`; controllers map to HTTP (incl. idempotency conflict).
- **Types:** `com.wallet.domain.*` business types; web DTOs in `adapter.in.web`; `LedgerEntryTypes`, `IdempotencyFingerprint` on persisted rows.
- **Observability:** `X-Request-Id` (generated if absent); POST `/users/**` logs **hash** of idempotency key, not raw value.

---

## FX killswitch — full detail

Operator killswitch for FX operations: Postgres table **`runtime_config`**, row key **`wallet.fx.enabled`**.

- **Values:** **`true`** / **`false`** string. If the row is **missing**, behavior defaults to **enabled**.
- **What `false` disables:** `POST …/fx/quotes`, `POST …/fx/exchanges`, and `POST …/transfers` with a `toCurrency` that differs from `currency` — all return **503 SERVICE_DISABLED**. Same-currency transfers are unaffected.
- **When it makes sense to set `false`:** Use when stale rates are too old to be financially safe, or when the FX provider outage is expected to be long. For short outages, prefer the stale-rate TTL lever first (`wallet.fx.stale-rate-ttl-seconds`) — it keeps FX operations running on a cached rate within an acceptable window.
- **Rollout:** App re-reads `runtime_config` on a **~5s** cache window — **no redeploy**.
- **Recovery:** `UPDATE runtime_config SET value = 'true' WHERE key = 'wallet.fx.enabled';` — confirm `wallet_provider_health_total{provider="fx", outcome="degraded"}` returns to zero before closing the incident.

---

## Withdrawal killswitch — full detail

Operator killswitch for withdrawals: Postgres table **`runtime_config`**, row key **`wallet.withdrawals.enabled`**.

- **Values:** **`true`** / **`false`** string. If the row is **missing**, behavior defaults to **enabled** (withdrawals allowed).
- **When it makes sense to set `false`:** Turn **off** when **new** withdrawals should fail fast at the API (**503**) — PSP outage / maintenance, compliance pause on payouts, or incident mode where you want fewer fresh debits and outbox rows **without redeploy**. Use with normal triage/comms (RUNBOOK); it **pauses intake**, it does **not** repair the provider.
- **What `false` does:** **`POST …/withdrawals`** returns **503** `SERVICE_DISABLED`. **Already queued** `payout_outbox` rows are **not** cancelled by this flag alone — the worker can still drain existing work (the controller gate only blocks **new** withdrawal requests).
- **Rollout:** App re-reads `runtime_config` on a **~5s** cache window — **no redeploy**, same cadence as other **`runtime_config`** knobs.
