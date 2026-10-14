package dev.skewer.checker;

import java.time.Instant;
import java.util.List;

import dev.skewer.checker.history.Read;

/**
 * A minimal, self-contained explanation of one anomaly: the transactions in the cycle, what each read and wrote,
 * the edges with their types, and when each ran. Everything the report needs to draw it.
 */
public record Counterexample(
        String anomalyClass,
        String label,
        String explanation,
        List<CxTxn> txns,
        List<CxEdge> edges) {

    /** A transaction in the cycle. Times are microseconds since the run started, from the client; null if unknown. */
    public record CxTxn(
            String id,
            String name,
            String op,
            String params,
            String strategy,
            String isolation,
            String outcome,
            List<Read> reads,
            List<CxWrite> writes,
            Instant committedAt,
            Long startMicros,
            Long endMicros) {
    }

    /** A version this transaction created: it replaced {@code prev}'s version of the account. */
    public record CxWrite(long account, String prev, long oldBalance, long newBalance) {
    }

    /** {@code from} and {@code to} are transaction names (T1, T2, ...). */
    public record CxEdge(String from, String to, String type, long account, String detail) {
    }
}
