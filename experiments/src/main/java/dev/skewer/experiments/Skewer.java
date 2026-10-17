package dev.skewer.experiments;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

import dev.skewer.checker.CheckResult;
import dev.skewer.checker.Checker;
import dev.skewer.checker.Counterexample;
import dev.skewer.checker.TextSummary;
import dev.skewer.checker.history.History;
import dev.skewer.checker.history.HistoryIO;
import dev.skewer.report.ReportGenerator;
import dev.skewer.report.Results.CellResult;
import dev.skewer.report.Results.Controls;
import dev.skewer.report.Results.Matrix;
import dev.skewer.report.Results.SeedRun;
import dev.skewer.workload.RunMetrics;
import dev.skewer.workload.Workload;
import dev.skewer.workload.WorkloadConfig;

/**
 * The Skewer command line.
 *
 * <pre>
 * skewer demo                       the headline: naive-jpa under plain @Transactional loses money
 * skewer run --strategy S --isolation I [--seed N] [--duration-ms D] [--think-ms T] [--total-mode M]
 * skewer matrix [--config experiments/matrix.properties] [--seeds 5] [--quick]
 * skewer controls [--negative-runs N] [--positive-runs N]
 * skewer check &lt;history.jsonl&gt;...     re-analyse saved histories offline
 * skewer report [results.json]      rebuild the HTML report from saved results
 * </pre>
 *
 * The bank service runs embedded unless {@code --url} points at a running one. Postgres must be up
 * ({@code docker compose up -d}).
 */
public final class Skewer {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private Skewer() {
    }

