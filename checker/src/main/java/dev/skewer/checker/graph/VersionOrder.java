package dev.skewer.checker.graph;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import dev.skewer.checker.history.Account;
import dev.skewer.checker.history.Ids;
import dev.skewer.checker.history.Write;

/**
 * The version order of every account, built from the write audit's {@code prevWriter -> newWriter} links.
 *
 * <p>Skewer controls the database, so it observes version order directly instead of inferring it. That is what
 * turns serializability checking (NP-complete in general) into cycle detection. A version is named by the
 * transaction that wrote it; every account's first version is written by {@link Ids#INIT}.
 */
public final class VersionOrder {

    private final Map<Long, List<UUID>> chains = new HashMap<>();
    private final Map<Long, Map<UUID, Integer>> positions = new HashMap<>();
    private final Map<Long, Map<UUID, Write>> producers = new HashMap<>();
    private final List<String> problems = new ArrayList<>();

    private VersionOrder() {
    }

    public static VersionOrder build(Collection<Account> accounts, Collection<Write> writes) {
        VersionOrder order = new VersionOrder();
        Map<Long, List<Write>> byAccount = new HashMap<>();
        for (Account a : accounts) {
            byAccount.put(a.id(), new ArrayList<>());
        }
        for (Write w : writes) {
            byAccount.computeIfAbsent(w.account(), k -> new ArrayList<>()).add(w);
        }
        byAccount.forEach((account, list) -> order.buildChain(account, list));
        return order;
    }

    private void buildChain(long account, List<Write> writes) {
        // Within one transaction seq order is reliable, so it orders repeated writes by the same writer.
        writes.sort(Comparator.comparingLong(Write::seq));
        Map<UUID, UUID> next = new HashMap<>();
        Map<UUID, Write> produced = new HashMap<>();
        for (Write w : writes) {
            if (w.prevWriter().equals(w.newWriter())) {
                // A transaction rewrote its own version (or a forced version bump): fold into one version.
                Write first = produced.get(w.newWriter());
                if (first != null) {
                    produced.put(w.newWriter(), new Write(first.seq(), account, first.prevWriter(), first.newWriter(),
                            first.oldBalance(), w.newBalance(), first.dbXid()));
                } else if (!Ids.INIT.equals(w.newWriter())) {
                    problems.add("account " + account + ": " + Ids.shortId(w.newWriter())
                            + " rewrote a version it never created (seq " + w.seq() + ")");
                }
                continue;
            }
            UUID previous = next.putIfAbsent(w.prevWriter(), w.newWriter());
            if (previous != null) {
                problems.add("account " + account + ": version fork, both " + Ids.shortId(previous) + " and "
                        + Ids.shortId(w.newWriter()) + " replaced " + Ids.shortId(w.prevWriter()));
            }
            if (produced.putIfAbsent(w.newWriter(), w) != null) {
                problems.add("account " + account + ": " + Ids.shortId(w.newWriter()) + " created two versions");
            }
        }

        List<UUID> chain = new ArrayList<>();
        Map<UUID, Integer> pos = new HashMap<>();
        Set<UUID> seen = new HashSet<>();
        UUID current = Ids.INIT;
        while (current != null && seen.add(current)) {
            pos.put(current, chain.size());
            chain.add(current);
            current = next.get(current);
        }
        if (current != null) {
            problems.add("account " + account + ": version chain loops back to " + Ids.shortId(current));
        }
        for (UUID writer : produced.keySet()) {
            if (!pos.containsKey(writer)) {
                problems.add("account " + account + ": version by " + Ids.shortId(writer)
                        + " is not reachable from the initial version");
            }
        }
        chains.put(account, chain);
        positions.put(account, pos);
        producers.put(account, produced);
    }

    /** Position of {@code writer}'s version of {@code account}, or -1 if no such version exists. */
    public int position(long account, UUID writer) {
        Map<UUID, Integer> pos = positions.get(account);
        if (pos == null) {
            return -1;
        }
        return pos.getOrDefault(writer, -1);
    }

    /** The writer of the version right after {@code writer}'s, or null if it is the latest. */
    public UUID next(long account, UUID writer) {
        int p = position(account, writer);
        List<UUID> chain = chains.get(account);
        if (p < 0 || p + 1 >= chain.size()) {
            return null;
        }
        return chain.get(p + 1);
    }

    /** The audit row that created {@code writer}'s version, or null for the initial version. */
    public Write producer(long account, UUID writer) {
        Map<UUID, Write> produced = producers.get(account);
        return produced == null ? null : produced.get(writer);
    }

    public List<UUID> chain(long account) {
        return chains.getOrDefault(account, List.of());
    }

    public Set<Long> accounts() {
        return chains.keySet();
    }

    /** Inconsistencies in the audit itself. Postgres serialises row writes, so any entry means an instrumentation bug. */
    public List<String> problems() {
        return problems;
    }
}
