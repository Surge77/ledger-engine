-- Double-entry ledger schema. Money is BIGINT minor units (paise); never float.
-- Balances are DERIVED from entries (SUM); no mutable balance column exists.

CREATE TABLE accounts (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       TEXT        NOT NULL,
    currency   CHAR(3)     NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT accounts_currency_upper CHECK (currency = upper(currency)),
    CONSTRAINT accounts_name_not_blank CHECK (length(btrim(name)) > 0)
);

CREATE TABLE transactions (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    idempotency_key TEXT UNIQUE,
    type            TEXT        NOT NULL,   -- TRANSFER | REVERSAL
    status          TEXT        NOT NULL,   -- POSTED
    reverses_tx_id  BIGINT      REFERENCES transactions(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT transactions_type_valid   CHECK (type IN ('TRANSFER', 'REVERSAL')),
    CONSTRAINT transactions_status_valid CHECK (status IN ('POSTED'))
);

-- amount_minor: positive = credit, negative = debit. Per-transaction SUM must be 0.
CREATE TABLE entries (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transaction_id BIGINT      NOT NULL REFERENCES transactions(id),
    account_id     BIGINT      NOT NULL REFERENCES accounts(id),
    amount_minor   BIGINT      NOT NULL,
    direction      TEXT        NOT NULL,   -- CREDIT | DEBIT
    currency       CHAR(3)     NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT entries_amount_nonzero CHECK (amount_minor <> 0),
    CONSTRAINT entries_direction_sign CHECK (
        (direction = 'CREDIT' AND amount_minor > 0) OR
        (direction = 'DEBIT'  AND amount_minor < 0)
    )
);

CREATE INDEX idx_entries_account ON entries (account_id, id);
CREATE INDEX idx_entries_tx      ON entries (transaction_id);

CREATE TABLE outbox (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transaction_id BIGINT      NOT NULL REFERENCES transactions(id),
    event_type     TEXT        NOT NULL,
    payload        JSONB       NOT NULL,
    published_at   TIMESTAMPTZ,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Partial index so the poller scans only unpublished rows.
CREATE INDEX idx_outbox_unpublished ON outbox (id) WHERE published_at IS NULL;

-- DB-level guarantee of the double-entry invariant. DEFERRED so it runs once at
-- COMMIT, when all of a transaction's entries are present: SUM(amount_minor) = 0.
CREATE FUNCTION assert_tx_balanced() RETURNS trigger AS $$
DECLARE
    leg_sum BIGINT;
    leg_cnt INT;
BEGIN
    SELECT COALESCE(SUM(amount_minor), 0), COUNT(*)
      INTO leg_sum, leg_cnt
      FROM entries
     WHERE transaction_id = NEW.transaction_id;
    IF leg_cnt < 2 THEN
        RAISE EXCEPTION 'transaction % has % entries; double-entry needs >= 2',
            NEW.transaction_id, leg_cnt;
    END IF;
    IF leg_sum <> 0 THEN
        RAISE EXCEPTION 'transaction % is unbalanced: SUM(amount_minor) = %',
            NEW.transaction_id, leg_sum;
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER entries_balanced
    AFTER INSERT ON entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION assert_tx_balanced();
