package dev.skewer.checker;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.skewer.checker.history.Account;
import dev.skewer.checker.history.Delta;
import dev.skewer.checker.history.History;
import dev.skewer.checker.history.Ids;
import dev.skewer.checker.history.Meta;
import dev.skewer.checker.history.Read;
import dev.skewer.checker.history.Txn;
import dev.skewer.checker.history.Write;

/** Builds histories by hand, with transactions named instead of UUID'd. "init" is the initial version. */
final class HistoryBuilder {

    private final List<Account> accounts = new ArrayList<>();
    private final List<Txn> txns = new ArrayList<>();
    private final List<Write> writes = new ArrayList<>();
    private final Map<Long, Long> finals = new LinkedHashMap<>();
    private long seq;
    private long clock;

    static UUID id(String name) {
        return name.equals("init") ? Ids.INIT : UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }

    HistoryBuilder single(long id, long customer, long balance) {
        accounts.add(new Account(id, customer, false, balance));
        finals.put(id, balance);
        return this;
    }

    HistoryBuilder joint(long id, long customer, long balance) {
        accounts.add(new Account(id, customer, true, balance));
        finals.put(id, balance);
        return this;
    }

    /** Records a committed write and makes it the account's final balance. */
    HistoryBuilder write(long account, String prev, String writer, long oldBalance, long newBalance) {
        writes.add(new Write(++seq, account, id(prev), id(writer), oldBalance, newBalance));
        finals.put(account, newBalance);
        return this;
    }

    TxnBuilder txn(String name, String op) {
        return new TxnBuilder(name, op);
    }

    History build() {
        Meta meta = new Meta("test", "hand-built", "serializable", 0, Instant.EPOCH, 0, Map.of());
        return new History(meta, accounts, txns, writes, finals, List.of());
    }

    final class TxnBuilder {
        private final String name;
        private final String op;
        private final List<Read> reads = new ArrayList<>();
        private final List<Delta> deltas = new ArrayList<>();

        TxnBuilder(String name, String op) {
            this.name = name;
            this.op = op;
        }

        TxnBuilder read(long account, String writer, long balance) {
            reads.add(new Read(account, id(writer), balance));
            return this;
        }

        TxnBuilder delta(long account, long cents) {
            deltas.add(new Delta(account, cents));
            return this;
        }

        HistoryBuilder commit() {
            txns.add(new Txn(id(name), name, op, "test", "serializable", "applied", reads, deltas,
                    deltas.isEmpty() ? "client" : "log", Instant.EPOCH.plusMillis(++clock)));
            return HistoryBuilder.this;
        }
    }
}
