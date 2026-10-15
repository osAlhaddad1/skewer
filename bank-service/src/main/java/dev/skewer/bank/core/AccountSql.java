package dev.skewer.bank.core;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * The SQL the JDBC strategies are built from. Every read returns {@code last_writer}, so the caller knows exactly
 * which version it saw; every write sets {@code last_writer} to the writing transaction.
 */
@Component
public class AccountSql {

    private static final String COLUMNS = "id, customer_id, balance_cents, last_writer";
    private static final RowMapper<AccountRow> ROW = (rs, n) -> new AccountRow(
            rs.getLong("id"), rs.getLong("customer_id"), rs.getLong("balance_cents"), rs.getObject("last_writer", UUID.class));

    /** A version an atomic UPDATE replaced, from Postgres 18's {@code RETURNING old.*}. */
    public record Replaced(long balanceCents, UUID writer) {
    }

    /** Joint debit result: the target's replaced version and the sibling version the statement checked. */
    public record JointDebit(Replaced target, AccountRow sibling) {
    }

    private final JdbcTemplate jdbc;

    public AccountSql(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public AccountRow select(long id) {
        return jdbc.queryForObject("SELECT " + COLUMNS + " FROM account WHERE id = ?", ROW, id);
    }

    public AccountRow selectForUpdate(long id) {
        return jdbc.queryForObject("SELECT " + COLUMNS + " FROM account WHERE id = ? FOR UPDATE", ROW, id);
    }

    public AccountRow selectForShare(long id) {
        return jdbc.queryForObject("SELECT " + COLUMNS + " FROM account WHERE id = ? FOR SHARE", ROW, id);
    }

    /** All of a customer's accounts in one statement, so one snapshot even at Read Committed. */
    public List<AccountRow> customerAccounts(long customer) {
        return jdbc.query("SELECT " + COLUMNS + " FROM account WHERE customer_id = ? ORDER BY id", ROW, customer);
    }

    /** Account ids never change, so reading them carries no version. */
    public List<Long> customerAccountIds(long customer) {
        return jdbc.queryForList("SELECT id FROM account WHERE customer_id = ? ORDER BY id", Long.class, customer);
    }

    public long customerOf(long account) {
        return jdbc.queryForObject("SELECT customer_id FROM account WHERE id = ?", Long.class, account);
    }

    public void setBalance(long id, long balanceCents, UUID txnId) {
        jdbc.update("UPDATE account SET balance_cents = ?, last_writer = ? WHERE id = ?", balanceCents, txnId, id);
    }

    /**
     * {@code UPDATE ... WHERE balance_cents >= :amount}. At Read Committed a blocked UPDATE re-checks its WHERE
     * clause against the newest row version once the lock is free, so the account rule holds.
     */
    public Optional<Replaced> debitIfCovered(long id, long amount, UUID txnId) {
        List<Replaced> rows = jdbc.query("""
                UPDATE account SET balance_cents = balance_cents - ?, last_writer = ?
                 WHERE id = ? AND balance_cents >= ?
                RETURNING old.balance_cents AS old_balance, old.last_writer AS old_writer
                """, (rs, n) -> new Replaced(rs.getLong("old_balance"), rs.getObject("old_writer", UUID.class)),
                amount, txnId, id, amount);
        return rows.stream().findFirst();
    }

    public Replaced credit(long id, long amount, UUID txnId) {
        return jdbc.queryForObject("""
                UPDATE account SET balance_cents = balance_cents + ?, last_writer = ?
                 WHERE id = ?
                RETURNING old.balance_cents AS old_balance, old.last_writer AS old_writer
                """, (rs, n) -> new Replaced(rs.getLong("old_balance"), rs.getObject("old_writer", UUID.class)),
                amount, txnId, id);
    }

    /**
     * One statement for the customer rule: debit the target if the customer's accounts summed still cover it.
     * The re-check after a lock wait only re-reads the target row; the sibling comes from the statement's
     * snapshot, and the sibling is never locked, so two such debits on different accounts never collide.
     */
    public Optional<JointDebit> jointDebitIfCovered(long id, long customer, long amount, UUID txnId) {
        List<JointDebit> rows = jdbc.query("""
                WITH sibling AS (
                    SELECT id, customer_id, balance_cents, last_writer FROM account WHERE customer_id = ? AND id <> ?
                )
                UPDATE account a SET balance_cents = a.balance_cents - ?, last_writer = ?
                 WHERE a.id = ?
                   AND a.balance_cents + (SELECT coalesce(sum(balance_cents), 0) FROM sibling) >= ?
                RETURNING old.balance_cents AS old_balance, old.last_writer AS old_writer,
                          (SELECT id FROM sibling ORDER BY id LIMIT 1) AS sib_id,
                          (SELECT balance_cents FROM sibling ORDER BY id LIMIT 1) AS sib_balance,
                          (SELECT last_writer FROM sibling ORDER BY id LIMIT 1) AS sib_writer
                """, (rs, n) -> new JointDebit(
                        new Replaced(rs.getLong("old_balance"), rs.getObject("old_writer", UUID.class)),
                        rs.getObject("sib_id") == null ? null : new AccountRow(rs.getLong("sib_id"), customer,
                                rs.getLong("sib_balance"), rs.getObject("sib_writer", UUID.class))),
                customer, id, amount, txnId, id, amount);
        return rows.stream().findFirst();
    }
}
