package dev.skewer.bank.strategy;

import java.util.ArrayList;
import java.util.List;

import dev.skewer.bank.core.AccountRow;
import dev.skewer.bank.core.AccountSql;
import dev.skewer.bank.core.TxContext;
import dev.skewer.bank.core.TxnLogger;
import dev.skewer.bank.tx.TxRunner;

/**
 * {@code SELECT ... FOR UPDATE} on every account involved before reading balances. With {@code ordered}, locks
 * are taken in account-id order; without it, in request order, so two opposite-direction transfers can each hold
 * the lock the other needs (deadlock, SQLSTATE 40P01).
 */
public class PessimisticStrategy extends TransactionalStrategy {

    private final AccountSql sql;
    private final boolean ordered;

    public PessimisticStrategy(TxRunner tx, TxnLogger logger, AccountSql sql, boolean ordered) {
        super(tx, logger);
        this.sql = sql;
        this.ordered = ordered;
    }

    @Override
    public String name() {
        return ordered ? "pessimistic" : "pessimistic-unordered";
    }

    @Override
    protected void transfer(TxContext ctx, long from, long to, long amount) {
        AccountRow source;
        AccountRow target;
        if (ordered && to < from) {
            target = lock(ctx, to);
            source = lock(ctx, from);
        } else {
            source = lock(ctx, from);
            target = lock(ctx, to);
        }
        ctx.think();
        if (source.balanceCents() < amount) {
            ctx.reject("insufficient funds");
            return;
        }
        sql.setBalance(from, source.balanceCents() - amount, ctx.txnId());
        sql.setBalance(to, target.balanceCents() + amount, ctx.txnId());
        ctx.delta(from, -amount);
        ctx.delta(to, amount);
    }

    @Override
    protected void withdraw(TxContext ctx, long account, long amount) {
        AccountRow row = lock(ctx, account);
        ctx.think();
        if (row.balanceCents() < amount) {
            ctx.reject("insufficient funds");
            return;
        }
        sql.setBalance(account, row.balanceCents() - amount, ctx.txnId());
        ctx.delta(account, -amount);
    }

    @Override
    protected void jointWithdraw(TxContext ctx, long customer, long account, long amount) {
        List<Long> ids = new ArrayList<>(sql.customerAccountIds(customer));
        if (!ordered) {
            // Request order: the account being debited first, then the rest.
            ids.remove(Long.valueOf(account));
            ids.addFirst(account);
        }
        long sum = 0;
        AccountRow target = null;
        for (long id : ids) {
            AccountRow row = lock(ctx, id);
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

    /** Shared locks in id order: writers wait for the report, the report never sees half a transfer. */
    @Override
    protected void totalBalance(TxContext ctx, long customer) {
        long total = 0;
        boolean first = true;
        for (long id : sql.customerAccountIds(customer)) {
            if (!first) {
                ctx.think();
            }
            first = false;
            AccountRow row = sql.selectForShare(id);
            ctx.read(row);
            total += row.balanceCents();
        }
        ctx.total(total);
    }

    private AccountRow lock(TxContext ctx, long id) {
        AccountRow row = sql.selectForUpdate(id);
        ctx.read(row);
        return row;
    }
}
