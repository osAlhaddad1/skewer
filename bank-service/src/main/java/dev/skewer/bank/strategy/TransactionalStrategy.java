package dev.skewer.bank.strategy;

import dev.skewer.bank.api.OpRequest;
import dev.skewer.bank.api.OpResult;
import dev.skewer.bank.core.TxContext;
import dev.skewer.bank.core.TxnLogger;
import dev.skewer.bank.tx.TxRunner;

/**
 * A strategy whose operations each run in one {@code @Transactional} boundary at the requested isolation level,
 * ending with the shared instrumentation.
 */
public abstract class TransactionalStrategy implements Strategy {

    private final TxRunner tx;
    private final TxnLogger logger;

    protected TransactionalStrategy(TxRunner tx, TxnLogger logger) {
        this.tx = tx;
        this.logger = logger;
    }

    @Override
    public OpResult execute(OpRequest request) {
        return attempt(request);
    }

    /** One transaction. Retrying strategies call this repeatedly from outside the boundary. */
    protected OpResult attempt(OpRequest request) {
        TxContext ctx = new TxContext(request);
        try {
            tx.run(request.isolation(), () -> {
                apply(ctx);
                logger.finish(ctx);
                return null;
            });
            return ctx.toResult();
        } catch (RuntimeException e) {
            return ctx.failed(e);
        }
    }

    private void apply(TxContext ctx) {
        OpRequest r = ctx.request();
        switch (r.op()) {
            case OpRequest.TRANSFER -> transfer(ctx, r.from(), r.to(), r.amountCents());
            case OpRequest.WITHDRAW -> withdraw(ctx, r.account(), r.amountCents());
            case OpRequest.JOINT_WITHDRAW -> jointWithdraw(ctx, r.customer(), r.account(), r.amountCents());
            case OpRequest.TOTAL_BALANCE -> totalBalance(ctx, r.customer());
            default -> throw new IllegalArgumentException("unknown operation: " + r.op());
        }
        afterApply(ctx);
    }

    /** Moves money between two accounts; the source must stay at or above zero. */
    protected abstract void transfer(TxContext ctx, long from, long to, long amount);

    /** Account rule: the balance must stay at or above zero. */
    protected abstract void withdraw(TxContext ctx, long account, long amount);

    /** Customer rule: the customer's accounts summed must stay at or above zero; each may go negative. */
    protected abstract void jointWithdraw(TxContext ctx, long customer, long account, long amount);

    /** Read-only: the customer's total balance. */
    protected abstract void totalBalance(TxContext ctx, long customer);

    /** Hook before the instrumentation runs, e.g. to flush the persistence context. */
    protected void afterApply(TxContext ctx) {
    }
}
