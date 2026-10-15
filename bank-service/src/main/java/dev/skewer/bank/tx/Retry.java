package dev.skewer.bank.tx;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import dev.skewer.bank.api.OpResult;

/**
 * Retries a whole transaction on transient failures. It must sit outside the transactional boundary: after a
 * serialization failure the Postgres transaction is already aborted, and every further statement in it fails
 * with 25P02. Each attempt is a new transaction with a new id.
 */
public final class Retry {

    public static final int MAX_ATTEMPTS = 10;

    private Retry() {
    }

    public static OpResult transientFailures(Supplier<OpResult> attempt) {
        List<String> errors = new ArrayList<>();
        for (int n = 1; ; n++) {
            OpResult result = attempt.get();
            if (!result.failed() || !SqlStates.isTransient(result.sqlState()) || n == MAX_ATTEMPTS) {
                return result.withAttempts(n, errors);
            }
            errors.add(result.sqlState());
            backoff(n);
        }
    }

    private static void backoff(int attempt) {
        int capMs = Math.min(50, 1 << attempt);
        try {
            Thread.sleep(ThreadLocalRandom.current().nextInt(capMs + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
