package dev.skewer.checker;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import dev.skewer.checker.Counterexample.CxEdge;
import dev.skewer.checker.Counterexample.CxTxn;
import dev.skewer.checker.Counterexample.CxWrite;
import dev.skewer.checker.graph.DependencyGraph;
import dev.skewer.checker.graph.Edge;
import dev.skewer.checker.graph.EdgeType;
import dev.skewer.checker.graph.VersionOrder;
import dev.skewer.checker.history.ClientOp;
import dev.skewer.checker.history.History;
import dev.skewer.checker.history.Ids;
import dev.skewer.checker.history.Read;
import dev.skewer.checker.history.Txn;
import dev.skewer.checker.history.Write;

/** Runs both layers over a history: invariants, then the dependency graph. */
public final class Checker {

    public record Options(int maxRwSearches, int counterexamplesPerClass) {
        public static Options defaults() {
            return new Options(200_000, 3);
        }
    }

    private Checker() {
    }

    public static CheckResult check(History history) {
        return check(history, Options.defaults());
    }

    public static CheckResult check(History history, Options options) {
        long started = System.nanoTime();
        DependencyGraph graph = DependencyGraph.build(history);
        List<Violation> violations = Invariants.check(history, graph.versions());
        Classifier.Result classified = Classifier.classify(graph, options.maxRwSearches());

        Map<String, Integer> anomalies = new LinkedHashMap<>();
        classified.counts().forEach((cls, n) -> anomalies.put(cls.code(), n));
        Map<String, Integer> edges = new LinkedHashMap<>();
        graph.edgeCounts().forEach((type, n) -> edges.put(type.label(), n));
        Map<String, Long> observed = history.txns().stream()
                .collect(Collectors.groupingBy(t -> Objects.requireNonNullElse(t.isolation(), "unknown"), TreeMap::new,
                        Collectors.counting()));

        Map<String, ClientOp> clientOps = new HashMap<>();
        history.clientOps().forEach(c -> clientOps.put(c.opId(), c));
        java.util.Set<UUID> nonAtomic = Invariants.nonAtomic(history);
        List<Counterexample> counterexamples = new ArrayList<>();
        Map<AnomalyClass, List<Anomaly>> byClass = classified.anomalies().stream()
                .collect(Collectors.groupingBy(Anomaly::cls, LinkedHashMap::new, Collectors.toList()));
        for (List<Anomaly> list : byClass.values()) {
            list.stream()
                    .sorted(Comparator.comparing((Anomaly a) -> a.label() == null).thenComparingInt(a -> a.cycle().size()))
                    .limit(options.counterexamplesPerClass())
                    .forEach(a -> counterexamples.add(explain(a, graph, clientOps, nonAtomic)));
        }

        long millis = (System.nanoTime() - started) / 1_000_000;
        return new CheckResult(history.meta().runId(), history.meta().strategy(), history.meta().requestedIsolation(),
                observed, history.txns().size(), edges, violations, anomalies, classified.labels(), counterexamples,
                graph.problems(), classified.truncated(), millis);
    }

    /** Turns a cycle into a counterexample. Transactions are named T1, T2, ... in commit order. */
    static Counterexample explain(Anomaly a, DependencyGraph g, Map<String, ClientOp> clientOps,
                                  java.util.Set<UUID> nonAtomic) {
        LinkedHashSet<Integer> nodes = new LinkedHashSet<>();
        a.cycle().forEach(e -> nodes.add(e.from()));
        List<Integer> ordered = nodes.stream()
                .sorted(Comparator.comparing((Integer n) -> g.txn(n) == null || g.txn(n).committedAt() == null
                        ? java.time.Instant.MAX : g.txn(n).committedAt()))
                .toList();
        Map<Integer, String> names = new HashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            names.put(ordered.get(i), "T" + (i + 1));
        }
        VersionOrder versions = g.versions();

        List<CxTxn> txns = new ArrayList<>();
        for (int n : ordered) {
            UUID id = g.id(n);
            Txn t = g.txn(n);
            List<CxWrite> writes = new ArrayList<>();
            for (long account : new java.util.TreeSet<>(versions.accounts())) {
                Write w = versions.producer(account, id);
                if (w != null) {
                    writes.add(new CxWrite(account, Ids.shortId(w.prevWriter()), w.oldBalance(), w.newBalance()));
                }
            }
            ClientOp c = t == null ? null : clientOps.get(t.opId());
            txns.add(new CxTxn(id.toString(), names.get(n),
                    t == null ? "unknown (no txn_log row)" : t.op(),
                    c == null ? null : c.params(),
                    t == null ? null : t.strategy(),
                    t == null ? null : t.isolation(),
                    t == null ? null : t.outcome(),
                    t == null ? List.of() : t.reads(),
                    writes,
                    t == null ? null : t.committedAt(),
                    c == null ? null : c.startMicros(),
                    c == null ? null : c.endMicros()));
        }

