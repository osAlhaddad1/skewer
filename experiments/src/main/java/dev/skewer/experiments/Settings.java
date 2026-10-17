package dev.skewer.experiments;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

/**
 * Experiment settings, from {@code experiments/matrix.properties} with command-line overrides
 * ({@code --seeds 5 --duration-ms 8000 ...}).
 */
public record Settings(
        String db,
        int seeds,
        long baseSeed,
        long durationMs,
        int maxOps,
        boolean baseline,
        long baselineDurationMs,
        int negativeRuns,
        int positiveRuns,
        long controlDurationMs,
        Path output) {

    public static Settings load(Path file, Map<String, String> overrides) {
        Properties p = new Properties();
        if (file != null && Files.exists(file)) {
            try (Reader r = Files.newBufferedReader(file)) {
                p.load(r);
            } catch (IOException e) {
                throw new IllegalStateException("cannot read " + file, e);
            }
        }
        overrides.forEach((k, v) -> p.setProperty(camel(k), v));
        return new Settings(
                p.getProperty("db", "jdbc:postgresql://localhost:55432/skewer"),
                Integer.parseInt(p.getProperty("seeds", "5")),
                Long.parseLong(p.getProperty("baseSeed", String.valueOf(System.currentTimeMillis() % 100_000))),
                Long.parseLong(p.getProperty("durationMs", "8000")),
                Integer.parseInt(p.getProperty("maxOps", "6000")),
                Boolean.parseBoolean(p.getProperty("baseline", "true")),
                Long.parseLong(p.getProperty("baselineDurationMs", "5000")),
                Integer.parseInt(p.getProperty("negativeRuns", "30")),
                Integer.parseInt(p.getProperty("positiveRuns", "20")),
                Long.parseLong(p.getProperty("controlDurationMs", "10000")),
                Path.of(p.getProperty("output", "experiments/results")));
    }

    /** {@code duration-ms} to {@code durationMs}. */
    private static String camel(String key) {
        StringBuilder sb = new StringBuilder();
        boolean upper = false;
        for (char c : key.toCharArray()) {
            if (c == '-') {
                upper = true;
            } else {
                sb.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return sb.toString();
    }
}
