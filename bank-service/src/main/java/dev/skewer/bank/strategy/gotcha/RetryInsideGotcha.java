package dev.skewer.bank.strategy.gotcha;

import java.util.ArrayList;
import java.util.List;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import dev.skewer.bank.api.OpRequest;
import dev.skewer.bank.api.OpResult;
import dev.skewer.bank.core.TxContext;
import dev.skewer.bank.core.TxnLogger;
import dev.skewer.bank.strategy.Strategy;
import dev.skewer.bank.tx.SqlStates;

/**
 * Gotcha: the retry loop sits inside the {@code @Transactional} method. After a serialization failure the
 * Postgres transaction is already aborted, so the retry's first statement fails with 25P02 ("current
 * transaction is aborted"). And a failure raised at commit happens after the method returned, out of the loop's
 * reach entirely. The retry must wrap the whole transaction.
 */
@Component
public class RetryInsideGotcha implements Strategy {

    static final int ATTEMPTS = 3;

    private final Body body;

    public RetryInsideGotcha(Body body) {
        this.body = body;
    }

    @Override
    public String name() {
        return "gotcha-retry-inside";
    }

    @Override
    public OpResult execute(OpRequest request) {
        List<String> errors = new ArrayList<>();
        TxContext last = new TxContext(request);
        try {
            OpResult result = body.withdrawWithRetry(request, errors);
            return result.withAttempts(errors.size() + 1, errors);
        } catch (RuntimeException e) {
            return last.failed(e).withAttempts(errors.size() + 1, errors);
        }
    }

    /** A separate bean, so the call from {@link #execute} goes through the transactional proxy. */
    @Component
    public static class Body {

        private final NaiveSqlOps ops;
        private final TxnLogger logger;

        public Body(NaiveSqlOps ops, TxnLogger logger) {
            this.ops = ops;
            this.logger = logger;
        }

        @Transactional(isolation = Isolation.SERIALIZABLE)
        public OpResult withdrawWithRetry(OpRequest request, List<String> errors) {
            for (int attempt = 1; ; attempt++) {
                TxContext ctx = new TxContext(request);
                try {
                    ops.apply(ctx);
                    logger.finish(ctx);
                    return ctx.toResult();
                } catch (DataAccessException e) {
                    String state = SqlStates.of(e);
                    if (!SqlStates.isTransient(state) || attempt == ATTEMPTS) {
                        throw e;
                    }
                    errors.add(state);
                }
            }
        }
    }
}