        List<CxEdge> edges = new ArrayList<>();
        for (Edge e : a.cycle()) {
            edges.add(new CxEdge(names.get(e.from()), names.get(e.to()), e.type().label(), e.account(),
                    edgeDetail(e, g, names)));
        }
        boolean split = a.cycle().stream().anyMatch(e -> nonAtomic.contains(g.id(e.from())));
        return new Counterexample(a.cls().code(), a.label(), explanation(a, g, names, split), txns, edges);
    }

    private static String edgeDetail(Edge e, DependencyGraph g, Map<Integer, String> names) {
        String from = names.get(e.from());
        String to = names.get(e.to());
        return switch (e.type()) {
            case WW -> to + " overwrote " + from + "'s version of account " + e.account();
            case WR -> to + " read " + from + "'s version of account " + e.account()
                    + balanceSuffix(g.versions().producer(e.account(), g.id(e.from())));
            case RW -> from + " read account " + e.account() + " at the version by " + versionName(e.version(), g, names)
                    + "; " + to + " wrote the next version, which " + from + " never saw";
        };
    }

    private static String explanation(Anomaly a, DependencyGraph g, Map<Integer, String> names, boolean split) {
        Edge rw = a.cycle().stream().filter(e -> e.type() == EdgeType.RW).findFirst().orElse(null);
        if ("lost update".equals(a.label()) && rw != null) {
            String u = names.get(rw.from());
            String v = names.get(rw.to());
            long seen = readBalance(g.txn(rw.from()), rw.account(), rw.version());
            Write vWrite = g.versions().producer(rw.account(), g.id(rw.to()));
            Write uWrite = g.versions().producer(rw.account(), g.id(rw.from()));
            return u + " read account " + rw.account() + " at " + Money.format(seen) + ". " + v + " then wrote "
                    + Money.format(vWrite.newBalance()) + ", and " + u + " overwrote it with "
                    + Money.format(uWrite.newBalance()) + ", computed from its stale " + Money.format(seen) + ". "
                    + v + "'s update was lost: no serial order of " + u + " and " + v + " ends this way.";
        }
        if ("read skew".equals(a.label()) && rw != null) {
            String u = names.get(rw.from());
            String v = names.get(rw.to());
            Edge wr = a.cycle().stream().filter(e -> e.type() == EdgeType.WR && e.to() == rw.from()).findFirst().orElse(null);
            String other = wr == null ? "another account" : "account " + wr.account();
            return u + " is a read-only report. It read " + other + " after " + v + " committed, but account "
                    + rw.account() + " before " + v + "'s write to it, so its total mixes two database states.";
        }
        if ("write skew".equals(a.label())) {
            Edge e1 = a.cycle().get(0);
            Edge e2 = a.cycle().get(1);
            String t1 = names.get(e1.from());
            String t2 = names.get(e1.to());
            return t1 + " and " + t2 + " each checked the rule against a state the other was about to change: " + t1
                    + " read account " + e1.account() + " before " + t2 + " wrote it, and " + t2 + " read account "
                    + e2.account() + " before " + t1 + " wrote it. They wrote different rows, so no lock collided.";
        }
        String chain = a.cycle().stream()
                .map(e -> names.get(e.from()) + " -" + e.type().label() + "(" + e.account() + ")-> " + names.get(e.to()))
                .collect(Collectors.joining(", "));
        String note = "";
        if (a.cls().impossibleOnPostgres()) {
            note = split
                    ? " Postgres prevents this inside transactions, but these operations ran as several auto-committed"
                    + " statements (see the atomicity violation), so its guarantees never applied."
                    : " Postgres prevents this at every isolation level, so this points at a bug in Skewer's recording.";
        }
        return "Each transaction must come before the next one in the cycle: " + chain + "." + note;
    }

    private static long readBalance(Txn t, long account, UUID version) {
        if (t == null) {
            return 0;
        }
        return t.reads().stream()
                .filter(r -> r.account() == account && r.saw().equals(version))
                .mapToLong(Read::balance).findFirst().orElse(0);
    }

    private static String versionName(UUID writer, DependencyGraph g, Map<Integer, String> names) {
        int n = g.indexOf(writer);
        String name = n >= 0 ? names.get(n) : null;
        return name != null ? name : Ids.shortId(writer);
    }

    private static String balanceSuffix(Write w) {
        return w == null ? "" : " (" + Money.format(w.newBalance()) + ")";
    }
}
