package dev.skewer.bank.core;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import dev.skewer.bank.api.OpRequest;
import dev.skewer.checker.history.HistoryIO;

/**
 * Instrumentation shared by every strategy, so the comparison is fair. Runs as a transaction's last statements:
 * record the isolation level that actually ran, then insert the txn_log row that proves the commit.
 */
@Component
public class TxnLogger {

    private final JdbcTemplate jdbc;

    public TxnLogger(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void finish(TxContext ctx) {
        OpRequest req = ctx.request();
        if (!req.instrumented()) {
            return;
        }
        // The level that ran, not the one the annotation asked for.
        ctx.isolation(jdbc.queryForObject("SHOW transaction_isolation", String.class));
        if (OpRequest.TOTAL_BALANCE.equals(req.op())) {
            // Read-only: the workload records it from the response instead.
            return;
        }
        jdbc.update("""
                INSERT INTO txn_log (txn_id, op_id, op, strategy, isolation, outcome, reads, deltas)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
                """, ctx.txnId(), req.opId(), req.op(), req.strategy(), ctx.isolationOrUnknown(), ctx.outcome(),
                HistoryIO.JSON.writeValueAsString(ctx.reads()), HistoryIO.JSON.writeValueAsString(ctx.deltas()));
    }
}
