package dev.skewer.experiments;

import java.util.ArrayList;
import java.util.List;

import dev.skewer.report.Results.Cell;
import dev.skewer.report.Results.Expect;
import dev.skewer.workload.WorkloadConfig;

/**
 * The experiment matrix: six strategies at three isolation levels, the two Spring gotchas, and the follow-ups
 * the matrix sets up. Every hypothesis is the project plan's prediction, to be confirmed or refuted.
 */
public final class Experiments {

    /** A cell and the workload it runs. */
    public record Spec(Cell cell, WorkloadConfig config) {
    }

    private static final String RC = "read_committed";
    private static final String RR = "repeatable_read";
    private static final String S = "serializable";

    private static final String SERIALIZATION = "40001";
    private static final String DEADLOCK = "40P01";
    private static final String ABORTED_TXN = "25P02";

    private Experiments() {
    }

    public static List<Spec> all(WorkloadConfig base) {
        List<Spec> specs = new ArrayList<>();
        // The six strategies.
        add(specs, base, "matrix", "naive-jpa", RC, "Lost updates; invariants break",
                new Expect(true, true, null, true, null));
        add(specs, base, "matrix", "naive-jpa", RR,
                "No lost updates, but 40001 errors reach clients; joint rule broken by write skew",
                new Expect(false, true, null, true, List.of(SERIALIZATION)));
        add(specs, base, "matrix", "naive-jpa", S, "No anomalies; 40001 errors reach clients without retry",
                new Expect(false, false, false, false, List.of(SERIALIZATION)));
        add(specs, base, "matrix", "atomic-update", RC,
                "Single-account rule holds (the WHERE is re-checked after the lock); joint rule broken",
                new Expect(false, true, null, true, null));
        add(specs, base, "matrix", "atomic-update", RR, "Single holds, with 40001 on conflicts; joint broken",
                new Expect(false, true, null, true, List.of(SERIALIZATION)));
        add(specs, base, "matrix", "atomic-update", S, "Holds",
                new Expect(false, false, false, false, null));
        add(specs, base, "matrix", "pessimistic", RC, "Holds for both rules",
                new Expect(false, false, null, null, null));
        add(specs, base, "matrix", "pessimistic", RR, "Holds",
                new Expect(false, false, false, false, null));
        add(specs, base, "matrix", "pessimistic", S, "Holds, possibly with extra aborts",
                new Expect(false, false, false, false, null));
        add(specs, base, "matrix", "pessimistic-unordered", RC,
                "Holds, but deadlocks (40P01) on opposite-direction transfers",
                new Expect(false, false, null, null, List.of(DEADLOCK)));
        add(specs, base, "matrix", "pessimistic-unordered", RR, "Holds, but deadlocks (40P01)",
                new Expect(false, false, false, false, List.of(DEADLOCK)));
        add(specs, base, "matrix", "pessimistic-unordered", S, "Holds, but deadlocks (40P01)",
                new Expect(false, false, false, false, List.of(DEADLOCK)));
        add(specs, base, "matrix", "optimistic", RC,
                "Lost updates prevented; joint rule broken, because only the written row's version is checked",
                new Expect(false, true, null, true, null));
        add(specs, base, "matrix", "optimistic", RR, "Lost updates prevented; joint rule broken",
                new Expect(false, true, null, true, null));
        add(specs, base, "matrix", "optimistic", S, "Holds",
                new Expect(false, false, false, false, null));
        add(specs, base, "matrix", "serializable-retry", S,
                "No anomalies; the cost is an abort rate that rises with contention",
                new Expect(false, false, false, false, List.of(SERIALIZATION)));

        // Spring gotchas. Their isolation comes from the annotation, which asks for Serializable.
        add(specs, base, "gotcha", "gotcha-self-invocation", S,
                "No transaction at all: anomalies at every requested level",
                new Expect(true, null, null, true, null));
        add(specs, base, "gotcha", "gotcha-retry-inside", S,
                "Retries fail with \"current transaction is aborted\" (25P02)",
                new Expect(null, null, null, null, List.of(ABORTED_TXN)));

        // Follow-up 1: close the optimistic strategy's write-skew gap.
        add(specs, base, "followup", "optimistic-force-increment", RC,
                "OPTIMISTIC_FORCE_INCREMENT on the row only read closes the write-skew gap",
                new Expect(false, false, null, null, null));
        add(specs, base, "followup", "optimistic-force-increment", RR,
                "Closes the write-skew gap at Repeatable Read too",
                new Expect(false, false, null, null, null));

        // Follow-up 2: read skew. Optimistic transfers cannot lose updates, so what is left is the report's doing.
        WorkloadConfig reads = base.withMix(70, 0, 0, 30).withIntraCustomerPct(60);
        specs.add(spec("read-skew-split-rc", "followup", "optimistic", RC,
                "total_balance as two findById calls, Read Committed",
                "Two statements, two snapshots: read skew",
                new Expect(false, null, true, true, null), reads.withTotalMode("split")));
        specs.add(spec("read-skew-single-rc", "followup", "optimistic", RC,
                "total_balance as one SQL statement, Read Committed",
                "One statement, one snapshot: no read skew",
                new Expect(false, null, false, null, null), reads.withTotalMode("single")));
        specs.add(spec("read-skew-split-rr", "followup", "optimistic", RR,
                "total_balance as two findById calls, Repeatable Read",
                "One snapshot per transaction: no read skew",
                new Expect(false, null, false, null, null), reads.withTotalMode("split")));
        return specs;
    }

    private static void add(List<Spec> specs, WorkloadConfig config, String group, String strategy, String isolation,
                            String hypothesis, Expect expect) {
        specs.add(spec(strategy + "@" + isolation, group, strategy, isolation, strategy + " at " + label(isolation),
                hypothesis, expect, config));
    }

    private static Spec spec(String id, String group, String strategy, String isolation, String title,
                             String hypothesis, Expect expect, WorkloadConfig config) {
        return new Spec(new Cell(id, group, strategy, isolation, title, hypothesis, expect, config.describe()), config);
    }

    public static String label(String isolation) {
        return switch (isolation) {
            case RC -> "Read Committed";
            case RR -> "Repeatable Read";
            case S -> "Serializable";
            case "default" -> "@Transactional default";
            default -> isolation;
        };
    }
}
