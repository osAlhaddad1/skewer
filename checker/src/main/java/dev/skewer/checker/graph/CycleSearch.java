package dev.skewer.checker.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Breadth-first search for shortest paths inside one strongly connected component. */
public final class CycleSearch {

    private CycleSearch() {
    }

    /**
     * Shortest path from {@code start} to {@code target} using only edges of the given types and only nodes in
     * component {@code compId}. Returns the edges in order, or null if there is none.
     */
    public static List<Edge> shortestPath(DependencyGraph g, int start, int target, Set<EdgeType> types,
                                          int[] component, int compId) {
        if (start == target) {
            return List.of();
        }
        Map<Integer, Edge> via = new HashMap<>();
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(start);
        via.put(start, null);
        while (!queue.isEmpty()) {
            int v = queue.poll();
            for (Edge e : g.out(v)) {
                int w = e.to();
                if (!types.contains(e.type()) || component[w] != compId || via.containsKey(w)) {
                    continue;
                }
                via.put(w, e);
                if (w == target) {
                    List<Edge> path = new ArrayList<>();
                    for (Edge step = e; step != null; step = via.get(step.from())) {
                        path.add(step);
                    }
                    Collections.reverse(path);
                    return path;
                }
                queue.add(w);
            }
        }
        return null;
    }

    /** Shortest cycle through {@code edge}: the edge itself followed by the shortest way back. */
    public static List<Edge> cycleThrough(DependencyGraph g, Edge edge, Set<EdgeType> types, int[] component) {
        List<Edge> back = shortestPath(g, edge.to(), edge.from(), types, component, component[edge.from()]);
        if (back == null) {
            return null;
        }
        List<Edge> cycle = new ArrayList<>(back.size() + 1);
        cycle.add(edge);
        cycle.addAll(back);
        return cycle;
    }
}
