package dev.skewer.checker.history;

import java.time.Instant;
import java.util.Map;

/** Run metadata: what was run, how, and when. {@code config} holds the workload knobs as strings. */
public record Meta(
        String runId,
        String strategy,
        String requestedIsolation,
        long seed,
        Instant startedAt,
        long durationMicros,
        Map<String, String> config) {

    public Meta {
        config = Map.copyOf(config);
    }
}
