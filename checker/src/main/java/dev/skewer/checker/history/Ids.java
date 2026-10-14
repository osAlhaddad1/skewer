package dev.skewer.checker.history;

import java.util.UUID;

/** Well-known writer ids. */
public final class Ids {

    /** Writer of every account's initial version: the seed data, not a real transaction. */
    public static final UUID INIT = new UUID(0L, 0L);

    private Ids() {
    }

    /** First eight hex digits: enough to tell transactions apart in a report. */
    public static String shortId(UUID id) {
        return INIT.equals(id) ? "init" : id.toString().substring(0, 8);
    }
}
