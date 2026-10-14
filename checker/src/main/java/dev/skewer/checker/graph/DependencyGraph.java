package dev.skewer.checker.graph;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import dev.skewer.checker.history.History;
import dev.skewer.checker.history.Ids;
import dev.skewer.checker.history.Read;
import dev.skewer.checker.history.Txn;
import dev.skewer.checker.history.Write;

/**
 * Direct serialization graph: one node per committed transaction (plus the initial-version writer), with
 * ww, wr and rw edges derived from the version order and the read sets.
 */
public final class DependencyGraph {

    private final List<UUID> nodes = new ArrayList<>();
    private final Map<UUID, Integer> index = new HashMap<>();
    private final Map<UUID, Txn> txns = new HashMap<>();
    private final List<List<Edge>> out = new ArrayList<>();
    private final Set<Edge> edgeSet = new HashSet<>();
    private final Map<EdgeType, Integer> edgeCounts = new EnumMap<>(EdgeType.class);
    private final List<String> problems = new ArrayList<>();
    private final VersionOrder versions;

    private DependencyGraph(VersionOrder versions) {
        this.versions = versions;
        for (EdgeType t : EdgeType.values()) {
            edgeCounts.put(t, 0);
        }
    }

    public static DependencyGraph build(History history) {
        VersionOrder versions = VersionOrder.build(history.accounts(), history.writes());
        DependencyGraph g = new DependencyGraph(versions);
        g.problems.addAll(versions.problems());
        g.node(Ids.INIT);
        for (Txn t : history.txns()) {
            if (g.txns.putIfAbsent(t.id(), t) != null) {
                g.problems.add("transaction " + Ids.shortId(t.id()) + " appears twice in the history");
            }
            g.node(t.id());
        }

        Set<UUID> unloggedWriters = new HashSet<>();
        for (Write w : history.writes()) {
            if (!w.newWriter().equals(Ids.INIT) && !g.txns.containsKey(w.newWriter())) {
                unloggedWriters.add(w.newWriter());
            }
            if (w.prevWriter().equals(w.newWriter())) {
                continue;
            }
            g.addEdge(g.node(w.prevWriter()), g.node(w.newWriter()), EdgeType.WW, w.account(), w.prevWriter());
        }
        if (!unloggedWriters.isEmpty()) {
            g.problems.add(unloggedWriters.size() + " transaction(s) wrote rows but left no txn_log row: their writes"
                    + " committed without a commit record (statements ran outside one transaction?)");
        }

        for (Txn t : history.txns()) {
            int reader = g.index.get(t.id());
            for (Read r : t.reads()) {
                if (versions.position(r.account(), r.saw()) < 0) {
                    g.problems.add(Ids.shortId(t.id()) + " read a version of account " + r.account() + " written by "
                            + Ids.shortId(r.saw()) + ", which no committed write produced");
                    continue;
                }
                if (!r.saw().equals(t.id())) {
                    g.addEdge(g.node(r.saw()), reader, EdgeType.WR, r.account(), r.saw());
                }
                UUID overwriter = versions.next(r.account(), r.saw());
                if (overwriter != null && !overwriter.equals(t.id())) {
                    g.addEdge(reader, g.node(overwriter), EdgeType.RW, r.account(), r.saw());
                }
            }
        }
        return g;
    }

    private int node(UUID id) {
        Integer i = index.get(id);
        if (i != null) {
            return i;
        }
        int n = nodes.size();
        nodes.add(id);
        index.put(id, n);
        out.add(new ArrayList<>());
        return n;
    }

    private void addEdge(int from, int to, EdgeType type, long account, UUID version) {
        if (from == to) {
            return;
        }
        Edge e = new Edge(from, to, type, account, version);
        if (edgeSet.add(e)) {
            out.get(from).add(e);
            edgeCounts.merge(type, 1, Integer::sum);
        }
    }

    public int size() {
        return nodes.size();
    }

    public List<Edge> out(int node) {
        return out.get(node);
    }

    public UUID id(int node) {
        return nodes.get(node);
    }

    public int indexOf(UUID id) {
        return index.getOrDefault(id, -1);
    }

    /** The logged transaction behind a node, or null for the initial writer and unlogged writers. */
    public Txn txn(int node) {
        return txns.get(nodes.get(node));
    }

    public VersionOrder versions() {
        return versions;
    }

    public Map<EdgeType, Integer> edgeCounts() {
        return edgeCounts;
    }

    public List<String> problems() {
        return problems;
    }
}
