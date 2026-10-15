package dev.skewer.bank.strategy.gotcha;

import org.springframework.stereotype.Component;

import dev.skewer.bank.api.OpRequest;
import dev.skewer.bank.core.AccountRow;
import dev.skewer.bank.core.AccountSql;
import dev.skewer.bank.core.TxContext;

/**
 * Read-then-write over plain JDBC, used by the gotchas. Plain SQL rather than JPA because without a transaction
 * JPA's dirty checking never flushes, which would hide the gotcha instead of showing it.
 */
@Component
public class NaiveSqlOps {

    private final AccountSql sql;

    public NaiveSqlOps(AccountSql sql) {
        this.sql = sql;
    }

    public void apply(TxContext ctx) {
        OpRequest r = ctx.request();
        switch (r.op()) {
            case OpRequest.TRANSFER -> transfer(ctx, r.from(), r.to(), r.amountCents());
            case OpRequest.WITHDRAW -> withdraw(ctx, r.account(), r.amountCents());
            case OpRequest.JOINT_WITHDRAW -> jointWithdraw(ctx, r.customer(), r.account(), r.amountCents());
            case OpRequest.TOTAL_BALANCE -> totalBalance(ctx, r.customer());
            default -> throw new IllegalArgumentException("unknown operation: " + r.op());
        }
    }

    private void transfer(TxContext ctx, long from, long to, long amount) {
        AccountRow source = read(ctx, from);
        AccountRow target = read(ctx, to);
        ctx.think();
        if (source.balanceCents() < amount) {
            ctx.reject("insufficient funds");
            return;
        }
        sql.setBalance(from, source.balanceCents() - amount, ctx.txnId());
        ctx.delta(from, -amount);
        sql.setBalance(to, target.balanceCents() + amount, ctx.txnId());
        ctx.delta(to, amount);
    }

    private void withdraw(TxContext ctx, long account, long amount) {
        AccountRow row = read(ctx, account);
        ctx.think();
        if (row.balanceCents() < amount) {
            ctx.reject("insufficient funds");
            return;
        }
        sql.setBalance(account, row.balanceCents() - amount, ctx.txnId());
        ctx.delta(account, -amount);
    }

    private void jointWithdraw(TxContext ctx, long customer, long account, long amount) {
        long sum = 0;
        AccountRow target = null;
        for (long id : sql.customerAccountIds(customer)) {
            AccountRow row = read(ctx, id);
            sum += row.balanceCents();
            if (id == account) {
                target = row;
            }
        }
        ctx.think();
        if (sum < amount || target == null) {
            ctx.reject("customer total does not cover it");
            return;
        }
        sql.setBalance(account, target.balanceCents() - amount, ctx.txnId());
        ctx.delta(account, -amount);
    }

    private void totalBalance(TxContext ctx, long customer) {
        long total = 0;
        boolean first = true;
        for (long id : sql.customerAccountIds(customer)) {
            if (!first) {
                ctx.think();
            }
            first = false;
            total += read(ctx, id).balanceCents();
        }
        ctx.total(total);
    }

    private AccountRow read(TxContext ctx, long id) {
        AccountRow row = sql.select(id);
        ctx.read(row);
        return row;
    }
}
