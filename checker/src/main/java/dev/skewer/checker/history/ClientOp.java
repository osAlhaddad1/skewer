package dev.skewer.checker.history;

import java.util.List;
import java.util.UUID;

/**
 * What the workload client saw for one operation.
 *
 * @param outcome       {@code applied}, {@code rejected}, {@code failed}, or {@code indeterminate} (client timeout:
 *                      the transaction may or may not have committed; {@code txn_log} decides)
 * @param startMicros   start time, microseconds since the run started
 * @param attemptErrors SQLSTATEs of attempts that aborted, including ones a retry wrapper recovered from
 */
public record ClientOp(
        String opId,
        int client,
        String op,
        String params,
        String outcome,
        String sqlState,
        UUID txnId,
        int attempts,
        List<String> attemptErrors,
        long startMicros,
        long endMicros) {

    public ClientOp {
        attemptErrors = attemptErrors == null ? List.of() : List.copyOf(attemptErrors);
    }
}
