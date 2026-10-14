package dev.skewer.checker.history;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A committed application transaction.
 *
 * @param source    {@code log} when the row came from {@code txn_log} (it exists iff the transaction committed),
 *                  {@code client} for read-only transactions the workload recorded from the response
 * @param outcome   {@code applied} or {@code rejected} (a business rule said no; the reads still happened)
 * @param isolation the level that actually ran, from {@code SHOW transaction_isolation}
 * @param dbXid     the Postgres transaction that inserted the txn_log row; null for client-recorded reports
 */
public record Txn(
        UUID id,
        String opId,
        String op,
        String strategy,
        String isolation,
        String outcome,
        List<Read> reads,
        List<Delta> deltas,
        String source,
        Instant committedAt,
        String dbXid) {

    public Txn {
        reads = List.copyOf(reads);
        deltas = List.copyOf(deltas);
    }

    public Txn(UUID id, String opId, String op, String strategy, String isolation, String outcome, List<Read> reads,
               List<Delta> deltas, String source, Instant committedAt) {
        this(id, opId, op, strategy, isolation, outcome, reads, deltas, source, committedAt, null);
    }

    public boolean readOnly() {
        return deltas.isEmpty();
    }
}