    public static void main(String[] args) throws Exception {
        // Euro signs and arrows survive any console code page.
        System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true,
                java.nio.charset.StandardCharsets.UTF_8));
        if (args.length == 0) {
            usage();
            return;
        }
        String command = args[0];
        List<String> positional = new ArrayList<>();
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].startsWith("--")) {
                String key = args[i].substring(2);
                boolean flag = i + 1 >= args.length || args[i + 1].startsWith("--");
                options.put(key, flag ? "true" : args[++i]);
            } else {
                positional.add(args[i]);
            }
        }
        int exit = switch (command) {
            case "check" -> check(positional);
            case "report" -> report(positional);
            case "demo", "run", "matrix", "controls" -> withService(command, options);
            default -> {
                usage();
                yield 2;
            }
        };
        System.exit(exit);
    }

    private static int withService(String command, Map<String, String> options) throws Exception {
        Path config = Path.of(options.getOrDefault("config", "experiments/matrix.properties"));
        Map<String, String> overrides = new LinkedHashMap<>(options);
        overrides.remove("config");
        overrides.remove("url");
        if (options.containsKey("quick")) {
            overrides.putIfAbsent("seeds", "1");
            overrides.putIfAbsent("duration-ms", "3000");
            overrides.putIfAbsent("max-ops", "2000");
            overrides.putIfAbsent("baseline", "false");
            overrides.putIfAbsent("negative-runs", "6");
            overrides.putIfAbsent("positive-runs", "3");
        }
        Settings settings = Settings.load(config, overrides);

        EmbeddedBank embedded = null;
        URI url;
        if (options.containsKey("url")) {
            url = URI.create(options.get("url"));
        } else {
            System.out.println("starting the bank service (embedded) against " + settings.db());
            embedded = EmbeddedBank.start(settings.db());
            url = embedded.url();
        }
        try {
            Workload workload = new Workload(url);
            if (!workload.client().isUp()) {
                System.err.println("bank service is not answering at " + url);
                return 2;
            }
            Runner runner = new Runner(workload);
            return switch (command) {
                case "demo" -> demo(runner, options, settings);
                case "run" -> run(runner, options, settings);
                case "matrix" -> matrix(runner, settings, options.get("only"));
                default -> {
                    Controls c = controls(runner, settings);
                    yield c.negativeFalsePositives() == 0 ? 0 : 1;
                }
            };
        } finally {
            if (embedded != null) {
                embedded.close();
            }
        }
    }

    /** The headline: plain @Transactional, naive JPA code, Postgres's default isolation. */
    private static int demo(Runner runner, Map<String, String> options, Settings settings) {
        long seed = Long.parseLong(options.getOrDefault("seed", String.valueOf(new Random().nextInt(100_000))));
        WorkloadConfig config = WorkloadConfig.defaults()
                .withRunLength(Long.parseLong(options.getOrDefault("duration-ms", "10000")), 100_000)
                .withThinkMaxMs(Integer.parseInt(options.getOrDefault("think-ms", "20")));
        System.out.printf(Locale.ROOT, "%nSkewer demo: naive-jpa under plain @Transactional (Isolation.DEFAULT), seed %d%n", seed);
        System.out.println("32 clients hammer 20 accounts with transfers and withdrawals for up to 10 seconds...");
        String runId = "demo-" + seed;
        SeedRun run = runner.run(runId, "naive-jpa", "default", seed, config,
                Path.of("experiments/histories"), true);
        CheckResult c = run.check();
        System.out.println();
        System.out.println(TextSummary.render(c));
        System.out.println(Runner.line(run));
        System.out.println();
        Counterexample lost = c.counterexamples().stream()
                .filter(cx -> "lost update".equals(cx.label())).findFirst().orElse(null);
        Path page = settings.output().resolve("demo.html");
        ReportGenerator.writeDemo(run, page);
        if (lost != null) {
            System.out.println("DOUBLE-SPEND REPRODUCED: " + lost.explanation());
        } else if (!c.clean()) {
            System.out.println("Anomalies found, but no lost update this time; rerun or raise --think-ms.");
        } else {
            System.out.println("No anomaly this run. Contention too low? Try --think-ms 30.");
        }
        System.out.println("history: " + run.history());
        System.out.println("page:    " + page.toAbsolutePath());
        return lost != null ? 0 : 1;
    }

    private static int run(Runner runner, Map<String, String> options, Settings settings) {
        String strategy = options.getOrDefault("strategy", "naive-jpa");
        String isolation = options.getOrDefault("isolation", "read_committed");
        long seed = Long.parseLong(options.getOrDefault("seed", String.valueOf(new Random().nextInt(100_000))));
        WorkloadConfig config = WorkloadConfig.defaults()
                .withRunLength(settings.durationMs(), settings.maxOps())
                .withThinkMaxMs(Integer.parseInt(options.getOrDefault("think-ms", "20")))
                .withTotalMode(options.get("total-mode"));
        if (options.containsKey("concurrency")) {
            config = config.withConcurrency(Integer.parseInt(options.get("concurrency")));
        }
        if (options.containsKey("skew")) {
            config = config.withSkew(Double.parseDouble(options.get("skew")));
        }
        SeedRun run = runner.run(strategy + "@" + isolation + "-" + seed, strategy, isolation, seed, config,
                Path.of("experiments/histories"), true);
        System.out.println(TextSummary.render(run.check()));
        System.out.println(Runner.line(run));
        System.out.println("history: " + run.history());
        return run.check().clean() ? 0 : 1;
    }

    /**
     * Runs every cell, or with {@code only} (comma-separated cell ids) reruns just those cells with the same seeds
     * and merges them into the existing results, keeping everything else.
     */
    private static int matrix(Runner runner, Settings settings, String only) throws Exception {
        Instant started = Instant.now();
        String matrixId = STAMP.format(started);
        Path historyDir = Path.of("experiments/histories", matrixId);
        WorkloadConfig base = WorkloadConfig.defaults().withRunLength(settings.durationMs(), settings.maxOps());
        List<Experiments.Spec> specs = Experiments.all(base);
        Path json = settings.output().resolve("matrix.json");
        Matrix previous = null;
        if (only != null) {
            List<String> ids = List.of(only.split(","));
            specs = specs.stream().filter(s -> ids.contains(s.cell().id())).toList();
            previous = HistoryIO.JSON.readValue(Files.readString(json), Matrix.class);
            System.out.println("rerunning " + specs.stream().map(s -> s.cell().id()).toList() + " into " + json);
        }
        System.out.printf(Locale.ROOT, "matrix %s: %d cells x %d seeds, %d ms or %d ops per run, base seed %d%n",
                matrixId, specs.size(), settings.seeds(), settings.durationMs(), settings.maxOps(), settings.baseSeed());

        // Warm the JIT on both the JPA and the JDBC paths first, so the first cell's numbers are not a cold start.
        System.out.println("warming up (results discarded)...");
        for (String s : List.of("naive-jpa", "pessimistic", "atomic-update", "optimistic")) {
            runner.run("warmup-" + s, s, "read_committed", 1, base.withRunLength(3_000, 3_000),
                    Path.of("experiments/histories", "warmup"), false);
        }

        List<CellResult> cells = new ArrayList<>();
        for (Experiments.Spec spec : specs) {
            System.out.printf(Locale.ROOT, "%n== %s  [%s]%n   hypothesis: %s%n", spec.cell().id(), spec.cell().group(),
                    spec.cell().hypothesis());
            List<SeedRun> runs = new ArrayList<>();
            for (int i = 0; i < settings.seeds(); i++) {
                long seed = settings.baseSeed() + i;
                SeedRun run = runner.run(spec.cell().id() + "-" + seed, spec.cell().strategy(), spec.cell().isolation(),
                        seed, spec.config(), historyDir, false);
                System.out.println("   " + Runner.line(run));
                runs.add(run);
            }
            RunMetrics baseline = null;
            if (settings.baseline()) {
                baseline = runner.baseline(spec.cell().strategy(), spec.cell().isolation(), settings.baseSeed(),
                        spec.config().withRunLength(settings.baselineDurationMs(), settings.maxOps()));
                System.out.printf(Locale.ROOT, "   baseline without instrumentation: %.0f ops/s, p99 %.1f ms%n",
                        baseline.throughput(), baseline.p99Ms());
            }
            cells.add(new CellResult(spec.cell(), runs, baseline));
        }

        Matrix matrix;
        if (previous != null) {
            Map<String, CellResult> rerun = new LinkedHashMap<>();
            cells.forEach(c -> rerun.put(c.cell().id(), c));
            List<CellResult> merged = previous.cells().stream()
                    .map(c -> rerun.getOrDefault(c.cell().id(), c)).toList();
            matrix = new Matrix(previous.runId(), previous.startedAt(), previous.elapsedMs(), previous.environment(),
                    merged, previous.controls());
        } else {
            Controls controls = controls(runner, settings);
            matrix = new Matrix(matrixId, started, java.time.Duration.between(started, Instant.now()).toMillis(),
                    Runner.environment(), cells, controls);
        }
        Files.createDirectories(settings.output());
        Files.writeString(json, HistoryIO.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(matrix));
        Path html = Path.of("docs/report/index.html");
        ReportGenerator.write(matrix, html);
        System.out.println();
        System.out.println("results: " + json.toAbsolutePath());
        System.out.println("report:  " + html.toAbsolutePath());
        return matrix.controls() == null || matrix.controls().negativeFalsePositives() == 0 ? 0 : 1;
    }

    /**
     * Negative control: every strategy at Serializable must stay clean. Positive control: naive-jpa at Read
     * Committed with think time must be caught; if it is not, a clean result anywhere else means nothing.
     */
    private static Controls controls(Runner runner, Settings settings) {
        String[] strategies = {"naive-jpa", "atomic-update", "pessimistic", "pessimistic-unordered", "optimistic",
                "serializable-retry"};
        WorkloadConfig shortRun = WorkloadConfig.defaults().withRunLength(2_000, 1_000);
        Path dir = Path.of("experiments/histories", "controls");
        int falsePositives = 0;
        System.out.printf(Locale.ROOT, "%n== negative control: %d runs at Serializable, every strategy%n", settings.negativeRuns());
        for (int i = 0; i < settings.negativeRuns(); i++) {
            String strategy = strategies[i % strategies.length];
            long seed = settings.baseSeed() + 10_000 + i;
            SeedRun run = runner.run("negative-" + strategy + "-" + seed, strategy, "serializable", seed, shortRun, dir, false);
            if (!run.check().clean()) {
                falsePositives++;
                System.out.println("   FALSE POSITIVE " + strategy + " " + Runner.line(run));
            }
        }
        System.out.printf(Locale.ROOT, "   %d/%d clean%n", settings.negativeRuns() - falsePositives, settings.negativeRuns());

        WorkloadConfig positive = WorkloadConfig.defaults().withRunLength(settings.controlDurationMs(), 1_000_000);
        int caught = 0;
        List<Double> times = new ArrayList<>();
        System.out.printf(Locale.ROOT, "== positive control: %d runs of naive-jpa at Read Committed, %d s each%n",
                settings.positiveRuns(), settings.controlDurationMs() / 1000);
        for (int i = 0; i < settings.positiveRuns(); i++) {
            long seed = settings.baseSeed() + 20_000 + i;
            Instant start = Instant.now();
            SeedRun run = runner.run("positive-" + seed, "naive-jpa", "read_committed", seed, positive, dir, false);
            if (!run.check().clean()) {
                caught++;
                Double t = Runner.secondsToFailure(run.check(), start);
                if (t != null) {
                    times.add(t);
                }
            }
        }
        times.sort(Double::compare);
        double median = times.isEmpty() ? Double.NaN : times.get(times.size() / 2);
        System.out.printf(Locale.ROOT, "   %d/%d caught, median first counterexample after %.2f s%n", caught,
                settings.positiveRuns(), median);
        return new Controls(settings.negativeRuns(), falsePositives, settings.positiveRuns(), caught, median,
                "negative: 2 s / 1,000 ops per run; positive: think time 0-20 ms, " + settings.controlDurationMs() / 1000 + " s per run");
    }

    private static int check(List<String> files) {
        boolean clean = true;
        for (String f : files) {
            History h = HistoryIO.read(Path.of(f));
            CheckResult r = Checker.check(h);
            System.out.println(TextSummary.render(r));
            clean &= r.clean();
        }
        return clean ? 0 : 1;
    }

    private static int report(List<String> args) throws Exception {
        Path json = Path.of(args.isEmpty() ? "experiments/results/matrix.json" : args.getFirst());
        Matrix matrix = HistoryIO.JSON.readValue(Files.readString(json), Matrix.class);
        Path html = Path.of("docs/report/index.html");
        ReportGenerator.write(matrix, html);
        System.out.println("report: " + html.toAbsolutePath());
        return 0;
    }

    private static void usage() {
        System.out.println("""
                usage: skewer <command> [options]
                  demo      reproduce the headline double-spend (naive-jpa, plain @Transactional)
                  run       one run: --strategy S --isolation I [--seed N] [--duration-ms D] [--think-ms T]
                  matrix    the full strategy x isolation matrix, then the HTML report [--quick] [--seeds N]
                  controls  end-to-end negative and positive controls
                  check     re-analyse saved histories: skewer check <history.jsonl>...
                  report    rebuild docs/report/index.html from experiments/results/matrix.json
                common: --url http://host:port (use a running service), --db jdbc:postgresql://...""");
    }
}
