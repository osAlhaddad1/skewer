package dev.skewer.workload;

import java.util.Map;
import java.util.TreeMap;

import org.HdrHistogram.Histogram;

import dev.skewer.checker.history.ClientOp;

/**
 * Cost of a run, from the client's side.
 *
 * @param failures               operations that ended failed, by SQLSTATE
 * @param aborts                 every aborted attempt by SQLSTATE: the ones a retry recovered from and the final ones
 * @param indeterminateCommitted timed-out operations that txn_log shows did commit
 * @param throughput             committed operations (applied plus rejected) per second
 */
public record RunMetrics(
        int ops,
        int applied,
        int rejected,
        int failed,
        int indeterminate,
        int indeterminateCommitted,
        Map<String, Integer> failures,
        Map<String, Integer> aborts,
        int attempts,
        int retries,
        long durationMs,
        double throughput,
        double p50Ms,
        double p99Ms,
        double maxMs) {

    public static final String DEADLOCK = "40P01";
    public static final String SERIALIZATION_FAILURE = "40001";

    public int abortedAttempts() {
        return aborts.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** Aborted attempts over all attempts. */
    public double abortRate() {
        return attempts == 0 ? 0 : (double) abortedAttempts() / attempts;
    }

    /** Extra attempts per operation. */
    public double retryRate() {
        return ops == 0 ? 0 : (double) retries / ops;
    }

    public int deadlocks() {
        return aborts.getOrDefault(DEADLOCK, 0);
    }

    public int serializationFailures() {
        return aborts.getOrDefault(SERIALIZATION_FAILURE, 0);
    }

    static RunMetrics of(Iterable<ClientOp> ops, Histogram latencyMicros, long durationMs, int indeterminateCommitted) {
        int n = 0, applied = 0, rejected = 0, failed = 0, indeterminate = 0, attempts = 0;
        Map<String, Integer> failures = new TreeMap<>();
        Map<String, Integer> aborts = new TreeMap<>();
        for (ClientOp op : ops) {
            n++;
            attempts += Math.max(1, op.attempts());
            for (String e : op.attemptErrors()) {
                aborts.merge(e, 1, Integer::sum);
            }
            switch (op.outcome()) {
                case "applied" -> applied++;
                case "rejected" -> rejected++;
                case "indeterminate" -> indeterminate++;
                default -> {
                    failed++;
                    String state = String.valueOf(op.sqlState());
                    failures.merge(state, 1, Integer::sum);
                    aborts.merge(state, 1, Integer::sum);
                }
            }
        }
        double seconds = Math.max(1, durationMs) / 1000.0;
        return new RunMetrics(n, applied, rejected, failed, indeterminate, indeterminateCommitted, failures, aborts,
                attempts, attempts - n, durationMs, (applied + rejected) / seconds,
                latencyMicros.getValueAtPercentile(50) / 1000.0, latencyMicros.getValueAtPercentile(99) / 1000.0,
                latencyMicros.getMaxValue() / 1000.0);
    }
}
