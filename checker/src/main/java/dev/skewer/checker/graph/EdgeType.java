package dev.skewer.checker.graph;

/** Adya's direct dependency types. */
public enum EdgeType {
    /** Write dependency: the target wrote the version right after the source's version of the same row. */
    WW,
    /** Read dependency: the target read the version the source wrote. */
    WR,
    /** Anti-dependency: the source read a version and the target wrote the next one; the source missed it. */
    RW;

    public String label() {
        return name().toLowerCase();
    }
}
