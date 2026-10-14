package dev.skewer.checker;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import dev.skewer.checker.graph.CycleSearch;
import dev.skewer.checker.graph.DependencyGraph;
import dev.skewer.checker.graph.Edge;
import dev.skewer.checker.graph.EdgeType;
import dev.skewer.checker.graph.Tarjan;
import dev.skewer.checker.history.Txn;

/**
 * Finds dependency cycles and classifies them with Adya's taxonomy, in layers like Elle does: cycles among
 * ww edges only (G0), then ww and wr (G1c), then cycles through each rw edge, preferring a way back without
 * further rw edges (G-single) over one with them (G2-item).
 */
public final class Classifier {

    private static final Set<EdgeType> WW = EnumSet.of(EdgeType.WW);
    private static final Set<EdgeType> WW_WR = EnumSet.of(EdgeType.WW, EdgeType.WR);
    private static final Set<EdgeType> ALL = EnumSet.allOf(EdgeType.class);

    public record Result(
            List<Anomaly> anomalies,
            Map<AnomalyClass, Integer> counts,
            Map<String, Integer> labels,
            boolean truncated) {
    }

    private Classifier() {
    }

    /**
     * @param maxRwSearches cap on cycle searches through rw edges; a history with more rw edges inside cycles is
     *                      reported as truncated (anomaly counts become lower bounds)
     */
    public static Result classify(DependencyGraph g, int maxRwSearches) {
        List<Anomaly> anomalies = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        // Layer 1: G0, cycles of writes alone.
        Tarjan.Components ww = Tarjan.run(g, WW);
        for (int[] comp : ww.nonTrivial()) {
            List<Edge> cycle = firstCycle(g, comp, EdgeType.WW, WW, ww.component());
            if (cycle != null) {
                add(anomalies, seen, new Anomaly(AnomalyClass.G0, null, cycle));
            }
        }

        // Layer 2: G1c, cycles of writes and reads that contain at least one read dependency.
        Tarjan.Components wwwr = Tarjan.run(g, WW_WR);
        for (int[] comp : wwwr.nonTrivial()) {
            List<Edge> cycle = firstCycle(g, comp, EdgeType.WR, WW_WR, wwwr.component());
            if (cycle != null) {
                add(anomalies, seen, new Anomaly(AnomalyClass.G1C, null, cycle));
            }
        }

        // Layer 3: every rw edge inside a strongly connected component lies on some cycle.
        Tarjan.Components all = Tarjan.run(g, ALL);
        int[] component = all.component();
        int searches = 0;
        boolean truncated = false;
        outer:
        for (int[] comp : all.nonTrivial()) {
            for (int v : comp) {
                for (Edge e : g.out(v)) {
                    if (e.type() != EdgeType.RW || component[e.to()] != component[e.from()]) {
                        continue;
                    }
                    if (searches++ >= maxRwSearches) {
                        truncated = true;
                        break outer;
                    }
                    List<Edge> single = CycleSearch.cycleThrough(g, e, WW_WR, component);
                    if (single != null) {
                        add(anomalies, seen, new Anomaly(AnomalyClass.G_SINGLE, labelSingle(g, e), single));
                        continue;
                    }
                    List<Edge> multi = CycleSearch.cycleThrough(g, e, ALL, component);
                    if (multi != null) {
                        add(anomalies, seen, new Anomaly(AnomalyClass.G2_ITEM, labelMulti(multi), multi));
                    }
                }
            }
        }

        Map<AnomalyClass, Integer> counts = new EnumMap<>(AnomalyClass.class);
        for (AnomalyClass c : AnomalyClass.values()) {
            counts.put(c, 0);
        }
        Map<String, Integer> labels = new LinkedHashMap<>();
        for (Anomaly a : anomalies) {
            counts.merge(a.cls(), 1, Integer::sum);
            if (a.label() != null) {
                labels.merge(a.label(), 1, Integer::sum);
            }
        }
        return new Result(anomalies, counts, labels, truncated);
    }

    /** Shortest cycle through the first internal edge of the given type, or null if the component has none. */
    private static List<Edge> firstCycle(DependencyGraph g, int[] comp, EdgeType through, Set<EdgeType> types,
                                         int[] component) {
        for (int v : comp) {
            for (Edge e : g.out(v)) {
                if (e.type() == through && component[e.to()] == component[v]) {
                    List<Edge> cycle = CycleSearch.cycleThrough(g, e, types, component);
                    if (cycle != null) {
                        return cycle;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Lost update (Hermitage's P4): the anti-dependency's reader also wrote that row, so it overwrote a version it
     * never saw. Read skew: the reader wrote nothing, so it is a report that saw two different states.
     */
    private static String labelSingle(DependencyGraph g, Edge rw) {
        if (g.versions().position(rw.account(), g.id(rw.from())) >= 0) {
            return "lost update";
        }
        Txn reader = g.txn(rw.from());
        if (reader != null && reader.readOnly()) {
            return "read skew";
        }
        return null;
    }

    /** Write skew: two transactions, each missed the other's write to a different row. */
    private static String labelMulti(List<Edge> cycle) {
        if (cycle.size() == 2 && cycle.stream().allMatch(e -> e.type() == EdgeType.RW)
                && cycle.get(0).account() != cycle.get(1).account()) {
            return "write skew";
        }
        return null;
    }

    private static void add(List<Anomaly> anomalies, Set<String> seen, Anomaly a) {
        Set<Integer> nodes = new TreeSet<>();
        for (Edge e : a.cycle()) {
            nodes.add(e.from());
        }
        if (seen.add(a.cls() + ":" + nodes)) {
            anomalies.add(a);
        }
    }
}
