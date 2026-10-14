package dev.skewer.checker.history;

import java.util.UUID;

/**
 * One committed row write, captured by the {@code account_write_audit} trigger from the row version the
 * database actually overwrote. {@code seq} is only a display tiebreaker: sequences are not rolled back on abort
 * and are not handed out in commit order, so version order always comes from {@code prevWriter}.
 *
 * @param dbXid the Postgres transaction that wrote it ({@code pg_current_xact_id()}); null if not recorded
 */
public record Write(long seq, long account, UUID prevWriter, UUID newWriter, long oldBalance, long newBalance,
                    String dbXid) {

    public Write(long seq, long account, UUID prevWriter, UUID newWriter, long oldBalance, long newBalance) {
        this(seq, account, prevWriter, newWriter, oldBalance, newBalance, null);
    }
}
