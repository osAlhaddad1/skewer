-- Skewer's banking schema plus the instrumentation the checker relies on.

CREATE TABLE account (
    id            BIGINT  PRIMARY KEY,
    customer_id   BIGINT  NOT NULL,
    joint         BOOLEAN NOT NULL DEFAULT false, -- joint: customer rule; otherwise account rule
    balance_cents BIGINT  NOT NULL,
    version       BIGINT  NOT NULL DEFAULT 0,     -- used only by the optimistic strategies
    last_writer   UUID    NOT NULL                -- transaction that wrote this row version
);

CREATE INDEX account_customer_idx ON account (customer_id);

-- One row per committed application transaction, inserted as its last statement:
-- the row exists if and only if the transaction committed.
CREATE TABLE txn_log (
    txn_id       UUID        PRIMARY KEY,
    op_id        TEXT        NOT NULL,           -- the client's operation id; resolves indeterminate operations
    op           TEXT        NOT NULL,           -- transfer | withdraw | joint_withdraw
    strategy     TEXT        NOT NULL,
    isolation    TEXT        NOT NULL,           -- from SHOW transaction_isolation
    outcome      TEXT        NOT NULL,           -- applied | rejected
    reads        JSONB       NOT NULL,           -- [{"account": 1, "saw": "<writer uuid>", "balance": 10000}]
    deltas       JSONB       NOT NULL,           -- [{"account": 1, "cents": -6000}]
    committed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    db_xid       XID8        NOT NULL DEFAULT pg_current_xact_id() -- the database transaction that logged it
);

CREATE INDEX txn_log_op_idx ON txn_log (op_id);

-- Version chain: every committed write records the version it replaced.
-- db_xid in both tables proves atomicity: an application transaction whose writes carry a different database
-- transaction id than its txn_log row did not run as one transaction (the self-invocation gotcha).
-- seq is a display tiebreaker only: sequences are not rolled back and are not handed out in commit order.
CREATE TABLE write_audit (
    seq         BIGSERIAL PRIMARY KEY,
    account_id  BIGINT    NOT NULL,
    prev_writer UUID      NOT NULL,
    new_writer  UUID      NOT NULL,
    old_balance BIGINT    NOT NULL,
    new_balance BIGINT    NOT NULL,
    db_xid      XID8      NOT NULL DEFAULT pg_current_xact_id() -- the database transaction that wrote it
);

-- OLD is the row version the database actually overwrote, not the one the application believes it read.
-- Audit rows of aborted transactions roll back with them, so only committed writes remain.
CREATE FUNCTION audit_account_write() RETURNS trigger AS $$
BEGIN
    INSERT INTO write_audit (account_id, prev_writer, new_writer, old_balance, new_balance)
    VALUES (OLD.id, OLD.last_writer, NEW.last_writer, OLD.balance_cents, NEW.balance_cents);
    RETURN NEW;
END
$$ LANGUAGE plpgsql;

CREATE TRIGGER account_write_audit
    AFTER UPDATE ON account
    FOR EACH ROW EXECUTE FUNCTION audit_account_write();
