package dev.skewer.workload;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.HdrHistogram.Histogram;

import dev.skewer.checker.history.ClientOp;
import dev.skewer.checker.history.History;
import dev.skewer.checker.history.Meta;
import dev.skewer.checker.history.Txn;
import dev.skewer.workload.BankClient.OpResponse;
import dev.skewer.workload.BankClient.ServerHistory;

/**
 * Drives the bank service with concurrent clients on virtual threads, then merges what the clients saw with what
 * the database recorded into one history.
 */
public final class Workload {

    /** What one run produced. {@code history} is null for uninstrumented throughput baselines. */
    public record RunResult(History history, RunMetrics metrics) {
    }

    private final BankClient client;

    public Workload(URI baseUrl) {
        this.client = new BankClient(baseUrl);
    }

    public BankClient client() {
        return client;
    }

    public RunResult run(String runId, String strategy, String isolation, long seed, WorkloadConfig config) {
        AccountPool pool = new AccountPool(config);
        client.reset(pool.accounts());
        if (!config.instrument()) {
            client.instrumentation(false);
        }

        Instant startedAt = Instant.now();
        long start = System.nanoTime();
        long deadline = start + config.durationMs() * 1_000_000L;
        AtomicInteger issued = new AtomicInteger();
        Collection<ClientOp> ops = new ConcurrentLinkedQueue<>();
        Collection<Txn> reports = new ConcurrentLinkedQueue<>();
        List<Histogram> latencies = new ArrayList<>();
        for (int c = 0; c < config.concurrency(); c++) {
            latencies.add(new Histogram(60_000_000L, 3));
        }

        try (ExecutorService clients = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int c = 0; c < config.concurrency(); c++) {
                int clientNo = c;
                clients.submit(() -> clientLoop(clientNo, strategy, isolation, seed, config, pool, start, deadline,
                        issued, ops, reports, latencies.get(clientNo), startedAt));
            }
        }
        long durationMs = (System.nanoTime() - start) / 1_000_000;
        Histogram all = new Histogram(60_000_000L, 3);
        latencies.forEach(all::add);
        // Timed-out operations may still be running server-side and may still commit: wait for them.
        client.quiesce();

        if (!config.instrument()) {
            client.instrumentation(true);
            return new RunResult(null, RunMetrics.of(ops, all, durationMs, 0));
        }

        ServerHistory server = client.history();
        Set<String> committedOps = new HashSet<>();
        server.txns().forEach(t -> committedOps.add(t.opId()));
        int indeterminateCommitted = (int) ops.stream()
                .filter(o -> o.outcome().equals("indeterminate") && committedOps.contains(o.opId())).count();

        List<Txn> txns = new ArrayList<>(server.txns());
        txns.addAll(reports);
        List<ClientOp> clientOps = ops.stream()
                .sorted((a, b) -> Long.compare(a.startMicros(), b.startMicros()))
                .toList();
        Meta meta = new Meta(runId, strategy, isolation, seed, startedAt, durationMs * 1000, config.describe());
        History history = new History(meta, pool.accounts(), txns, server.writes(), server.finalBalances(), clientOps);
        return new RunResult(history, RunMetrics.of(ops, all, durationMs, indeterminateCommitted));
    }

    private void clientLoop(int clientNo, String strategy, String isolation, long seed, WorkloadConfig config,
                            AccountPool pool, long start, long deadline, AtomicInteger issued,
                            Collection<ClientOp> ops, Collection<Txn> reports, Histogram latency, Instant startedAt) {
        OpGenerator gen = new OpGenerator(seed, clientNo, config, pool);
        for (int n = 0; ; n++) {
            if (System.nanoTime() >= deadline || issued.incrementAndGet() > config.maxOps()) {
                return;
            }
            PlannedOp op = gen.next();
            String opId = "c" + clientNo + "-" + n;
            long t0 = System.nanoTime();
            OpResponse r;
            String outcome;
            try {
                r = client.execute(opId, op, strategy, isolation, config, config.timeoutMs());
                outcome = r.outcome();
            } catch (BankClient.Indeterminate e) {
                r = null;
                outcome = "indeterminate";
            } catch (IOException e) {
                r = null;
                outcome = "failed";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            long t1 = System.nanoTime();
            latency.recordValue(Math.min(60_000_000L, Math.max(1, (t1 - t0) / 1000)));
            long startMicros = (t0 - start) / 1000;
            long endMicros = (t1 - start) / 1000;
            ops.add(new ClientOp(opId, clientNo, op.op(), op.describe(), outcome,
                    r == null ? (outcome.equals("failed") ? "io" : null) : r.sqlState(),
                    r == null ? null : r.txnId(),
                    r == null ? 1 : r.attempts(),
                    r == null ? List.of() : r.attemptErrors(),
                    startMicros, endMicros));
            // Read-only reports leave no txn_log row: record them from the response.
            if (r != null && PlannedOp.TOTAL_BALANCE.equals(op.op()) && "applied".equals(r.outcome())
                    && config.instrument()) {
                reports.add(new Txn(r.txnId(), opId, op.op(), strategy, r.isolation(), r.outcome(), r.reads(),
                        List.of(), "client", startedAt.plusNanos(t1 - start)));
            }
        }
    }
}
