package dev.skewer.checker;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import dev.skewer.checker.graph.VersionOrder;
import dev.skewer.checker.history.Account;
import dev.skewer.checker.history.Delta;
import dev.skewer.checker.history.History;
import dev.skewer.checker.history.Ids;
import dev.skewer.checker.history.Read;
import dev.skewer.checker.history.Txn;
import dev.skewer.checker.history.Write;

/**
 * Layer 1: business invariants. Fast, and they say that money went wrong in terms a bank cares about; the
 * dependency graph then says which transactions did it.
 */
public final class Invariants {

    private static final int MAX_DETAILS = 10;

    /** The read-only reporting operation. A rejected withdrawal also writes nothing, but it is not a report. */
    public static final String REPORT_OP = "total_balance";

    private Invariants() {
    }

    public static List<Violation> check(History h, VersionOrder versions) {
        List<Violation> out = new ArrayList<>();
        Map<Long, Account> accounts = new TreeMap<>();
        h.accounts().forEach(a -> accounts.put(a.id(), a));
        Map<Long, Long> deltaSum = new HashMap<>();
        for (Txn t : h.txns()) {
            for (Delta d : t.deltas()) {
                deltaSum.merge(d.account(), d.cents(), Long::sum);
            }
        }
        atomicity(h, out);
        conservation(h, accounts, deltaSum, out);
        exactlyOnce(h, accounts, deltaSum, out);
        accountRule(h, accounts, out);
        customerRule(h, accounts, out);
        readConsistency(h, versions, out);
        return out;
    }

    /**
     * Application transactions whose writes were committed by a different database transaction than their txn_log
     * row. Only possible when the statements never ran inside one transaction, e.g. a {@code @Transactional}
     * method reached by self-invocation. Postgres's isolation guarantees never applied to them.
     */
    public static Set<UUID> nonAtomic(History h) {
        Map<UUID, String> logged = new HashMap<>();
        for (Txn t : h.txns()) {
            if (t.dbXid() != null) {
                logged.put(t.id(), t.dbXid());
            }
        }
        Set<UUID> out = new HashSet<>();
        for (Write w : h.writes()) {
            String xid = logged.get(w.newWriter());
            if (xid != null && w.dbXid() != null && !xid.equals(w.dbXid())) {
                out.add(w.newWriter());
            }
        }
        return out;
    }

    private static void atomicity(History h, List<Violation> out) {
        Set<UUID> split = nonAtomic(h);
        if (split.isEmpty()) {
            return;
        }
        Map<UUID, Set<String>> xids = new HashMap<>();
        for (Write w : h.writes()) {
            if (split.contains(w.newWriter()) && w.dbXid() != null) {
                xids.computeIfAbsent(w.newWriter(), k -> new HashSet<>()).add(w.dbXid());
            }
        }
        List<String> details = new ArrayList<>();
        Map<UUID, Txn> byId = new HashMap<>();
        h.txns().forEach(t -> byId.put(t.id(), t));
        for (UUID id : split) {
            if (details.size() >= MAX_DETAILS) {
                break;
            }
            Txn t = byId.get(id);
            details.add(t.op() + " " + Ids.shortId(id) + ": its writes were committed by " + xids.get(id).size()
                    + " database transaction(s), its commit record by another");
        }
        out.add(new Violation("atomicity", split.size() + " operation(s) did not run as one database transaction:"
                + " each statement committed on its own", 0, details));
    }

    /** Final total equals initial total plus every committed transaction's claimed effect. */
    private static void conservation(History h, Map<Long, Account> accounts, Map<Long, Long> deltaSum,
                                     List<Violation> out) {
        long initial = accounts.values().stream().mapToLong(Account::initialBalance).sum();
        long claimed = deltaSum.values().stream().mapToLong(Long::longValue).sum();
        long actual = accounts.keySet().stream().mapToLong(id -> h.finalBalances().getOrDefault(id, 0L)).sum();
        long diff = actual - (initial + claimed);
        if (diff != 0) {
            String verb = diff > 0 ? "created from nothing" : "destroyed";
            out.add(new Violation("conservation", Money.format(Math.abs(diff)) + " " + verb, diff, List.of(
                    "initial total " + Money.format(initial),
                    "committed transactions moved " + Money.format(claimed) + " net",
                    "expected final total " + Money.format(initial + claimed) + ", actual " + Money.format(actual))));
        }
    }

    /** Each account's final balance is its initial balance plus the deltas of committed transactions. */
    private static void exactlyOnce(History h, Map<Long, Account> accounts, Map<Long, Long> deltaSum,
                                    List<Violation> out) {
        List<String> details = new ArrayList<>();
        long total = 0;
        int count = 0;
        for (Account a : accounts.values()) {
            long expected = a.initialBalance() + deltaSum.getOrDefault(a.id(), 0L);
            long actual = h.finalBalances().getOrDefault(a.id(), 0L);
            if (expected != actual) {
                count++;
                total += Math.abs(actual - expected);
                if (details.size() < MAX_DETAILS) {
                    details.add("account " + a.id() + ": expected " + Money.format(expected) + ", actual "
                            + Money.format(actual) + " (" + signed(actual - expected) + ")");
                }
            }
        }
        if (count > 0) {
            out.add(new Violation("exactly-once", count + " account(s) do not match their committed effects: an effect"
                    + " was lost or applied twice", total, details));
        }
    }

