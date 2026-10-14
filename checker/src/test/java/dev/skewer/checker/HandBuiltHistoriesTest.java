package dev.skewer.checker;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import dev.skewer.checker.Counterexample.CxEdge;
import dev.skewer.checker.history.History;

/** One history per anomaly class plus clean ones: exact classification and exact cycle. */
class HandBuiltHistoriesTest {

    @Test
    void lostUpdateIsGSingleAndBreaksConservation() {
        // Both withdrawals read €100 and both write €40: one withdrawal vanishes, €60 is created.
        History h = new HistoryBuilder()
                .single(1, 1, 100_00)
                .write(1, "init", "T1", 100_00, 40_00)
                .write(1, "T1", "T2", 40_00, 40_00)
                .txn("T1", "withdraw").read(1, "init", 100_00).delta(1, -60_00).commit()
                .txn("T2", "withdraw").read(1, "init", 100_00).delta(1, -60_00).commit()
                .build();

        CheckResult r = Checker.check(h);

        assertThat(r.anomalies()).containsEntry("G-single", 1).containsEntry("G0", 0).containsEntry("G1c", 0)
                .containsEntry("G2-item", 0);
        assertThat(r.labels()).containsEntry("lost update", 1);
        assertThat(cycleEdges(r)).containsExactlyInAnyOrder("T1-ww-T2", "T2-rw-T1");
        assertThat(kinds(r)).contains("conservation", "exactly-once");
        assertThat(r.violations().stream().filter(v -> v.kind().equals("conservation")).findFirst().orElseThrow().cents())
                .isEqualTo(60_00);
        assertThat(r.instrumentationIssues()).isEmpty();
    }

    @Test
    void writeSkewIsG2ItemAndBreaksCustomerRule() {
        // Joint accounts €50 + €50. Each withdrawal of €80 checks the sum (€100), both pass, the sum ends at -€60.
        History h = new HistoryBuilder()
                .joint(1, 1, 50_00).joint(2, 1, 50_00)
                .write(1, "init", "T1", 50_00, -30_00)
                .write(2, "init", "T2", 50_00, -30_00)
                .txn("T1", "joint_withdraw").read(1, "init", 50_00).read(2, "init", 50_00).delta(1, -80_00).commit()
                .txn("T2", "joint_withdraw").read(1, "init", 50_00).read(2, "init", 50_00).delta(2, -80_00).commit()
                .build();

        CheckResult r = Checker.check(h);

        assertThat(r.anomalies()).containsEntry("G2-item", 1).containsEntry("G-single", 0);
        assertThat(r.labels()).containsEntry("write skew", 1);
        assertThat(cycleEdges(r)).containsExactlyInAnyOrder("T1-rw-T2", "T2-rw-T1");
        assertThat(kinds(r)).containsExactly("customer-rule");
    }

    @Test
    void readSkewIsGSingleAndBreaksReadConsistency() {
        // A transfer moves €60 from account 1 to 2; the report reads account 1 before it and account 2 after it.
        History h = new HistoryBuilder()
                .single(1, 1, 100_00).single(2, 1, 100_00)
                .write(1, "init", "T1", 100_00, 40_00)
                .write(2, "init", "T1", 100_00, 160_00)
                .txn("T1", "transfer").read(1, "init", 100_00).read(2, "init", 100_00)
                    .delta(1, -60_00).delta(2, 60_00).commit()
                .txn("R", "total_balance").read(1, "init", 100_00).read(2, "T1", 160_00).commit()
                .build();

        CheckResult r = Checker.check(h);

        assertThat(r.anomalies()).containsEntry("G-single", 1).containsEntry("G2-item", 0);
        assertThat(r.labels()).containsEntry("read skew", 1);
        assertThat(cycleEdges(r)).containsExactlyInAnyOrder("T1-wr-T2", "T2-rw-T1");
        assertThat(kinds(r)).containsExactly("read-consistency");
        assertThat(r.violations().getFirst().details().getFirst()).contains("reported €260.00").contains("+€60.00");
    }

