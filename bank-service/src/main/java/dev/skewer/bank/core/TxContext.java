package dev.skewer.bank.core;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import dev.skewer.bank.api.OpRequest;
import dev.skewer.bank.api.OpResult;
import dev.skewer.bank.tx.SqlStates;
import dev.skewer.checker.history.Delta;
import dev.skewer.checker.history.Read;

/** One transaction attempt: its id, and the read set and effects it accumulates. */
public final class TxContext {

    private final UUID txnId = UUID.randomUUID();
    private final OpRequest request;
    private final List<Read> reads = new ArrayList<>();
    private final List<Delta> deltas = new ArrayList<>();
    private String outcome = OpResult.APPLIED;
    private String reason;
    private String isolation;
    private Long total;

    public TxContext(OpRequest request) {
        this.request = request;
    }

    public UUID txnId() {
        return txnId;
    }

    public OpRequest request() {
        return request;
    }

    /** Records that this transaction saw {@code writer}'s version of {@code account}, holding {@code balance}. */
    public void read(long account, UUID writer, long balance) {
        reads.add(new Read(account, writer, balance));
    }

    public void read(AccountRow row) {
        read(row.id(), row.lastWriter(), row.balanceCents());
    }

    public void delta(long account, long cents) {
        deltas.add(new Delta(account, cents));
    }

    public void reject(String why) {
        outcome = OpResult.REJECTED;
        reason = why;
        deltas.clear();
    }

    public boolean rejected() {
        return OpResult.REJECTED.equals(outcome);
    }

    public void total(long cents) {
        total = cents;
    }

    public void isolation(String level) {
        isolation = level;
    }

    /** Pause between read and write, or between a report's reads: the test profile's race-window widener. */
    public void think() {
        if (request.thinkMs() > 0) {
            try {
                Thread.sleep(request.thinkMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public List<Read> reads() {
        return reads;
    }

    public List<Delta> deltas() {
        return deltas;
    }

    public String outcome() {
        return outcome;
    }

    public String isolationOrUnknown() {
        return isolation == null ? "unknown" : isolation;
    }

    public OpResult toResult() {
        return new OpResult(request.opId(), outcome, txnId, isolation, List.copyOf(reads), List.copyOf(deltas), total,
                1, List.of(), null, reason);
    }

    /** The attempt aborted: nothing it did committed, except under the no-transaction gotcha. */
    public OpResult failed(Throwable error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        int newline = message.indexOf('\n');
        return new OpResult(request.opId(), OpResult.FAILED, txnId, isolation, List.copyOf(reads), List.of(), null,
                1, List.of(), SqlStates.of(error), newline > 0 ? message.substring(0, newline) : message);
    }
}
