package dev.skewer.bank.api;

import java.util.List;
import java.util.UUID;

import dev.skewer.checker.history.Delta;
import dev.skewer.checker.history.Read;

/**
 * What happened to one operation.
 *
 * @param outcome       applied, rejected (a business rule said no) or failed (the database aborted it)
 * @param txnId         the transaction id of the last attempt
 * @param isolation     the level that actually ran, from SHOW transaction_isolation
 * @param reads         the versions the last attempt read; read-only operations are recorded from this
 * @param attempts      1 unless a retry wrapper ran
 * @param attemptErrors SQLSTATE of every aborted attempt, including those a retry recovered from
 * @param sqlState      for failed: the error that ended the operation
 */
public record OpResult(
        String opId,
        String outcome,
        UUID txnId,
        String isolation,
        List<Read> reads,
        List<Delta> deltas,
        Long totalCents,
        int attempts,
        List<String> attemptErrors,
        String sqlState,
        String message) {

    public static final String APPLIED = "applied";
    public static final String REJECTED = "rejected";
    public static final String FAILED = "failed";

    public boolean failed() {
        return FAILED.equals(outcome);
    }

    public OpResult withAttempts(int attempts, List<String> attemptErrors) {
        return new OpResult(opId, outcome, txnId, isolation, reads, deltas, totalCents, attempts, attemptErrors,
                sqlState, message);
    }
}
