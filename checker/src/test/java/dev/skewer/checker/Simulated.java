package dev.skewer.checker;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import dev.skewer.checker.history.Account;
import dev.skewer.checker.history.Delta;
import dev.skewer.checker.history.History;
import dev.skewer.checker.history.Ids;
import dev.skewer.checker.history.Meta;
import dev.skewer.checker.history.Read;
import dev.skewer.checker.history.Txn;
import dev.skewer.checker.history.Write;

/** Random histories for property tests. */
final class Simulated {

    /** A version of one account: who wrote it and the balance it holds. */
    record Version(UUID writer, long balance) {
    }

    record Serial(History history, Map<Long, List<Version>> chains) {
    }

    private Simulated() {
    }

    /**
     * Transactions run one at a time: each reads the latest versions and writes the next ones. Such a history is
     * serializable by construction, so the checker must find nothing.
     */
    static Serial serial(Random rnd, int nAccounts, int nTxns) {
        List<Account> accounts = new ArrayList<>();
        Map<Long, List<Version>> chains = new LinkedHashMap<>();
        for (long a = 1; a <= nAccounts; a++) {
            long balance = 1_000 + rnd.nextInt(10_000);
            accounts.add(new Account(a, (a + 1) / 2, a % 4 >= 2, balance));
            chains.put(a, new ArrayList<>(List.of(new Version(Ids.INIT, balance))));
        }
        List<Txn> txns = new ArrayList<>();
        List<Write> writes = new ArrayList<>();
        long seq = 0;
        for (int i = 0; i < nTxns; i++) {
            UUID id = new UUID(1, i + 1);
            List<Long> ids = new ArrayList<>(chains.keySet());
            Collections.shuffle(ids, rnd);
            int kind = rnd.nextInt(4);
            List<Long> readSet = ids.subList(0, 1 + rnd.nextInt(ids.size()));
            List<Long> writeSet = switch (kind) {
                case 0 -> List.of();                                                    // read-only report
                case 1 -> readSet.subList(0, 1 + rnd.nextInt(readSet.size()));          // read-modify-write
                case 2 -> ids.subList(0, 1 + rnd.nextInt(ids.size()));                  // blind write
                default -> readSet.subList(0, 1);                                       // single-row RMW
            };
            List<Read> reads = new ArrayList<>();
            if (kind != 2) {
                for (long a : readSet) {
                    Version v = chains.get(a).getLast();
                    reads.add(new Read(a, v.writer(), v.balance()));
                }
            }
            List<Delta> deltas = new ArrayList<>();
            for (long a : writeSet) {
                Version prev = chains.get(a).getLast();
                long delta = -rnd.nextLong(prev.balance() + 1) + rnd.nextInt(2_000);
                long next = prev.balance() + delta;
                writes.add(new Write(++seq, a, prev.writer(), id, prev.balance(), next));
                chains.get(a).add(new Version(id, next));
                deltas.add(new Delta(a, delta));
            }
            String op = writeSet.isEmpty() ? "total_balance" : "transfer";
            txns.add(new Txn(id, "op-" + i, op, "sim", "serializable", "applied", reads, deltas,
                    writeSet.isEmpty() ? "client" : "log", Instant.EPOCH.plusMillis(i)));
        }
        Map<Long, Long> finals = new LinkedHashMap<>();
        chains.forEach((a, chain) -> finals.put(a, chain.getLast().balance()));
        Meta meta = new Meta("sim", "sim", "serializable", 0, Instant.EPOCH, 0, Map.of());
        return new Serial(new History(meta, accounts, txns, writes, finals, List.of()), chains);
    }

    /**
     * Arbitrary histories: random version chains and reads of random versions. Mostly not serializable; used to
     * cross-check graph algorithms, not the checker's verdicts.
     */
    static History arbitrary(Random rnd, int nAccounts, int nTxns, int nReads) {
        List<UUID> ids = new ArrayList<>();
        List<Txn> txns = new ArrayList<>();
        for (int i = 0; i < nTxns; i++) {
            ids.add(new UUID(2, i + 1));
        }
        List<Account> accounts = new ArrayList<>();
        List<Write> writes = new ArrayList<>();
        Map<Long, List<UUID>> chains = new LinkedHashMap<>();
        long seq = 0;
        for (long a = 1; a <= nAccounts; a++) {
            accounts.add(new Account(a, a, false, 0));
            List<UUID> shuffled = new ArrayList<>(ids);
            Collections.shuffle(shuffled, rnd);
            List<UUID> chain = new ArrayList<>(List.of(Ids.INIT));
            chain.addAll(shuffled.subList(0, rnd.nextInt(shuffled.size() + 1)));
            for (int k = 1; k < chain.size(); k++) {
                writes.add(new Write(++seq, a, chain.get(k - 1), chain.get(k), 0, 0));
            }
            chains.put(a, chain);
        }
        List<List<Read>> reads = new ArrayList<>();
        for (int i = 0; i < nTxns; i++) {
            reads.add(new ArrayList<>());
        }
        for (int r = 0; r < nReads; r++) {
            long a = 1 + rnd.nextInt(nAccounts);
            List<UUID> chain = chains.get(a);
            reads.get(rnd.nextInt(nTxns)).add(new Read(a, chain.get(rnd.nextInt(chain.size())), 0));
        }
        for (int i = 0; i < nTxns; i++) {
            txns.add(new Txn(ids.get(i), "op-" + i, "transfer", "sim", "serializable", "applied", reads.get(i),
                    List.of(), "log", Instant.EPOCH.plusMillis(i)));
        }
        Map<Long, Long> finals = new LinkedHashMap<>();
        chains.keySet().forEach(a -> finals.put(a, 0L));
        Meta meta = new Meta("arb", "sim", "serializable", 0, Instant.EPOCH, 0, Map.of());
        return new History(meta, accounts, txns, writes, finals, List.of());
    }
}