    @Test
    void writeCycleIsG0AndFlaggedAsImpossible() {
        History h = new HistoryBuilder()
                .single(1, 1, 100_00).single(2, 1, 100_00)
                .write(1, "init", "T1", 100_00, 100_00)
                .write(1, "T1", "T2", 100_00, 100_00)
                .write(2, "init", "T2", 100_00, 100_00)
                .write(2, "T2", "T1", 100_00, 100_00)
                .txn("T1", "transfer").commit()
                .txn("T2", "transfer").commit()
                .build();

        CheckResult r = Checker.check(h);

        assertThat(r.anomalies()).containsEntry("G0", 1).containsEntry("G1c", 0).containsEntry("G-single", 0);
        assertThat(r.suspicious()).isTrue();
    }

    @Test
    void circularInformationFlowIsG1c() {
        History h = new HistoryBuilder()
                .single(1, 1, 100_00).single(2, 1, 100_00)
                .write(1, "init", "T1", 100_00, 90_00)
                .write(2, "init", "T2", 100_00, 90_00)
                .txn("T1", "withdraw").read(2, "T2", 90_00).delta(1, -10_00).commit()
                .txn("T2", "withdraw").read(1, "T1", 90_00).delta(2, -10_00).commit()
                .build();

        CheckResult r = Checker.check(h);

        assertThat(r.anomalies()).containsEntry("G1c", 1).containsEntry("G0", 0).containsEntry("G-single", 0);
        assertThat(cycleEdges(r)).containsExactlyInAnyOrder("T1-wr-T2", "T2-wr-T1");
        assertThat(r.suspicious()).isTrue();
    }

    @Test
    void serialWithdrawalsAreClean() {
        History h = new HistoryBuilder()
                .single(1, 1, 100_00)
                .write(1, "init", "T1", 100_00, 40_00)
                .write(1, "T1", "T2", 40_00, 10_00)
                .txn("T1", "withdraw").read(1, "init", 100_00).delta(1, -60_00).commit()
                .txn("T2", "withdraw").read(1, "T1", 40_00).delta(1, -30_00).commit()
                .txn("R", "total_balance").read(1, "T2", 10_00).commit()
                .build();

        CheckResult r = Checker.check(h);

        assertThat(r.clean()).isTrue();
        assertThat(r.instrumentationIssues()).isEmpty();
        assertThat(r.edges()).containsEntry("ww", 2).containsEntry("wr", 3);
    }

    @Test
    void staleReadOnlyReportIsNotAnAnomaly() {
        // A report that saw an older, consistent state can be serialised before the writers: no cycle.
        History h = new HistoryBuilder()
                .single(1, 1, 100_00).single(2, 1, 100_00)
                .write(1, "init", "T1", 100_00, 40_00)
                .write(2, "init", "T1", 100_00, 160_00)
                .txn("T1", "transfer").read(1, "init", 100_00).read(2, "init", 100_00)
                    .delta(1, -60_00).delta(2, 60_00).commit()
                .txn("R", "total_balance").read(1, "init", 100_00).read(2, "init", 100_00).commit()
                .build();

        assertThat(Checker.check(h).clean()).isTrue();
    }

    @Test
    void rejectedOperationsStillContributeReads() {
        // T2 read €100, decided it could not afford €120 and wrote nothing. T1 withdrew €60 meanwhile. Serialisable.
        History h = new HistoryBuilder()
                .single(1, 1, 100_00)
                .write(1, "init", "T1", 100_00, 40_00)
                .txn("T1", "withdraw").read(1, "init", 100_00).delta(1, -60_00).commit()
                .txn("T2", "withdraw").read(1, "init", 100_00).commit()
                .build();

        CheckResult r = Checker.check(h);
        assertThat(r.clean()).isTrue();
        assertThat(r.edges()).containsEntry("rw", 1);
    }

