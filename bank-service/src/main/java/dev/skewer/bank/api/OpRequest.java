package dev.skewer.bank.api;

/**
 * One banking operation, with the knobs the test profile exposes per request.
 *
 * @param op         transfer, withdraw, joint_withdraw or total_balance
 * @param strategy   which implementation runs it (see {@code Strategies})
 * @param isolation  default, read_committed, repeatable_read or serializable
 * @param account    for withdraw and joint_withdraw: the account debited
 * @param customer   for total_balance and joint_withdraw: whose accounts are read
 * @param thinkMs    pause between read and write inside the transaction; widens race windows
 * @param totalMode  for total_balance: {@code split} (one read per account) or {@code single} (one statement);
 *                   null lets the strategy choose
 * @param instrument false skips SHOW transaction_isolation and the txn_log insert, for throughput baselines
 */
public record OpRequest(
        String opId,
        String op,
        String strategy,
        String isolation,
        Long from,
        Long to,
        Long account,
        Long customer,
        long amountCents,
        int thinkMs,
        String totalMode,
        Boolean instrument) {

    public static final String TRANSFER = "transfer";
    public static final String WITHDRAW = "withdraw";
    public static final String JOINT_WITHDRAW = "joint_withdraw";
    public static final String TOTAL_BALANCE = "total_balance";

    public boolean instrumented() {
        return instrument == null || instrument;
    }
}
