package dev.skewer.bank.tx;

import java.util.function.Supplier;

import org.springframework.stereotype.Component;

/**
 * Picks the isolation level per request. This dispatch cannot live inside {@link IsolatedTx}: a call from one of
 * its methods to another would bypass the proxy and start no transaction at all (the self-invocation gotcha).
 */
@Component
public class TxRunner {

    private final IsolatedTx tx;

    public TxRunner(IsolatedTx tx) {
        this.tx = tx;
    }

    public <T> T run(String isolation, Supplier<T> work) {
        return switch (isolation == null ? "default" : isolation) {
            case "default" -> tx.atDefault(work);
            case "read_committed" -> tx.readCommitted(work);
            case "repeatable_read" -> tx.repeatableRead(work);
            case "serializable" -> tx.serializable(work);
            default -> throw new IllegalArgumentException("unknown isolation level: " + isolation);
        };
    }
}
