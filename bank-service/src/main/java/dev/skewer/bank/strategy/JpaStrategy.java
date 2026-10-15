package dev.skewer.bank.strategy;

import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import dev.skewer.bank.core.AccountRow;
import dev.skewer.bank.core.AccountSql;
import dev.skewer.bank.core.TxContext;
import dev.skewer.bank.core.TxnLogger;
import dev.skewer.bank.tx.TxRunner;

/**
 * The textbook Spring Data JPA shape: load the entity, compute the new balance in Java, let dirty checking write
 * it at flush. Whether that is safe depends entirely on the mapping and the isolation level around it.
 */
public abstract class JpaStrategy<E extends dev.skewer.bank.jpa.BankAccount> extends TransactionalStrategy {

    @PersistenceContext
    private EntityManager em;

    private final AccountSql sql;
    private final Class<E> type;

    protected JpaStrategy(TxRunner tx, TxnLogger logger, AccountSql sql, Class<E> type) {
        super(tx, logger);
        this.sql = sql;
        this.type = type;
    }

    protected EntityManager em() {
        return em;
    }

    /** findById, recording which version came back. */
    protected E load(TxContext ctx, long id) {
        E account = em.find(type, id);
        ctx.read(id, account.getLastWriter(), account.getBalanceCents());
        return account;
    }

    protected void write(TxContext ctx, E account, long newBalance) {
        ctx.delta(account.getId(), newBalance - account.getBalanceCents());
        account.setBalanceCents(newBalance);
        account.setLastWriter(ctx.txnId());
    }

    @Override
    protected void transfer(TxContext ctx, long from, long to, long amount) {
        E source = load(ctx, from);
        E target = load(ctx, to);
        ctx.think();
        if (source.getBalanceCents() < amount) {
            ctx.reject("insufficient funds");
            return;
        }
        write(ctx, source, source.getBalanceCents() - amount);
        write(ctx, target, target.getBalanceCents() + amount);
    }

    @Override
    protected void withdraw(TxContext ctx, long account, long amount) {
        E a = load(ctx, account);
        ctx.think();
        if (a.getBalanceCents() < amount) {
            ctx.reject("insufficient funds");
            return;
        }
        write(ctx, a, a.getBalanceCents() - amount);
    }

    @Override
    protected void jointWithdraw(TxContext ctx, long customer, long account, long amount) {
        List<E> all = new ArrayList<>();
        for (long id : sql.customerAccountIds(customer)) {
            all.add(load(ctx, id));
        }
        ctx.think();
        long sum = all.stream().mapToLong(E::getBalanceCents).sum();
        if (sum < amount) {
            ctx.reject("customer total does not cover it");
            return;
        }
        E target = all.stream().filter(a -> a.getId() == account).findFirst().orElseThrow();
        write(ctx, target, target.getBalanceCents() - amount);
        onJointDebit(ctx, all, target);
    }

    /** Hook for strategies that also need to protect the rows a joint withdrawal only read. */
    protected void onJointDebit(TxContext ctx, List<E> all, E target) {
    }

    /**
     * Two findById calls by default, with the think time between them (the report's race window);
     * {@code totalMode=single} reads all accounts in one SQL statement instead.
     */
    @Override
    protected void totalBalance(TxContext ctx, long customer) {
        long total = 0;
        if ("single".equals(ctx.request().totalMode())) {
            for (AccountRow row : sql.customerAccounts(customer)) {
                ctx.read(row);
                total += row.balanceCents();
            }
        } else {
            List<Long> ids = sql.customerAccountIds(customer);
            for (int i = 0; i < ids.size(); i++) {
                if (i > 0) {
                    ctx.think();
                }
                total += load(ctx, ids.get(i)).getBalanceCents();
            }
        }
        ctx.total(total);
    }

    @Override
    protected void afterApply(TxContext ctx) {
        // Write the UPDATEs now, so the txn_log insert stays the transaction's last statement.
        em.flush();
    }
}
