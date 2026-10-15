package dev.skewer.bank.admin;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import dev.skewer.checker.history.Account;
import dev.skewer.checker.history.Delta;
import dev.skewer.checker.history.HistoryIO;
import dev.skewer.checker.history.Ids;
import dev.skewer.checker.history.Read;
import dev.skewer.checker.history.Txn;
import dev.skewer.checker.history.Write;
import tools.jackson.core.type.TypeReference;

/** Run setup and teardown: seed the accounts, export what was recorded, switch the audit trigger. */
@Service
public class BankAdmin {

    private static final TypeReference<List<Read>> READS = new TypeReference<>() {
    };
    private static final TypeReference<List<Delta>> DELTAS = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;

    public BankAdmin(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Wipes the previous run and seeds fresh accounts, each version written by {@link Ids#INIT}. */
    @Transactional
    public void reset(List<Account> accounts) {
        jdbc.execute("TRUNCATE txn_log, write_audit, account RESTART IDENTITY");
        jdbc.execute("ALTER TABLE account ENABLE TRIGGER account_write_audit");
        jdbc.batchUpdate("INSERT INTO account (id, customer_id, joint, balance_cents, version, last_writer) VALUES (?, ?, ?, ?, 0, ?)",
                accounts.stream().map(a -> new Object[]{a.id(), a.customerId(), a.joint(), a.initialBalance(), Ids.INIT}).toList());
    }

    /** Throughput baselines run without the audit trigger; the checker cannot analyse those runs. */
    public void instrumentation(boolean enabled) {
        jdbc.execute("ALTER TABLE account " + (enabled ? "ENABLE" : "DISABLE") + " TRIGGER account_write_audit");
    }

    /** One consistent snapshot of everything recorded, taken after the workload has stopped. */
    @Transactional(isolation = Isolation.REPEATABLE_READ, readOnly = true)
    public ServerHistory export() {
        List<Txn> txns = jdbc.query("""
                SELECT txn_id, op_id, op, strategy, isolation, outcome, reads::text AS reads, deltas::text AS deltas,
                       committed_at, db_xid::text AS db_xid
                  FROM txn_log ORDER BY committed_at, txn_id
                """, (rs, n) -> new Txn(
                rs.getObject("txn_id", UUID.class),
                rs.getString("op_id"),
                rs.getString("op"),
                rs.getString("strategy"),
                rs.getString("isolation"),
                rs.getString("outcome"),
                HistoryIO.JSON.readValue(rs.getString("reads"), READS),
                HistoryIO.JSON.readValue(rs.getString("deltas"), DELTAS),
                "log",
                rs.getObject("committed_at", OffsetDateTime.class).toInstant(),
                rs.getString("db_xid")));
        List<Write> writes = jdbc.query("""
                SELECT seq, account_id, prev_writer, new_writer, old_balance, new_balance, db_xid::text AS db_xid
                  FROM write_audit ORDER BY seq
                """, (rs, n) -> new Write(rs.getLong("seq"), rs.getLong("account_id"),
                rs.getObject("prev_writer", UUID.class), rs.getObject("new_writer", UUID.class),
                rs.getLong("old_balance"), rs.getLong("new_balance"), rs.getString("db_xid")));
        Map<Long, Long> finals = new LinkedHashMap<>();
        jdbc.query("SELECT id, balance_cents FROM account ORDER BY id",
                rs -> {
                    finals.put(rs.getLong("id"), rs.getLong("balance_cents"));
                });
        return new ServerHistory(txns, writes, finals);
    }
}
