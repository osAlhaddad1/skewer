package dev.skewer.checker.graph;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Tarjan's strongly connected components, iterative so a 100,000-transaction history cannot overflow the stack.
 * Linear in nodes plus edges.
 */
public final class Tarjan {

    private Tarjan() {
    }

    /** {@code component[node]} is the node's component id; {@code components} lists each component's nodes. */
    public record Components(int[] component, List<int[]> components) {

        /** Components with more than one node. Only these can hold a cycle, since self-edges are never added. */
        public List<int[]> nonTrivial() {
            return components.stream().filter(c -> c.length > 1).toList();
        }
    }

    public static Components run(DependencyGraph g, Set<EdgeType> types) {
        int n = g.size();
        int[] index = new int[n];
        int[] low = new int[n];
        int[] comp = new int[n];
        boolean[] onStack = new boolean[n];
        Arrays.fill(index, -1);
        int[] stack = new int[n];
        int sp = 0;
        // Explicit call stack: the node being visited and how far through its edge list we are.
        int[] callNode = new int[n];
        int[] callEdge = new int[n];
        int counter = 0;
        List<int[]> components = new ArrayList<>();

        for (int root = 0; root < n; root++) {
            if (index[root] >= 0) {
                continue;
            }
            int depth = 0;
            callNode[0] = root;
            callEdge[0] = 0;
            index[root] = low[root] = counter++;
            stack[sp++] = root;
            onStack[root] = true;

            while (depth >= 0) {
                int v = callNode[depth];
                List<Edge> edges = g.out(v);
                boolean descended = false;
                while (callEdge[depth] < edges.size()) {
                    Edge e = edges.get(callEdge[depth]++);
                    if (!types.contains(e.type())) {
                        continue;
                    }
                    int w = e.to();
                    if (index[w] < 0) {
                        index[w] = low[w] = counter++;
                        stack[sp++] = w;
                        onStack[w] = true;
                        depth++;
                        callNode[depth] = w;
                        callEdge[depth] = 0;
                        descended = true;
                        break;
                    } else if (onStack[w]) {
                        low[v] = Math.min(low[v], index[w]);
                    }
                }
                if (descended) {
                    continue;
                }
                if (low[v] == index[v]) {
                    int id = components.size();
                    int start = sp;
                    do {
                        start--;
                    } while (stack[start] != v);
                    int[] members = Arrays.copyOfRange(stack, start, sp);
                    for (int w : members) {
                        onStack[w] = false;
                        comp[w] = id;
                    }
                    sp = start;
                    components.add(members);
                }
                depth--;
                if (depth >= 0) {
                    int parent = callNode[depth];
                    low[parent] = Math.min(low[parent], low[v]);
                }
            }
        }
        return new Components(comp, components);
    }
}
