package dev.skewer.bank;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import dev.skewer.bank.admin.BankAdmin;
import dev.skewer.bank.admin.ServerHistory;
import dev.skewer.bank.api.OpRequest;
import dev.skewer.bank.api.OpResult;
import dev.skewer.bank.strategy.Strategies;
import dev.skewer.checker.CheckResult;
import dev.skewer.checker.Checker;
import dev.skewer.checker.history.Account;
import dev.skewer.checker.history.History;
import dev.skewer.checker.history.Meta;

/**
 * Each strategy against a real Postgres 18. Think time makes two transactions overlap deterministically, so a
 * race either happens every time or is prevented every time, and the checker must say which.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class StrategiesIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");

    private static final List<Account> ACCOUNTS = List.of(
            new Account(1, 1, false, 100_00), new Account(2, 1, false, 100_00),
            new Account(3, 2, true, 50_00), new Account(4, 2, true, 50_00));

    @Autowired
    Strategies strategies;

    @Autowired
    BankAdmin admin;

    @BeforeEach
    void reset() {
        admin.reset(ACCOUNTS);
    }

    @Test
    void plainTransactionalRunsAtReadCommittedAndLogsIt() {
        OpResult r = run("naive-jpa", "default", withdraw("a", 1, 10_00, 0));

        assertThat(r.outcome()).isEqualTo("applied");
        assertThat(r.isolation()).isEqualTo("read committed");
        ServerHistory h = admin.export();
        assertThat(h.txns()).singleElement().satisfies(t -> {
            assertThat(t.isolation()).isEqualTo("read committed");
            assertThat(t.dbXid()).isNotBlank();
        });
        assertThat(h.writes()).singleElement().satisfies(w -> assertThat(w.newBalance()).isEqualTo(90_00));
        assertThat(h.finalBalances()).containsEntry(1L, 90_00L);
    }

    @Test
    void everyStrategyMovesMoneyAndRejectsOverdrafts() {
        for (String s : List.of("naive-jpa", "atomic-update", "pessimistic", "pessimistic-unordered", "optimistic",
                "optimistic-force-increment", "serializable-retry", "gotcha-self-invocation", "gotcha-retry-inside")) {
            admin.reset(ACCOUNTS);
            OpResult t = run(s, "serializable", new OpRequest(s + "-t", "transfer", s, "serializable", 1L, 2L, null, null,
                    30_00, 0, null, true));
            OpResult w = run(s, "serializable", withdraw(s + "-w", 1, 500_00, 0));
            OpResult total = run(s, "serializable", new OpRequest(s + "-r", "total_balance", s, "serializable", null, null,
                    null, 1L, 0, 0, null, true));

            assertThat(t.outcome()).as(s + " transfer").isEqualTo("applied");
            assertThat(w.outcome()).as(s + " overdraft").isEqualTo("rejected");
            assertThat(total.totalCents()).as(s + " total").isEqualTo(200_00);
            assertThat(admin.export().finalBalances()).as(s).containsEntry(1L, 70_00L).containsEntry(2L, 130_00L);
        }
    }

    @Test
    void selfInvocationRunsWithoutATransactionDespiteTheAnnotation() {
        OpResult r = run("gotcha-self-invocation", "serializable", new OpRequest("t", "transfer",
                "gotcha-self-invocation", "serializable", 1L, 2L, null, null, 10_00, 0, null, true));

        assertThat(r.isolation()).isEqualTo("read committed");
        ServerHistory h = admin.export();
        // Each statement committed on its own: three different database transactions for one "transaction".
        assertThat(h.writes()).extracting(w -> w.dbXid()).doesNotContain(h.txns().getFirst().dbXid());
    }

    @Test
    void naiveJpaAtReadCommittedLosesAnUpdate() {
        // Both withdrawals read €100, wait, and write: the second overwrites the first.
        List<OpResult> results = concurrently(
                () -> run("naive-jpa", "read_committed", withdraw("a", 1, 60_00, 300)),
                () -> run("naive-jpa", "read_committed", withdraw("b", 1, 30_00, 300)));

        assertThat(results).allMatch(r -> r.outcome().equals("applied"));
        CheckResult check = check("naive-jpa", "read_committed");
        assertThat(check.labels()).containsKey("lost update");
        assertThat(check.violations()).anyMatch(v -> v.kind().equals("conservation"));
    }

    @Test
    void pessimisticLockingPreventsTheLostUpdate() {
        concurrently(
                () -> run("pessimistic", "read_committed", withdraw("a", 1, 60_00, 300)),
                () -> run("pessimistic", "read_committed", withdraw("b", 1, 30_00, 300)));

        assertThat(admin.export().finalBalances()).containsEntry(1L, 10_00L);
        assertThat(check("pessimistic", "read_committed").clean()).isTrue();
    }

    @Test
    void naiveJpaAtRepeatableReadAbortsTheSecondWriter() {
        List<OpResult> results = concurrently(
                () -> run("naive-jpa", "repeatable_read", withdraw("a", 1, 60_00, 300)),
                () -> run("naive-jpa", "repeatable_read", withdraw("b", 1, 30_00, 300)));

        assertThat(results).extracting(OpResult::sqlState).contains("40001");
        assertThat(check("naive-jpa", "repeatable_read").clean()).isTrue();
    }

    @Test
    void atomicUpdateRechecksItsWhereClauseAfterTheLock() {
        List<OpResult> results = concurrently(
                () -> run("atomic-update", "read_committed", withdraw("a", 1, 60_00, 0)),
                () -> run("atomic-update", "read_committed", withdraw("b", 1, 60_00, 0)));

        assertThat(results).extracting(OpResult::outcome).containsExactlyInAnyOrder("applied", "rejected");
        assertThat(admin.export().finalBalances()).containsEntry(1L, 40_00L);
    }

    @Test
    void optimisticLockingStillAllowsWriteSkew() {
        // Joint accounts €50 + €50. Each withdrawal of €80 sees €100 and passes; they write different rows.
        concurrently(
                () -> run("optimistic", "read_committed", joint("a", 3, 80_00, 300)),
                () -> run("optimistic", "read_committed", joint("b", 4, 80_00, 300)));

        CheckResult check = check("optimistic", "read_committed");
        assertThat(check.labels()).containsKey("write skew");
        assertThat(check.violations()).anyMatch(v -> v.kind().equals("customer-rule"));
    }

    @Test
    void forcingAVersionIncrementOnTheReadRowClosesTheGap() {
        List<OpResult> results = concurrently(
                () -> run("optimistic-force-increment", "read_committed", joint("a", 3, 80_00, 300)),
                () -> run("optimistic-force-increment", "read_committed", joint("b", 4, 80_00, 300)));

        // One wins; the other's retry sees €20 left and is rejected.
        assertThat(results).extracting(OpResult::outcome).containsExactlyInAnyOrder("applied", "rejected");
        assertThat(check("optimistic-force-increment", "read_committed").clean()).isTrue();
    }

    @Test
    void serializableRetryAbortsAndRetriesTheWriteSkew() {
        List<OpResult> results = concurrently(
                () -> run("serializable-retry", "serializable", joint("a", 3, 80_00, 300)),
                () -> run("serializable-retry", "serializable", joint("b", 4, 80_00, 300)));

        assertThat(results).anyMatch(r -> r.attemptErrors().contains("40001"));
        assertThat(check("serializable-retry", "serializable").clean()).isTrue();
    }

    @Test
    void retryInsideTheTransactionFailsWithCurrentTransactionIsAborted() {
        List<OpResult> results = concurrently(
                () -> run("gotcha-retry-inside", "serializable", withdraw("a", 1, 60_00, 300)),
                () -> run("gotcha-retry-inside", "serializable", withdraw("b", 1, 30_00, 300)));

        assertThat(results).anySatisfy(r -> {
            assertThat(r.attemptErrors()).contains("40001");
            assertThat(r.sqlState()).isEqualTo("25P02");
        });
    }

    // ---- helpers ----

    private OpResult run(String strategy, String isolation, OpRequest request) {
        OpRequest r = new OpRequest(request.opId(), request.op(), strategy, isolation, request.from(), request.to(),
                request.account(), request.customer(), request.amountCents(), request.thinkMs(), request.totalMode(),
                request.instrument());
        return strategies.get(strategy).execute(r);
    }

    private static OpRequest withdraw(String id, long account, long cents, int thinkMs) {
        return new OpRequest(id, "withdraw", null, null, null, null, account, (account + 1) / 2, cents, thinkMs, null, true);
    }

    private static OpRequest joint(String id, long account, long cents, int thinkMs) {
        return new OpRequest(id, "joint_withdraw", null, null, null, null, account, (account + 1) / 2, cents, thinkMs,
                null, true);
    }

    @SafeVarargs
    private static List<OpResult> concurrently(Supplier<OpResult>... ops) {
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<OpResult>> futures = java.util.Arrays.stream(ops)
                    .map(op -> CompletableFuture.supplyAsync(op, pool)).toList();
            return futures.stream().map(CompletableFuture::join).toList();
        }
    }

    private CheckResult check(String strategy, String isolation) {
        ServerHistory server = admin.export();
        History h = new History(new Meta("it", strategy, isolation, 0, Instant.now(), 0, Map.of()), ACCOUNTS,
                server.txns(), server.writes(), server.finalBalances(), List.of());
        return Checker.check(h);
    }
}
