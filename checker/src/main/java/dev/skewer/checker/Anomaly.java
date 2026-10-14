package dev.skewer.checker;

import java.util.List;

import dev.skewer.checker.graph.Edge;

/**
 * One dependency cycle and its class.
 *
 * @param label the banking reading of the cycle when there is one: {@code lost update}, {@code read skew},
 *              {@code write skew}; null otherwise
 */
public record Anomaly(AnomalyClass cls, String label, List<Edge> cycle) {

    public Anomaly {
        cycle = List.copyOf(cycle);
    }
}
