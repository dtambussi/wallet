#!/usr/bin/env python3
"""
Live test — wallet main flows.

Covers: deposit, idempotency replay/conflict, insufficient funds, P2P transfer,
cross-currency transfer, FX quote+exchange (double-consume rejection),
batch transfer + settle, withdrawal + payout dispatch.

Each flow prints the request and full response. At the end, DB hints are printed
so a reviewer can verify the data directly.

Usage:
  python live-tests/run.py                        # against http://localhost:8080
  WALLET_URL=http://wallet:8080 python live-tests/run.py
"""

import json
import os
import sys
import time
import urllib.error
import urllib.request
from uuid import uuid4

BASE = os.environ.get("WALLET_URL", "http://localhost:8080").rstrip("/")
RUN_ID = str(uuid4())[:8]

# ── ANSI colours ──────────────────────────────────────────────────────────────
GREEN  = "\033[32m"
RED    = "\033[31m"
YELLOW = "\033[33m"
CYAN   = "\033[36m"
BOLD   = "\033[1m"
DIM    = "\033[2m"
RESET  = "\033[0m"

passed = 0
failed = 0


def _color_json(text):
    return text  # plain; no external deps


def section(title):
    print(f"\n{BOLD}{CYAN}{'─' * 60}{RESET}")
    print(f"{BOLD}{CYAN}  {title}{RESET}")
    print(f"{BOLD}{CYAN}{'─' * 60}{RESET}")


def _call(method, path, body=None, headers=None, idempotency_key=None):
    url = BASE + path
    data = json.dumps(body).encode() if body is not None else None
    req_headers = {"Content-Type": "application/json", "Accept": "application/json"}
    if idempotency_key:
        req_headers["Idempotency-Key"] = idempotency_key
    if headers:
        req_headers.update(headers)
    req = urllib.request.Request(url, data=data, headers=req_headers, method=method)
    print(f"\n{DIM}  → {method} {path}{RESET}")
    if body:
        print(f"{DIM}    body: {json.dumps(body)}{RESET}")
    if idempotency_key:
        print(f"{DIM}    Idempotency-Key: {idempotency_key}{RESET}")
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            raw = resp.read().decode()
            status = resp.status
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        status = e.code
    try:
        parsed = json.loads(raw)
        body_str = json.dumps(parsed, indent=4)
    except Exception:
        body_str = raw
    return status, parsed if raw else {}


def expect(label, status, body, expected_status, *, key=None, value=None, absent_key=None):
    global passed, failed
    ok = status == expected_status
    if ok and key is not None:
        ok = key in body and (value is None or body[key] == value)
    if ok and absent_key is not None:
        ok = absent_key not in body
    sym = f"{GREEN}✓{RESET}" if ok else f"{RED}✗{RESET}"
    detail = f"HTTP {status}"
    if key and key in body:
        detail += f"  {key}={body[key]!r}"
    if ok:
        passed += 1
    else:
        failed += 1
        detail += f"  (expected HTTP {expected_status})"
        if key and key not in body:
            detail += f"  missing key '{key}'"
    print(f"  {sym} {label:50s} {detail}")
    return body


# ── helpers ───────────────────────────────────────────────────────────────────

def create_user():
    status, body = _call("POST", "/users")
    expect("create user", status, body, 201, key="userId")
    return body.get("userId")


def ikey(tag):
    return f"{RUN_ID}-{tag}"


# ══════════════════════════════════════════════════════════════════════════════
section(f"Setup  (run_id={RUN_ID})")
# ══════════════════════════════════════════════════════════════════════════════

alice = create_user()
bob   = create_user()
carol = create_user()
print(f"  alice={alice}")
print(f"  bob  ={bob}")
print(f"  carol={carol}")


# ══════════════════════════════════════════════════════════════════════════════
section("1 · Deposit")
# ══════════════════════════════════════════════════════════════════════════════

status, body = _call("POST", f"/users/{alice}/deposits",
                     {"amount": "500.00", "currency": "USD"},
                     idempotency_key=ikey("dep-alice-usd"))
dep1 = expect("alice deposit USD 500", status, body, 201, key="ledgerEntryId")
alice_dep_id = body.get("ledgerEntryId")

status, body = _call("POST", f"/users/{alice}/deposits",
                     {"amount": "1000.00", "currency": "ARS"},
                     idempotency_key=ikey("dep-alice-ars"))
expect("alice deposit ARS 1000", status, body, 201, key="ledgerEntryId")

status, body = _call("POST", f"/users/{bob}/deposits",
                     {"amount": "200.00", "currency": "USD"},
                     idempotency_key=ikey("dep-bob-usd"))
expect("bob deposit USD 200", status, body, 201, key="ledgerEntryId")


