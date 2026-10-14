package dev.skewer.checker.graph;

import java.util.UUID;

/**
 * A dependency edge between two graph nodes, with the account it is about.
 *
 * @param version for {@code rw}, the version the source read (its writer); for {@code ww} and {@code wr}, the
 *                source's own version
 */
public record Edge(int from, int to, EdgeType type, long account, UUID version) {
}
