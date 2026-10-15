package dev.skewer.bank.tx;

import java.util.function.Supplier;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Real {@code @Transactional} boundaries, one per isolation level, so every strategy runs inside exactly the
 * proxy-managed transaction Spring code uses in production. Callers must go through the Spring proxy: see
 * {@link TxRunner}, which lives in a separate bean for exactly that reason.
 */
@Component
public class IsolatedTx {

    /** Plain {@code @Transactional}: Isolation.DEFAULT, which means the database default, Read Committed on Postgres. */
    @Transactional
    public <T> T atDefault(Supplier<T> work) {
        return work.get();
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public <T> T readCommitted(Supplier<T> work) {
        return work.get();
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public <T> T repeatableRead(Supplier<T> work) {
        return work.get();
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public <T> T serializable(Supplier<T> work) {
        return work.get();
    }
}
