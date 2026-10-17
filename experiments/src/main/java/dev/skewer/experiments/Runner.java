package dev.skewer.experiments;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import dev.skewer.checker.CheckResult;
import dev.skewer.checker.Checker;
import dev.skewer.checker.Counterexample;
import dev.skewer.checker.Money;
import dev.skewer.checker.Violation;
import dev.skewer.checker.history.HistoryIO;
import dev.skewer.report.Results.SeedRun;
import dev.skewer.workload.RunMetrics;
import dev.skewer.workload.Workload;
import dev.skewer.workload.Workload.RunResult;
import dev.skewer.workload.WorkloadConfig;

/** One run: drive the workload, check the history, keep the history if it failed. */
public final class Runner {

    private final Workload workload;

    public Runner(Workload workload) {
        this.workload = workload;
    }

    public SeedRun run(String runId, String strategy, String isolation, long seed, WorkloadConfig config,
                       Path historyDir, boolean keepAlways) {
        RunResult result = workload.run(runId, strategy, isolation, seed, config);
        CheckResult check = Checker.check(result.history());
        String saved = null;
        if (keepAlways || !check.clean()) {
            Path file = historyDir.resolve(runId.replace('@', '_') + ".jsonl");
            HistoryIO.write(result.history(), file);
            saved = file.toString().replace('\\', '/');
        }
        return new SeedRun(seed, result.metrics(), check, saved);
    }

    public RunMetrics baseline(String strategy, String isolation, long seed, WorkloadConfig config) {
        return workload.run("baseline", strategy, isolation, seed, config.withInstrument(false)).metrics();
    }

    /** One line per run for the terminal. */
    public static String line(SeedRun run) {
        CheckResult c = run.check();
        RunMetrics m = run.metrics();
        String verdict;
        if (c.clean()) {
            verdict = "clean";
        } else {
            String cycles = c.anomalies().entrySet().stream().filter(e -> e.getValue() > 0)
                    .map(e -> e.getValue() + " " + e.getKey()).collect(Collectors.joining(", "));
            String broken = c.violations().stream().map(Runner::shortViolation).collect(Collectors.joining(", "));
            verdict = "FAIL " + String.join("; ", java.util.stream.Stream.of(cycles, broken).filter(s -> !s.isEmpty()).toList());
        }
        return String.format(Locale.ROOT, "seed %-6d %-70s | %6.0f ops/s  p99 %6.1f ms  aborts %4.1f%%  deadlocks %d%s",
                run.seed(), verdict, m.throughput(), m.p99Ms(), 100 * m.abortRate(), m.deadlocks(),
                m.failures().isEmpty() ? "" : "  failed " + m.failures());
    }

    private static String shortViolation(Violation v) {
        return switch (v.kind()) {
            case "conservation" -> (v.cents() > 0 ? "+" : "") + Money.format(v.cents()) + " conservation";
            default -> v.kind();
        };
    }

    /** Seconds from the run's start until the first counterexample's last transaction committed. */
    public static Double secondsToFailure(CheckResult check, Instant startedAt) {
        Instant first = null;
        for (Counterexample cx : check.counterexamples()) {
            Instant done = cx.txns().stream().map(Counterexample.CxTxn::committedAt)
                    .filter(java.util.Objects::nonNull).max(Instant::compareTo).orElse(null);
            if (done != null && (first == null || done.isBefore(first))) {
                first = done;
            }
        }
        return first == null ? null : Duration.between(startedAt, first).toMillis() / 1000.0;
    }

    public static Map<String, String> environment() {
        return Map.of(
                "java", System.getProperty("java.version"),
                "os", System.getProperty("os.name") + " " + System.getProperty("os.arch"),
                "cpus", String.valueOf(Runtime.getRuntime().availableProcessors()),
                "spring-boot", org.springframework.boot.SpringBootVersion.getVersion());
    }
}
