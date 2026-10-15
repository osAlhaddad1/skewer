package dev.skewer.bank.api;

import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

/**
 * Counts operations the service is still executing. A client that timed out has moved on, but its transaction
 * may still commit; a run is only over, and the next one may only reset the database, once this reaches zero.
 */
@Component
public class InFlight {

    private final AtomicInteger count = new AtomicInteger();

    public void enter() {
        count.incrementAndGet();
    }

    public void exit() {
        count.decrementAndGet();
    }

    /** Waits until nothing is executing. Returns false if that did not happen within {@code timeoutMs}. */
    public boolean awaitIdle(long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (count.get() > 0) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            Thread.sleep(10);
        }
        return true;
    }

    public int current() {
        return count.get();
    }
}
