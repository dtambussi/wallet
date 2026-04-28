-- Baseline schema for fresh environments.

CREATE TABLE users (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Derived current-balance view from ledger entries, updated in the same transaction as each posting.
CREATE TABLE balance_projections (
    user_id UUID NOT NULL REFERENCES users (id),
    currency VARCHAR(3) NOT NULL,
    amount NUMERIC(38, 18) NOT NULL DEFAULT 0,
    pending_amount NUMERIC(38, 18) NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, currency)
);

COMMENT ON COLUMN balance_projections.pending_amount IS
    'Uncleared inbound transfer credits. Not spendable until moved to amount via settlement.';

CREATE TABLE ledger_entries (
    id UUID PRIMARY KEY,
    idempotency_key VARCHAR(200) NOT NULL UNIQUE,
    idempotency_fingerprint VARCHAR(128) NOT NULL DEFAULT '',
    entry_type VARCHAR(32) NOT NULL,
    metadata JSONB,
    correlation_id VARCHAR(64) NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON COLUMN ledger_entries.idempotency_fingerprint IS
    'SHA-256 hex of canonical request payload; used to detect Idempotency-Key reuse with a different body.';

COMMENT ON COLUMN ledger_entries.correlation_id IS
    'HTTP X-Request-Id (or server-generated). Empty string for legacy rows. Used for end-to-end trace and audit.';

CREATE TABLE ledger_lines (
    id UUID PRIMARY KEY,
    entry_id UUID NOT NULL REFERENCES ledger_entries (id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users (id),
    currency VARCHAR(3) NOT NULL,
    amount NUMERIC(38, 18) NOT NULL
);

CREATE TABLE fx_quotes (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id),
    sell_currency VARCHAR(3) NOT NULL,
    buy_currency VARCHAR(3) NOT NULL,
    sell_amount NUMERIC(38, 18) NOT NULL,
    buy_amount NUMERIC(38, 18) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    priced_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    pricing_source TEXT NOT NULL DEFAULT 'unknown',
    served_from_stale BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON COLUMN fx_quotes.priced_at IS
    'When the rate used for this quote was considered valid (as-of from provider).';

COMMENT ON COLUMN fx_quotes.pricing_source IS
    'Logical FX source id, e.g. MockFx.';

COMMENT ON COLUMN fx_quotes.served_from_stale IS
    'True if the rate came from last-known cache after provider failure (normally false for quotes).';

-- Append-only command outcome log: records every command decision, including rejections.
CREATE TABLE financial_audit_events (
    id UUID PRIMARY KEY,
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    correlation_id VARCHAR(64) NOT NULL,
    subject_user_id UUID NOT NULL,
    command_type VARCHAR(64) NOT NULL,
    outcome VARCHAR(64) NOT NULL,
    ledger_entry_id UUID REFERENCES ledger_entries (id),
    idempotency_key VARCHAR(200),
    details JSONB NOT NULL DEFAULT '{}'::jsonb
);

COMMENT ON TABLE financial_audit_events IS
    'Append-only wallet command audit: outcome per request (success, replay, business rejection). Pairs with ledger_entries for money movements.';

COMMENT ON COLUMN financial_audit_events.subject_user_id IS
    'User id from the request path (wallet scope); not an authenticated identity when auth is not deployed.';

CREATE TABLE payout_outbox (
    id UUID PRIMARY KEY,
    ledger_entry_id UUID NOT NULL REFERENCES ledger_entries(id),
    user_id UUID NOT NULL,
    currency VARCHAR(10) NOT NULL,
    amount NUMERIC(30,18) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',  -- PENDING | SUCCEEDED | FAILED
    attempts INT NOT NULL DEFAULT 0,
    provider_ref VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_attempted_at TIMESTAMPTZ,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE runtime_config (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE runtime_config IS 'Small runtime-tunable operational policy knobs.';
COMMENT ON COLUMN runtime_config.key IS 'Config key, e.g. wallet.fx.stale-rate-ttl-seconds';
COMMENT ON COLUMN runtime_config.value IS 'String value parsed by the owning component.';

INSERT INTO runtime_config (key, value)
VALUES ('wallet.fx.stale-rate-ttl-seconds', '0')
ON CONFLICT (key) DO NOTHING;

CREATE INDEX idx_ledger_entries_created ON ledger_entries (created_at DESC);
CREATE INDEX idx_ledger_entries_correlation_id ON ledger_entries (correlation_id)
    WHERE correlation_id <> '';
CREATE INDEX idx_ledger_entries_type_created ON ledger_entries (entry_type, created_at DESC);

CREATE INDEX idx_ledger_lines_user ON ledger_lines (user_id);
CREATE INDEX idx_ledger_lines_entry ON ledger_lines (entry_id);
CREATE INDEX idx_ledger_lines_user_currency_entry
    ON ledger_lines (user_id, currency, entry_id);

CREATE INDEX idx_fx_quotes_user ON fx_quotes (user_id);

CREATE INDEX idx_financial_audit_correlation ON financial_audit_events (correlation_id);
CREATE INDEX idx_financial_audit_occurred ON financial_audit_events (occurred_at DESC);
CREATE INDEX idx_financial_audit_subject ON financial_audit_events (subject_user_id);
CREATE INDEX idx_financial_audit_ledger ON financial_audit_events (ledger_entry_id)
    WHERE ledger_entry_id IS NOT NULL;
CREATE INDEX idx_financial_audit_command ON financial_audit_events (command_type, occurred_at DESC);

CREATE INDEX payout_outbox_pending_idx
    ON payout_outbox (next_attempt_at) WHERE status = 'PENDING';
