package dev.skewer.checker.history;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads and writes histories as JSON lines, one record per line, each tagged with a {@code type}: meta, account,
 * txn, write, final, client. Line-oriented so a failing run's history can be grepped and diffed by hand.
 */
public final class HistoryIO {

    public static final ObjectMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private HistoryIO() {
    }

    public static void write(History history, Path file) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (BufferedWriter out = Files.newBufferedWriter(file)) {
                line(out, "meta", history.meta());
                for (Account a : history.accounts()) {
                    line(out, "account", a);
                }
                for (Txn t : history.txns()) {
                    line(out, "txn", t);
                }
                for (Write w : history.writes()) {
                    line(out, "write", w);
                }
                for (Map.Entry<Long, Long> e : history.finalBalances().entrySet()) {
                    line(out, "final", Map.of("account", e.getKey(), "balance", e.getValue()));
                }
                for (ClientOp c : history.clientOps()) {
                    line(out, "client", c);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static History read(Path file) {
        Meta meta = null;
        List<Account> accounts = new ArrayList<>();
        List<Txn> txns = new ArrayList<>();
        List<Write> writes = new ArrayList<>();
        Map<Long, Long> finals = new LinkedHashMap<>();
        List<ClientOp> clients = new ArrayList<>();
        try (BufferedReader in = Files.newBufferedReader(file)) {
            String text;
            int lineNo = 0;
            while ((text = in.readLine()) != null) {
                lineNo++;
                if (text.isBlank()) {
                    continue;
                }
                ObjectNode node = (ObjectNode) JSON.readTree(text);
                String type = node.path("type").asString();
                node.remove("type");
                switch (type) {
                    case "meta" -> meta = JSON.treeToValue(node, Meta.class);
                    case "account" -> accounts.add(JSON.treeToValue(node, Account.class));
                    case "txn" -> txns.add(JSON.treeToValue(node, Txn.class));
                    case "write" -> writes.add(JSON.treeToValue(node, Write.class));
                    case "final" -> finals.put(node.get("account").asLong(), node.get("balance").asLong());
                    case "client" -> clients.add(JSON.treeToValue(node, ClientOp.class));
                    default -> throw new IllegalArgumentException(file + ":" + lineNo + ": unknown record type '" + type + "'");
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (meta == null) {
            throw new IllegalArgumentException(file + ": no meta record");
        }
        return new History(meta, accounts, txns, writes, finals, clients);
    }

    private static void line(BufferedWriter out, String type, Object value) throws IOException {
        ObjectNode node = JSON.createObjectNode();
        node.put("type", type);
        JsonNode body = JSON.valueToTree(value);
        node.setAll((ObjectNode) body);
        out.write(JSON.writeValueAsString(node));
        out.newLine();
    }
}