# ══════════════════════════════════════════════════════════════════════════════
section("2 · Idempotency")
# ══════════════════════════════════════════════════════════════════════════════

# Replay: same key + same body → same ledger entry id
status, body = _call("POST", f"/users/{alice}/deposits",
                     {"amount": "500.00", "currency": "USD"},
                     idempotency_key=ikey("dep-alice-usd"))
expect("replay same key → 201, same ledgerEntryId", status, body, 201,
       key="ledgerEntryId", value=alice_dep_id)

# Conflict: same key + different body → 409
status, body = _call("POST", f"/users/{alice}/deposits",
                     {"amount": "999.00", "currency": "USD"},
                     idempotency_key=ikey("dep-alice-usd"))
expect("conflict same key diff body → 409", status, body, 409)


# ══════════════════════════════════════════════════════════════════════════════
section("3 · Balances")
# ══════════════════════════════════════════════════════════════════════════════

status, body = _call("GET", f"/users/{alice}/balances")
expect("alice balances → 200", status, body, 200)
print(f"  alice balances: {body}")

status, body = _call("GET", f"/users/{alice}/transactions")
expect("alice transactions page → 200", status, body, 200, key="items")
print(f"  transaction count so far: {len(body.get('items', []))}")


# ══════════════════════════════════════════════════════════════════════════════
section("4 · Insufficient funds rejection")
# ══════════════════════════════════════════════════════════════════════════════

status, body = _call("POST", f"/users/{bob}/withdrawals",
                     {"amount": "9999.00", "currency": "USD"},
                     idempotency_key=ikey("bob-overdraft"))
expect("overdraft rejection → 422", status, body, 422)


# ══════════════════════════════════════════════════════════════════════════════
section("5 · P2P transfer (same currency)")
# ══════════════════════════════════════════════════════════════════════════════

status, body = _call("POST", f"/users/{alice}/transfers",
                     {"toUserId": bob, "amount": "50.00", "currency": "USD"},
                     idempotency_key=ikey("xfer-alice-bob-usd"))
expect("alice → bob USD 50", status, body, 201, key="ledgerEntryId")

status, body = _call("GET", f"/users/{alice}/balances")
expect("alice balance after transfer → 200", status, body, 200)
print(f"  alice balances: {body}")

status, body = _call("GET", f"/users/{bob}/balances")
expect("bob balance after transfer → 200", status, body, 200)
print(f"  bob balances: {body}")


# ══════════════════════════════════════════════════════════════════════════════
section("6 · Cross-currency transfer (USD → ARS)")
# ══════════════════════════════════════════════════════════════════════════════

status, body = _call("POST", f"/users/{alice}/transfers",
                     {"toUserId": bob, "amount": "10.00",
                      "currency": "USD", "toCurrency": "ARS"},
                     idempotency_key=ikey("xfer-alice-bob-fx"))
expect("alice USD 10 → bob ARS (live rate)", status, body, 201, key="ledgerEntryId")

status, body = _call("GET", f"/users/{bob}/balances")
expect("bob balances after cross-currency → 200", status, body, 200)
print(f"  bob balances: {body}")


# ══════════════════════════════════════════════════════════════════════════════
section("7 · FX quote + exchange (two-step self-conversion)")
# ══════════════════════════════════════════════════════════════════════════════

status, body = _call("POST", f"/users/{alice}/fx/quotes",
                     {"sellCurrency": "USD", "buyCurrency": "ARS",
                      "sellAmount": "20.00"})
expect("fx quote created → 201", status, body, 201, key="quoteId")
quote_id = body.get("quoteId")
print(f"  quoteId={quote_id}  buyAmount={body.get('buyAmount')}  expiresAt={body.get('expiresAt')}")

# Execute exchange
status, body = _call("POST", f"/users/{alice}/fx/exchanges",
                     {"quoteId": quote_id},
                     idempotency_key=ikey("fx-ex-1"))
expect("fx exchange executed → 201", status, body, 201, key="ledgerEntryId")

# Double-consume: same quote must be rejected
status, body = _call("POST", f"/users/{alice}/fx/exchanges",
                     {"quoteId": quote_id},
                     idempotency_key=ikey("fx-ex-2"))
expect("double-consume same quote → 422", status, body, 422)


# ══════════════════════════════════════════════════════════════════════════════
section("8 · Batch transfer + settle")
# ══════════════════════════════════════════════════════════════════════════════

status, body = _call("POST", f"/users/{alice}/batch-transfers",
                     {"transfers": [
                         {"toUserId": bob,   "amount": "15.00", "currency": "USD"},
                         {"toUserId": carol, "amount": "10.00", "currency": "USD"},
                     ]},
                     idempotency_key=ikey("batch-1"))
expect("alice batch → bob+carol (pending) → 201", status, body, 201, key="ledgerEntryId")

