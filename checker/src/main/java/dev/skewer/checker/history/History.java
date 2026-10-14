package dev.skewer.checker.history;

import java.util.List;
import java.util.Map;

/**
 * Everything the checker needs about one run. The checker only ever reads histories, so any saved run can be
 * re-analysed after a checker fix without rerunning the workload.
 */
public record History(
        Meta meta,
        List<Account> accounts,
        List<Txn> txns,
        List<Write> writes,
        Map<Long, Long> finalBalances,
        List<ClientOp> clientOps) {

    public History {
        accounts = List.copyOf(accounts);
        txns = List.copyOf(txns);
        writes = List.copyOf(writes);
        finalBalances = Map.copyOf(finalBalances);
        clientOps = List.copyOf(clientOps);
    }
}
