package dev.skewer.workload;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;

import dev.skewer.checker.history.Account;
import dev.skewer.checker.history.Delta;
import dev.skewer.checker.history.HistoryIO;
import dev.skewer.checker.history.Read;
import dev.skewer.checker.history.Txn;
import dev.skewer.checker.history.Write;

/** HTTP client for the bank service. The workload knows the service only through this API. */
public final class BankClient {

    /** The service's answer to one operation; mirrors its OpResult. */
    public record OpResponse(String opId, String outcome, UUID txnId, String isolation, List<Read> reads,
                             List<Delta> deltas, Long totalCents, int attempts, List<String> attemptErrors,
                             String sqlState, String message) {
    }

    /** What the database recorded; mirrors the service's ServerHistory. */
    public record ServerHistory(List<Txn> txns, List<Write> writes, Map<Long, Long> finalBalances) {
    }

    /** Thrown when the client gave up waiting: the operation's fate is unknown until txn_log is read. */
    public static final class Indeterminate extends Exception {
        Indeterminate(Throwable cause) {
            super(cause);
        }
    }

    private final URI base;
    private final HttpClient http;

    public BankClient(URI base) {
        this.base = base;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
    }

    public OpResponse execute(String opId, PlannedOp op, String strategy, String isolation, WorkloadConfig config,
                              int timeoutMs) throws Indeterminate, IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("opId", opId);
        body.put("op", op.op());
        body.put("strategy", strategy);
        body.put("isolation", isolation);
        body.put("from", op.from());
        body.put("to", op.to());
        body.put("account", op.account());
        body.put("customer", op.customer());
        body.put("amountCents", op.amountCents());
        body.put("thinkMs", op.thinkMs());
        body.put("totalMode", config.totalMode());
        body.put("instrument", config.instrument());
        HttpRequest request = HttpRequest.newBuilder(base.resolve("/api/ops"))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(HistoryIO.JSON.writeValueAsString(body)))
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new Indeterminate(e);
        }
        if (response.statusCode() != 200) {
            return new OpResponse(opId, "failed", null, null, List.of(), List.of(), null, 1, List.of(),
                    "http-" + response.statusCode(), response.body());
        }
        return HistoryIO.JSON.readValue(response.body(), OpResponse.class);
    }

    public void reset(List<Account> accounts) {
        send(HttpRequest.newBuilder(base.resolve("/admin/reset"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(HistoryIO.JSON.writeValueAsString(accounts))));
    }

    public void instrumentation(boolean enabled) {
        send(HttpRequest.newBuilder(base.resolve("/admin/instrumentation?enabled=" + enabled))
                .POST(HttpRequest.BodyPublishers.noBody()));
    }

    /** Blocks until the service has finished every operation, including ones whose client gave up. */
    public void quiesce() {
        send(HttpRequest.newBuilder(base.resolve("/admin/quiesce?timeoutMs=60000"))
                .POST(HttpRequest.BodyPublishers.noBody()));
    }

    public ServerHistory history() {
        return HistoryIO.JSON.readValue(send(HttpRequest.newBuilder(base.resolve("/admin/history")).GET()),
                ServerHistory.class);
    }

    public boolean isUp() {
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(base.resolve("/api/strategies"))
                    .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private String send(HttpRequest.Builder builder) {
        try {
            HttpResponse<String> r = http.send(builder.timeout(Duration.ofSeconds(60)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) {
                throw new IllegalStateException("bank service answered " + r.statusCode() + ": " + r.body());
            }
            return r.body();
        } catch (IOException e) {
            throw new IllegalStateException("bank service unreachable at " + base, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