    /** Single accounts never go below zero, in any committed version. */
    private static void accountRule(History h, Map<Long, Account> accounts, List<Violation> out) {
        List<String> details = new ArrayList<>();
        long worst = 0;
        int count = 0;
        for (Write w : h.writes()) {
            Account a = accounts.get(w.account());
            if (a != null && !a.joint() && w.newBalance() < 0) {
                count++;
                worst = Math.min(worst, w.newBalance());
                if (details.size() < MAX_DETAILS) {
                    details.add("account " + w.account() + " written as " + Money.format(w.newBalance()) + " by "
                            + Ids.shortId(w.newWriter()));
                }
            }
        }
        if (count > 0) {
            out.add(new Violation("account-rule", count + " write(s) left a single account overdrawn", worst, details));
        }
    }

    /**
     * Joint customers' accounts summed never go below zero. Checked on the final balances and on the running sum
     * of committed effects in commit order: a credit a debit relied on was always committed before it, so commit
     * order can only make a correct history look better, never worse.
     */
    private static void customerRule(History h, Map<Long, Account> accounts, List<Violation> out) {
        Map<Long, Long> running = new HashMap<>();
        Map<Long, Long> finalSum = new TreeMap<>();
        for (Account a : accounts.values()) {
            if (a.joint()) {
                running.merge(a.customerId(), a.initialBalance(), Long::sum);
                finalSum.merge(a.customerId(), h.finalBalances().getOrDefault(a.id(), 0L), Long::sum);
            }
        }
        if (running.isEmpty()) {
            return;
        }
        List<String> details = new ArrayList<>();
        long worst = 0;
        int count = 0;
        List<Txn> ordered = h.txns().stream()
                .filter(t -> !t.deltas().isEmpty() && t.committedAt() != null)
                .sorted(Comparator.comparing(Txn::committedAt).thenComparing(Txn::id))
                .toList();
        for (Txn t : ordered) {
            Map<Long, Long> touched = new HashMap<>();
            for (Delta d : t.deltas()) {
                Account a = accounts.get(d.account());
                if (a != null && a.joint()) {
                    touched.merge(a.customerId(), d.cents(), Long::sum);
                }
            }
            for (Map.Entry<Long, Long> e : touched.entrySet()) {
                long before = running.get(e.getKey());
                long after = before + e.getValue();
                running.put(e.getKey(), after);
                if (after < 0 && e.getValue() < 0) {
                    count++;
                    worst = Math.min(worst, after);
                    if (details.size() < MAX_DETAILS) {
                        details.add("customer " + e.getKey() + ": " + t.op() + " " + Ids.shortId(t.id()) + " took the sum"
                                + " from " + Money.format(before) + " to " + Money.format(after));
                    }
                }
            }
        }
        for (Map.Entry<Long, Long> e : finalSum.entrySet()) {
            if (e.getValue() < 0) {
                count++;
                worst = Math.min(worst, e.getValue());
                if (details.size() < MAX_DETAILS) {
                    details.add("customer " + e.getKey() + " ends the run at " + Money.format(e.getValue()));
                }
            }
        }
        if (count > 0) {
            out.add(new Violation("customer-rule", count + " time(s) a joint customer's accounts summed below " + Money.format(0),
                    worst, details));
        }
    }

    /**
     * A read-only report must see each committed transaction entirely or not at all. If it saw a transfer's write
     * to one account but the version before it on the other, its total is money that never existed.
     */
    private static void readConsistency(History h, VersionOrder versions, List<Violation> out) {
        Map<UUID, Txn> byId = new HashMap<>();
        h.txns().forEach(t -> byId.put(t.id(), t));
        List<String> details = new ArrayList<>();
        long totalOff = 0;
        int count = 0;
        for (Txn t : h.txns()) {
            if (!REPORT_OP.equals(t.op()) || t.reads().size() < 2) {
                continue;
            }
            long off = 0;
            String example = null;
            for (Read seen : t.reads()) {
                if (Ids.INIT.equals(seen.saw())) {
                    continue;
                }
                for (Read other : t.reads()) {
                    if (other.account() == seen.account()) {
                        continue;
                    }
                    int needed = versions.position(other.account(), seen.saw());
                    int got = versions.position(other.account(), other.saw());
                    if (needed >= 0 && got >= 0 && got < needed) {
                        Txn writer = byId.get(seen.saw());
                        long missed = writer == null ? 0 : writer.deltas().stream()
                                .filter(d -> d.account() == other.account()).mapToLong(Delta::cents).sum();
                        off -= missed;
                        if (example == null) {
                            example = "saw " + Ids.shortId(seen.saw()) + "'s write to account " + seen.account()
                                    + " but the version before it on account " + other.account();
                        }
                    }
                }
            }
            if (example != null) {
                count++;
                totalOff += Math.abs(off);
                long reported = t.reads().stream().mapToLong(Read::balance).sum();
                if (details.size() < MAX_DETAILS) {
                    details.add(t.op() + " " + Ids.shortId(t.id()) + " reported " + Money.format(reported) + " ("
                            + signed(off) + " versus any committed state): " + example);
                }
            }
        }
        if (count > 0) {
            out.add(new Violation("read-consistency", count + " report(s) mixed two database states", totalOff, details));
        }
    }

    private static String signed(long cents) {
        return (cents >= 0 ? "+" : "") + Money.format(cents);
    }
}
