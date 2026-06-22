-- Hardening migration. Closes correctness gaps found in review and adds the
-- system-account boundary that lets money enter the ledger via a deposit.

-- At most one reversal per original transaction. The service also checks first,
-- but that check is not race-safe under READ COMMITTED; this constraint is what
-- actually prevents a concurrent double-reversal (two compensating transactions
-- for the same original). A partial index allows the many NULLs (non-reversals).
CREATE UNIQUE INDEX uq_transactions_reverses
    ON transactions (reverses_tx_id) WHERE reverses_tx_id IS NOT NULL;

-- A system account is the external boundary money enters through (a deposit
-- debits it, crediting a user account). It is the only account permitted to run
-- negative; its negative balance equals the total issued into the ledger, so the
-- system-wide SUM stays zero. At most one system account per currency.
ALTER TABLE accounts ADD COLUMN is_system BOOLEAN NOT NULL DEFAULT FALSE;
CREATE UNIQUE INDEX uq_accounts_system_currency
    ON accounts (currency) WHERE is_system;

-- Entry currency hygiene (parity with accounts.currency) and an outbox event-type
-- guard (parity with the transactions type/status enums) so a typo can't produce
-- a silently-unrouted event.
ALTER TABLE entries ADD CONSTRAINT entries_currency_upper CHECK (currency = upper(currency));
ALTER TABLE outbox  ADD CONSTRAINT outbox_event_type_valid
    CHECK (event_type IN ('TRANSFER_POSTED', 'REVERSAL_POSTED'));

-- The balance invariant must hold per currency, not just in aggregate: a
-- +100 USD / -100 EUR transaction nets to zero overall yet leaks value between
-- currencies. Re-check SUM(amount_minor) = 0 within each currency of the
-- transaction. (The application already enforces single-currency transfers; this
-- makes the database itself the backstop.)
CREATE OR REPLACE FUNCTION assert_tx_balanced() RETURNS trigger AS $$
DECLARE
    leg_cnt INT;
    bad     RECORD;
BEGIN
    SELECT COUNT(*) INTO leg_cnt FROM entries WHERE transaction_id = NEW.transaction_id;
    IF leg_cnt < 2 THEN
        RAISE EXCEPTION 'transaction % has % entries; double-entry needs >= 2',
            NEW.transaction_id, leg_cnt;
    END IF;
    FOR bad IN
        SELECT currency, SUM(amount_minor) AS leg_sum
          FROM entries
         WHERE transaction_id = NEW.transaction_id
         GROUP BY currency
        HAVING SUM(amount_minor) <> 0
    LOOP
        RAISE EXCEPTION 'transaction % is unbalanced in %: SUM(amount_minor) = %',
            NEW.transaction_id, bad.currency, bad.leg_sum;
    END LOOP;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;
