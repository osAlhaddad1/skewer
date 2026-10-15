package dev.skewer.bank.admin;

import java.util.List;
import java.util.Map;

import dev.skewer.checker.history.Txn;
import dev.skewer.checker.history.Write;

/** What the database recorded during a run: committed transactions, the version chain, final balances. */
public record ServerHistory(List<Txn> txns, List<Write> writes, Map<Long, Long> finalBalances) {
}
