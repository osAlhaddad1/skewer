package dev.skewer.checker;

import java.nio.file.Files;
import java.nio.file.Path;

import dev.skewer.checker.history.History;
import dev.skewer.checker.history.HistoryIO;

/**
 * Re-analyses saved histories offline: {@code checker <history.jsonl>... [--json <out.json>]}.
 * Exit code 0 when every history is clean, 1 otherwise.
 */
public final class CheckerMain {

    private CheckerMain() {
    }

    public static void main(String[] args) throws Exception {
        System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true,
                java.nio.charset.StandardCharsets.UTF_8));
        if (args.length == 0) {
            System.err.println("usage: checker <history.jsonl>... [--json <out.json>]");
            System.exit(2);
        }
        Path json = null;
        boolean allClean = true;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--json")) {
                json = Path.of(args[++i]);
                continue;
            }
            History history = HistoryIO.read(Path.of(args[i]));
            CheckResult result = Checker.check(history);
            System.out.println(TextSummary.render(result));
            allClean &= result.clean();
            if (json != null) {
                Files.writeString(json, HistoryIO.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result));
            }
        }
        System.exit(allClean ? 0 : 1);
    }
}
