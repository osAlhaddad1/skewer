package dev.skewer.checker;

import java.util.stream.Collectors;

import dev.skewer.checker.Counterexample.CxEdge;
import dev.skewer.checker.Counterexample.CxTxn;
import dev.skewer.checker.Counterexample.CxWrite;
import dev.skewer.checker.history.Ids;
import dev.skewer.checker.history.Read;

/** Plain-text rendering of a check result for the terminal. */
public final class TextSummary {

    private TextSummary() {
    }

    public static String render(CheckResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append("run ").append(r.runId()).append(": ").append(r.strategy()).append(" @ ").append(r.requestedIsolation())
                .append('\n');
        sb.append("  ").append(r.transactions()).append(" committed transactions, edges ").append(r.edges())
                .append(", isolation actually run ").append(r.observedIsolation()).append('\n');
        if (r.clean()) {
            sb.append("  CLEAN: no invariant broken, no dependency cycle\n");
        }
        for (Violation v : r.violations()) {
            sb.append("  INVARIANT ").append(v.kind()).append(": ").append(v.message()).append('\n');
            v.details().stream().limit(3).forEach(d -> sb.append("      ").append(d).append('\n'));
        }
        if (r.anomalyCount() > 0) {
            sb.append("  CYCLES ").append(r.anomalies()).append(" ").append(r.labels())
                    .append(r.truncated() ? " (search capped: counts are lower bounds)" : "").append('\n');
        }
        for (String issue : r.instrumentationIssues()) {
            sb.append("  INSTRUMENTATION ").append(issue).append('\n');
        }
        for (Counterexample cx : r.counterexamples()) {
            sb.append('\n').append("  ").append(cx.anomalyClass())
                    .append(cx.label() == null ? "" : " (" + cx.label() + ")").append('\n');
            sb.append("    ").append(cx.explanation()).append('\n');
            for (CxTxn t : cx.txns()) {
                sb.append("    ").append(t.name()).append(" = ").append(Ids.shortId(java.util.UUID.fromString(t.id())))
                        .append(' ').append(t.op()).append(t.params() == null ? "" : " " + t.params())
                        .append(" [").append(t.isolation()).append("]\n");
                sb.append("       reads  ").append(t.reads().stream().map(TextSummary::read).collect(Collectors.joining(", ")))
                        .append('\n');
                if (!t.writes().isEmpty()) {
                    sb.append("       writes ").append(t.writes().stream().map(TextSummary::write)
                            .collect(Collectors.joining(", "))).append('\n');
                }
            }
            for (CxEdge e : cx.edges()) {
                sb.append("    ").append(e.from()).append(" --").append(e.type()).append("--> ").append(e.to())
                        .append("   ").append(e.detail()).append('\n');
            }
        }
        sb.append("  checked in ").append(r.checkMillis()).append(" ms\n");
        return sb.toString();
    }

    private static String read(Read r) {
        return "acct " + r.account() + "=" + Money.format(r.balance()) + " (v:" + Ids.shortId(r.saw()) + ")";
    }

    private static String write(CxWrite w) {
        return "acct " + w.account() + " " + Money.format(w.oldBalance()) + " -> " + Money.format(w.newBalance())
                + " (replaced v:" + w.prev() + ")";
    }
}