    @Test
    void versionForkAndUnknownReadAreInstrumentationIssues() {
        History h = new HistoryBuilder()
                .single(1, 1, 100_00)
                .write(1, "init", "T1", 100_00, 90_00)
                .write(1, "init", "T2", 100_00, 80_00)
                .txn("T1", "withdraw").read(1, "init", 100_00).delta(1, -10_00).commit()
                .txn("T2", "withdraw").read(1, "init", 100_00).delta(1, -20_00).commit()
                .txn("T3", "total_balance").read(1, "ghost", 1).commit()
                .build();

        CheckResult r = Checker.check(h);
        assertThat(r.instrumentationIssues()).anyMatch(s -> s.contains("version fork"))
                .anyMatch(s -> s.contains("which no committed write produced"));
    }

    @Test
    void writesCommittedOutsideTheLoggingTransactionBreakAtomicity() {
        // Two transfers in opposite directions, each statement auto-committed: a write cycle Postgres would prevent
        // inside transactions. The xids show there were none, so the G0 is real, not a recording bug.
        Instant t = Instant.EPOCH;
        History h = new History(
                new dev.skewer.checker.history.Meta("t", "gotcha", "serializable", 0, t, 0, java.util.Map.of()),
                List.of(new dev.skewer.checker.history.Account(1, 1, false, 100_00),
                        new dev.skewer.checker.history.Account(2, 1, false, 100_00)),
                List.of(
                        new dev.skewer.checker.history.Txn(HistoryBuilder.id("T1"), "T1", "transfer", "gotcha",
                                "read committed", "applied", List.of(), List.of(), "log", t.plusMillis(1), "10"),
                        new dev.skewer.checker.history.Txn(HistoryBuilder.id("T2"), "T2", "transfer", "gotcha",
                                "read committed", "applied", List.of(), List.of(), "log", t.plusMillis(2), "20")),
                List.of(
                        new dev.skewer.checker.history.Write(1, 1, HistoryBuilder.id("init"), HistoryBuilder.id("T1"), 100_00, 100_00, "11"),
                        new dev.skewer.checker.history.Write(2, 1, HistoryBuilder.id("T1"), HistoryBuilder.id("T2"), 100_00, 100_00, "21"),
                        new dev.skewer.checker.history.Write(3, 2, HistoryBuilder.id("init"), HistoryBuilder.id("T2"), 100_00, 100_00, "22"),
                        new dev.skewer.checker.history.Write(4, 2, HistoryBuilder.id("T2"), HistoryBuilder.id("T1"), 100_00, 100_00, "12")),
                java.util.Map.of(1L, 100_00L, 2L, 100_00L),
                List.of());

        CheckResult r = Checker.check(h);

        assertThat(r.anomalies()).containsEntry("G0", 1);
        assertThat(kinds(r)).contains("atomicity");
        assertThat(r.suspicious()).isFalse();
        assertThat(r.counterexamples().getFirst().explanation()).contains("auto-committed");
    }

    @Test
    void unloggedWriterIsReported() {
        History h = new HistoryBuilder()
                .single(1, 1, 100_00)
                .write(1, "init", "ghost", 100_00, 90_00)
                .build();

        CheckResult r = Checker.check(h);
        assertThat(r.instrumentationIssues()).anyMatch(s -> s.contains("no txn_log row"));
        assertThat(kinds(r)).contains("exactly-once");
    }

    private static List<String> cycleEdges(CheckResult r) {
        assertThat(r.counterexamples()).hasSize(1);
        return r.counterexamples().getFirst().edges().stream()
                .map((CxEdge e) -> e.from() + "-" + e.type() + "-" + e.to())
                .toList();
    }

    private static Set<String> kinds(CheckResult r) {
        return r.violations().stream().map(Violation::kind).collect(Collectors.toSet());
    }
}
