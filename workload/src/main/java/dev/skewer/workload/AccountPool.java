package dev.skewer.workload;

import java.util.ArrayList;
import java.util.List;

import dev.skewer.checker.history.Account;

/**
 * The contention pool. Customer {@code c} owns accounts {@code 2c-1} and {@code 2c}. The first customers hold
 * single accounts (account rule), the rest joint accounts (customer rule). Money only flows into joint accounts,
 * never out by transfer: a transfer out of a joint account could break the customer rule even when run serially.
 */
public final class AccountPool {

    private final List<Account> accounts = new ArrayList<>();
    private final List<Long> single = new ArrayList<>();
    private final List<Long> joint = new ArrayList<>();
    private final int customers;
    private final int jointCustomers;

    public AccountPool(WorkloadConfig config) {
        customers = config.accounts() / 2;
        jointCustomers = Math.min(customers - 1, (int) Math.round(customers * config.jointCustomerShare()));
        for (long c = 1; c <= customers; c++) {
            boolean isJoint = c > customers - jointCustomers;
            long balance = isJoint ? config.jointInitialCents() : config.singleInitialCents();
            for (long id = 2 * c - 1; id <= 2 * c; id++) {
                accounts.add(new Account(id, c, isJoint, balance));
                (isJoint ? joint : single).add(id);
            }
        }
    }

    public List<Account> accounts() {
        return accounts;
    }

    public List<Long> single() {
        return single;
    }

    public List<Long> joint() {
        return joint;
    }

    public int customers() {
        return customers;
    }

    public boolean hasJoint() {
        return !joint.isEmpty();
    }

    public static long customerOf(long account) {
        return (account + 1) / 2;
    }

    public static long siblingOf(long account) {
        return account % 2 == 1 ? account + 1 : account - 1;
    }
}
