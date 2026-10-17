package dev.skewer.experiments;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import dev.skewer.checker.CheckResult;
import dev.skewer.checker.Checker;
import dev.skewer.checker.history.HistoryIO;
import dev.skewer.report.Results.SeedRun;
import dev.skewer.workload.Workload;
import dev.skewer.workload.WorkloadConfig;

/** The whole pipeline, as the CLI runs it: embedded service, workload over HTTP, history, checker. */
@Testcontainers
class EndToEndIT {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18")
            .withDatabaseName("skewer").withUsername("skewer").withPassword("skewer");

    static EmbeddedBank bank;
    static Runner runner;

    @TempDir
    Path histories;

    @BeforeAll
    static void start() {
        bank = EmbeddedBank.start(postgres.getJdbcUrl());
        runner = new Runner(new Workload(bank.url()));
    }

    @AfterAll
    static void stop() {
        bank.close();
    }

    @Test
    void positiveControlNaiveJpaAtReadCommittedIsCaught() {
        SeedRun run = runner.run("positive", "naive-jpa", "read_committed", 1, short3s(), histories, true);

        assertThat(run.check().clean()).isFalse();
        assertThat(run.check().labels()).containsKey("lost update");
        assertThat(run.check().suspicious()).isFalse();
    }

    @Test
    void negativeControlSerializableRetryIsClean() {
        SeedRun run = runner.run("negative", "serializable-retry", "serializable", 2, short3s(), histories, true);

        assertThat(run.check().clean()).as(run.check().toString()).isTrue();
        assertThat(run.check().instrumentationIssues()).isEmpty();
        assertThat(run.check().observedIsolation()).containsOnlyKeys("serializable");
    }

    @Test
    void savedHistoryReanalysesOfflineWithTheSameResult() {
        SeedRun run = runner.run("offline", "naive-jpa", "read_committed", 3, short3s(), histories, true);

        CheckResult again = Checker.check(HistoryIO.read(Path.of(run.history())));

        assertThat(again.anomalies()).isEqualTo(run.check().anomalies());
        assertThat(again.violations()).isEqualTo(run.check().violations());
    }

    private static WorkloadConfig short3s() {
        return WorkloadConfig.defaults().withRunLength(3_000, 3_000);
    }
}