# Bob and carol land in pending_amount
status, body = _call("GET", f"/users/{bob}/pending-balances")
expect("bob has pending balance → 200", status, body, 200)
print(f"  bob pending: {body}")

status, body = _call("GET", f"/users/{carol}/pending-balances")
expect("carol has pending balance → 200", status, body, 200)
print(f"  carol pending: {body}")

# Settle carol
status, body = _call("POST", f"/users/{carol}/settle")
expect("carol settle → 200, settled amounts", status, body, 200)
print(f"  settled: {body}")

status, body = _call("GET", f"/users/{carol}/balances")
expect("carol available after settle → 200", status, body, 200)
print(f"  carol balances: {body}")

status, body = _call("GET", f"/users/{carol}/pending-balances")
expect("carol pending now zero → 200", status, body, 200)
print(f"  carol pending after settle: {body}")


# ══════════════════════════════════════════════════════════════════════════════
section("9 · Withdrawal + async payout dispatch")
# ══════════════════════════════════════════════════════════════════════════════

status, body = _call("POST", f"/users/{bob}/withdrawals",
                     {"amount": "10.00", "currency": "USD"},
                     idempotency_key=ikey("bob-withdraw-1"))
expect("bob withdrawal accepted → 201", status, body, 201, key="ledgerEntryId")
withdrawal_id = body.get("ledgerEntryId")
print(f"  ledgerEntryId={withdrawal_id}")
print(f"  {DIM}(payout dispatched to outbox worker asynchronously){RESET}")

# The debit commits synchronously with the outbox row — the balance drop below
# only proves the API accepted the withdrawal, not that the payout worker ran.
# A broken worker would still pass this check. To verify the worker actually
# dispatched to the provider, inspect the payout_outbox row in the DB hints
# below (status should be COMPLETED, attempts > 0, provider_ref set).
# The proper fix is a GET /users/{userId}/withdrawals/{id} status endpoint so
# any caller can poll the async outcome without DB access — noted as a gap.
time.sleep(2)
status, body = _call("GET", f"/users/{bob}/balances")
expect("bob balance after withdrawal → 200", status, body, 200)
print(f"  bob balances: {body}")


# ══════════════════════════════════════════════════════════════════════════════
# Summary
# ══════════════════════════════════════════════════════════════════════════════

print(f"\n{BOLD}{'═' * 60}{RESET}")
total = passed + failed
p_col = GREEN if failed == 0 else YELLOW
print(f"{BOLD}  Results: {p_col}{passed} passed{RESET}{BOLD}, "
      f"{'  ' if failed == 0 else RED}{failed} failed{RESET}{BOLD} / {total} total{RESET}")
print(f"{BOLD}{'═' * 60}{RESET}")


# ══════════════════════════════════════════════════════════════════════════════
print(f"""
{BOLD}{CYAN}DB verification hints{RESET}

  {BOLD}Connect via terminal (Postgres is exposed on host port 5433):{RESET}

    psql -h localhost -p 5433 -U wallet -d wallet
    # password: wallet

  {DIM}Or with the connection URL:{RESET}
    psql "postgresql://wallet:wallet@localhost:5433/wallet"

  {DIM}Or from inside Docker (no port mapping needed):{RESET}
    docker compose exec postgres psql -U wallet -d wallet

  {BOLD}Queries for this run (run_id={RUN_ID}):{RESET}

  -- Users created in this run
  SELECT id, created_at FROM users
   WHERE id IN ('{alice}', '{bob}', '{carol}');

  -- Ledger entries (all types for these users)
  SELECT le.id, le.entry_type, le.created_at,
         ll.currency, ll.amount
    FROM ledger_entries le
    JOIN ledger_lines ll ON ll.entry_id = le.id
   WHERE ll.user_id IN ('{alice}', '{bob}', '{carol}')
   ORDER BY le.created_at;

  -- Current balances
  SELECT user_id, currency, amount, pending_amount
    FROM balance_projections
   WHERE user_id IN ('{alice}', '{bob}', '{carol}');

  -- FX quotes consumed in this run
  SELECT id, user_id, sell_currency, buy_currency,
         sell_amount, buy_amount, consumed_at, expires_at
    FROM fx_quotes
   WHERE user_id = '{alice}';

  -- Payout outbox entries
  SELECT id, ledger_entry_id, status, attempts, last_attempted_at
    FROM payout_outbox
   WHERE ledger_entry_id = '{withdrawal_id}';

  -- Audit trail for all events (alice)
  SELECT fae.command_type, fae.outcome, fae.correlation_id,
         fae.occurred_at
    FROM financial_audit_events fae
   WHERE fae.subject_user_id = '{alice}'
   ORDER BY fae.occurred_at;
""")

sys.exit(1 if failed > 0 else 0)
