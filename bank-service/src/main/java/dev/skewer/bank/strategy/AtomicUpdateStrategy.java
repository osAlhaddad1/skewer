package dev.skewer.bank.strategy;

import java.util.Optional;

import org.springframework.stereotype.Component;

import dev.skewer.bank.core.AccountRow;
import dev.skewer.bank.core.AccountSql;
import dev.skewer.bank.core.AccountSql.JointDebit;
import dev.skewer.bank.core.AccountSql.Replaced;
import dev.skewer.bank.core.TxContext;
import dev.skewer.bank.core.TxnLogger;
import dev.skewer.bank.tx.TxRunner;

/**
 * Push the check into the UPDATE: {@code SET balance = balance - :amt WHERE id = :id AND balance >= :amt}. No
 * read-then-write gap in the application, so think time does not apply. The version each statement replaced
 * comes from {@code RETURNING old.*}.
 */
@Component
public class AtomicUpdateStrategy extends TransactionalStrategy {

    private final AccountSql sql;

    public AtomicUpdateStrategy(TxRunner tx, TxnLogger logger, AccountSql sql) {
        super(tx, logger);
        this.sql = sql;
    }

    @Override
    public String name() {
        return "atomic-update";
    }

    @Override
    protected void transfer(TxContext ctx, long from, long to, long amount) {
        Optional<Replaced> debited = sql.debitIfCovered(from, amount, ctx.txnId());
        if (debited.isEmpty()) {
            ctx.reject("insufficient funds");
            return;
        }
        ctx.read(from, debited.get().writer(), debited.get().balanceCents());
        Replaced credited = sql.credit(to, amount, ctx.txnId());
        ctx.read(to, credited.writer(), credited.balanceCents());
        ctx.delta(from, -amount);
        ctx.delta(to, amount);
    }

    @Override
    protected void withdraw(TxContext ctx, long account, long amount) {
        Optional<Replaced> debited = sql.debitIfCovered(account, amount, ctx.txnId());
        if (debited.isEmpty()) {
            ctx.reject("insufficient funds");
            return;
        }
        ctx.read(account, debited.get().writer(), debited.get().balanceCents());
        ctx.delta(account, -amount);
    }

    @Override
    protected void jointWithdraw(TxContext ctx, long customer, long account, long amount) {
        Optional<JointDebit> debited = sql.jointDebitIfCovered(account, customer, amount, ctx.txnId());
        if (debited.isEmpty()) {
            ctx.reject("customer total does not cover it");
            return;
        }
        ctx.read(account, debited.get().target().writer(), debited.get().target().balanceCents());
        if (debited.get().sibling() != null) {
            ctx.read(debited.get().sibling());
        }
        ctx.delta(account, -amount);
    }

    @Override
    protected void totalBalance(TxContext ctx, long customer) {
        long total = 0;
        for (AccountRow row : sql.customerAccounts(customer)) {
            ctx.read(row);
            total += row.balanceCents();
        }
        ctx.total(total);
    }
}
