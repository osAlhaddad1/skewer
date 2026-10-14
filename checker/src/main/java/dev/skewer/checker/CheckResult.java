package dev.skewer.checker;

import java.util.List;
import java.util.Map;

/**
 * Everything the checker concluded about one history.
 *
 * @param observedIsolation  how many transactions actually ran at each level ({@code SHOW transaction_isolation})
 * @param anomalies          distinct cycles found, by Adya class code
 * @param labels             the same cycles by banking name: lost update, read skew, write skew
 * @param instrumentationIssues things that should be impossible if Skewer's recording is right
 * @param truncated          the cycle search hit its cap, so anomaly counts are lower bounds
 */
public record CheckResult(
        String runId,
        String strategy,
        String requestedIsolation,
        Map<String, Long> observedIsolation,
        int transactions,
        Map<String, Integer> edges,
        List<Violation> violations,
        Map<String, Integer> anomalies,
        Map<String, Integer> labels,
        List<Counterexample> counterexamples,
        List<String> instrumentationIssues,
        boolean truncated,
        long checkMillis) {

    public int anomalyCount() {
        return anomalies.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** No invariant broken and no cycle found. */
    public boolean clean() {
        return violations.isEmpty() && anomalyCount() == 0;
    }

    /**
     * An invariant broke but no cycle explains it, or a G0/G1c cycle appeared although every operation ran as one
     * database transaction: suspect Skewer, not Postgres.
     */
    public boolean suspicious() {
        boolean atomic = violations.stream().noneMatch(v -> v.kind().equals("atomicity"));
        boolean impossible = atomic && (anomalies.getOrDefault(AnomalyClass.G0.code(), 0) > 0
                || anomalies.getOrDefault(AnomalyClass.G1C.code(), 0) > 0);
        boolean unexplained = !violations.isEmpty() && anomalyCount() == 0 && instrumentationIssues.isEmpty();
        return impossible || unexplained;
    }
}
