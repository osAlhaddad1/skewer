package dev.skewer.checker;

/** Adya's cycle classes, as far as Skewer can observe them with item (not predicate) reads. */
public enum AnomalyClass {
    G0("G0", "write cycle"),
    G1C("G1c", "circular information flow"),
    G_SINGLE("G-single", "one anti-dependency in the cycle"),
    G2_ITEM("G2-item", "two or more anti-dependencies in the cycle");

    private final String code;
    private final String meaning;

    AnomalyClass(String code, String meaning) {
        this.code = code;
        this.meaning = meaning;
    }

    public String code() {
        return code;
    }

    public String meaning() {
        return meaning;
    }

    /** Postgres prevents G0 and G1c at every isolation level, so finding one means Skewer itself is wrong. */
    public boolean impossibleOnPostgres() {
        return this == G0 || this == G1C;
    }
}
