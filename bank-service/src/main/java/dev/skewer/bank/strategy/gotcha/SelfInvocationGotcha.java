package dev.skewer.bank.strategy.gotcha;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import dev.skewer.bank.api.OpRequest;
import dev.skewer.bank.api.OpResult;
import dev.skewer.bank.core.TxContext;
import dev.skewer.bank.core.TxnLogger;
import dev.skewer.bank.strategy.Strategy;

/**
 * Gotcha: a public method calls a {@code @Transactional} method of the same class. The call goes through
 * {@code this}, not the Spring proxy, so the annotation is ignored: no transaction starts, and every statement
 * auto-commits on its own at Read Committed, whatever isolation the annotation asks for.
 */
@Component
public class SelfInvocationGotcha implements Strategy {

    private final NaiveSqlOps ops;
    private final TxnLogger logger;

    public SelfInvocationGotcha(NaiveSqlOps ops, TxnLogger logger) {
        this.ops = ops;
        this.logger = logger;
    }

    @Override
    public String name() {
        return "gotcha-self-invocation";
    }

    @Override
    public OpResult execute(OpRequest request) {
        TxContext ctx = new TxContext(request);
        try {
            return withdrawSafely(ctx);
        } catch (RuntimeException e) {
            return ctx.failed(e);
        }
    }

    /** Looks serializable. Is not even transactional when called from {@link #execute}. */
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public OpResult withdrawSafely(TxContext ctx) {
        ops.apply(ctx);
        logger.finish(ctx);
        return ctx.toResult();
    }
}
