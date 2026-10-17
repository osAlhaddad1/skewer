package dev.skewer.workload;

import dev.skewer.checker.Money;

/** One generated operation, before it is sent. Unused fields are null. */
public record PlannedOp(String op, Long from, Long to, Long account, Long customer, long amountCents, int thinkMs) {

    public static final String TRANSFER = "transfer";
    public static final String WITHDRAW = "withdraw";
    public static final String JOINT_WITHDRAW = "joint_withdraw";
    public static final String TOTAL_BALANCE = "total_balance";

    /** Human-readable parameters for reports: "1 → 2, €12.00". */
    public String describe() {
        return switch (op) {
            case TRANSFER -> from + " → " + to + ", " + Money.format(amountCents);
            case WITHDRAW, JOINT_WITHDRAW -> "account " + account + ", " + Money.format(amountCents);
            default -> "customer " + customer;
        };
    }
}
