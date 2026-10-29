package mx.quest.database.internal;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A site's database backup as read back: the manifest, each table's index and the parts, all checked before
 * anything live is touched. <b>The archive is untrusted</b> (a site's administrator can upload one), so every name is
 * checked against a strict grammar, every part against its recorded SHA-256 and row count, every line against a
 * length cap, and the whole against the backup budget; nothing in it ever becomes an identifier — the restore looks
 * names up in the new generation's own catalog.
 */
final class Archive {

    static final String STATE = "state.json";
    static final String MANIFEST = "manifest.json";
    static final int FORMAT = 1;
    static final int LINE_MAX = 16 * 1024 * 1024;
    static final int SMALL_ENTRY_MAX = 4 * 1024 * 1024;

    private static final Pattern NAME = Pattern.compile("[a-z0-9_]{1,64}");
    private static final Pattern PLUGIN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,254}");
    private static final Pattern PART = Pattern.compile("part-[0-9]{5}\\.jsonl\\.gz");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Where the entries come from: the backup being restored, or data parked in the site folder. */
    interface Entries {
        boolean has(String entry) throws IOException;

        byte[] read(String entry) throws IOException;
    }

    record Column(String name, String type) {
    }

    record Part(String entry, long rows, String sha256) {
    }

    record Table(String name, String plugin, List<Column> columns, long rows, List<Part> parts) {
    }

    record Sequence(String name, String plugin, long next, long increment) {
    }

    final String dialect;
    final Map<String, Integer> plugins;
    final List<Table> tables;
    final List<Sequence> sequences;

    private Archive(String dialect, Map<String, Integer> plugins, List<Table> tables, List<Sequence> sequences) {
        this.dialect = dialect;
        this.plugins = plugins;
        this.tables = tables;
        this.sequences = sequences;
    }

    /** The tables of one plugin. */
    List<Table> tables(String pluginId) {
        return tables.stream().filter(t -> t.plugin().equals(pluginId)).toList();
    }

    /** The sequences of one plugin. */
    List<Sequence> sequences(String pluginId) {
        return sequences.stream().filter(s -> s.plugin().equals(pluginId)).toList();
    }

    /** The state entry: {@code none}, {@code present}, or empty when the backup has none. */
    static Optional<String> state(Entries in) throws IOException {
        if (!in.has(STATE)) {
            return Optional.empty();
        }
        JsonNode node = parse(in.read(STATE), STATE);
        JsonNode schema = node.get("schema");
        if (schema == null || !schema.isString()) {
            throw new IOException("the database state entry is malformed");
        }
        return Optional.of(schema.stringValue());
    }

    /**
     * Reads and checks the whole archive: names, counts, checksums, line lengths, budgets, and on PostgreSQL text
     * holding U+0000, which it cannot store.
     */
    static Archive read(Entries in, long budget, boolean postgres) throws IOException {
        if (!in.has(MANIFEST)) {
            throw new IOException("the database backup has no manifest");
        }
        JsonNode manifest = parse(in.read(MANIFEST), MANIFEST);
        if (manifest.get("format") == null || manifest.get("format").asLong() != FORMAT) {
            throw new IOException("the database backup's format is not one this plugin reads");
        }
        String dialect = text(manifest, "dialect");
        Map<String, Integer> plugins = new LinkedHashMap<>();
        JsonNode pluginNode = manifest.get("plugins");
        if (pluginNode == null || !pluginNode.isObject()) {
            throw new IOException("the database backup's manifest lists no plugins");
        }
        for (Map.Entry<String, JsonNode> e : pluginNode.properties()) {
            requireMatch(PLUGIN, e.getKey(), "plugin id");
            JsonNode v = e.getValue().get("version");
            if (v == null || !v.isIntegralNumber() || v.asLong() < 0 || v.asLong() > 1_000_000) {
                throw new IOException("the version of plugin " + e.getKey() + " is malformed");
            }
            plugins.put(e.getKey(), (int) v.asLong());
        }
        long[] spent = {0};
        long decompressedBudget = Math.max(budget, 1) * 20;
        long[] decompressed = {0};
        List<Table> tables = new ArrayList<>();
        JsonNode tableList = manifest.get("tables");
        if (tableList == null || !tableList.isArray()) {
            throw new IOException("the database backup's manifest lists no tables");
        }
        for (JsonNode t : tableList) {
            String name = requireMatch(NAME, text(t, "name"), "table name");
            String plugin = requireMatch(PLUGIN, text(t, "plugin"), "plugin id");
            if (!plugins.containsKey(plugin)) {
                throw new IOException("table " + name + " belongs to a plugin the manifest does not list");
            }
            tables.add(readTable(in, name, plugin, budget, spent, decompressedBudget, decompressed, postgres));
        }
        List<Sequence> sequences = new ArrayList<>();
        JsonNode seqList = manifest.get("sequences");
        if (seqList != null && seqList.isArray()) {
            for (JsonNode s : seqList) {
                String plugin = requireMatch(PLUGIN, text(s, "plugin"), "plugin id");
                sequences.add(new Sequence(requireMatch(NAME, text(s, "name"), "sequence name"), plugin,
                        Long.parseLong(text(s, "next")), Long.parseLong(text(s, "increment"))));
            }
        }
        return new Archive(dialect, Map.copyOf(plugins), List.copyOf(tables), List.copyOf(sequences));
    }

    private static Table readTable(Entries in, String name, String plugin, long budget, long[] spent,
                                   long decompressedBudget, long[] decompressed, boolean postgres)
            throws IOException {
        String folder = "tables/" + name + "/";
        JsonNode index = parse(in.read(folder + "index.json"), folder + "index.json");
        List<Column> columns = columns(index.get("columns"), name);
        List<Part> parts = new ArrayList<>();
        long total = 0;
        JsonNode partList = index.get("parts");
        if (partList == null || !partList.isArray()) {
            throw new IOException("table " + name + " lists no parts");
        }
        for (JsonNode p : partList) {
            String file = requireMatch(PART, text(p, "name"), "part name");
            long rows = p.get("rows") == null ? -1 : p.get("rows").asLong();
            String sha = text(p, "sha256");
            byte[] bytes = in.read(folder + file);
            spent[0] += bytes.length;
            if (spent[0] > budget) {
                throw new IOException("the database backup is larger than the backup budget");
            }
            if (!Names.sha256Hex(bytes).equals(sha)) {
                throw new IOException("part " + file + " of table " + name + " does not match its checksum");
            }
            long counted = forEachRow(bytes, name, columns, decompressedBudget, decompressed, row -> {
                if (postgres) {
                    for (Object v : row) {
                        if (v instanceof String s && s.indexOf('\0') >= 0) {
                            throw new IOException("table " + name + " holds text with a NUL character, which "
                                    + "PostgreSQL cannot store");
                        }
                    }
                }
            });
            if (counted != rows) {
                throw new IOException("part " + file + " of table " + name + " holds " + counted + " rows, not "
                        + rows);
            }
            total += rows;
            parts.add(new Part(folder + file, rows, sha));
        }
        if (index.get("rows") == null || index.get("rows").asLong() != total) {
            throw new IOException("table " + name + " row count does not add up");
        }
        return new Table(name, plugin, columns, total, List.copyOf(parts));
    }

    private static List<Column> columns(JsonNode node, String table) throws IOException {
        if (node == null || !node.isArray() || node.isEmpty()) {
            throw new IOException("table " + table + " has no column list");
        }
        List<Column> columns = new ArrayList<>();
        for (JsonNode c : node) {
            String type = text(c, "type");
            if (type.length() > 64 || !type.matches("[A-Z]+(\\([0-9]{1,5}(,[0-9]{1,5})?\\))?")) {
                throw new IOException("table " + table + " has a malformed column type");
            }
            columns.add(new Column(requireMatch(NAME, text(c, "name"), "column name"), type));
        }
        return List.copyOf(columns);
    }

    /** Receives one decoded row. */
    @FunctionalInterface
    interface RowSink {
        void row(Object[] values) throws IOException;
    }

    /** Decodes every row of a part, checking the header against the table's columns; returns the row count. */
    static long forEachRow(byte[] part, String table, List<Column> columns, long decompressedBudget,
                           long[] decompressed, RowSink sink) throws IOException {
        long rows = 0;
        try (InputStream in = new BufferedInputStream(new GZIPInputStream(new ByteArrayInputStream(part)))) {
            byte[] headerLine = readLine(in, decompressed, decompressedBudget);
            if (headerLine == null) {
                throw new IOException("a part of table " + table + " is empty");
            }
            List<Column> header = columns(parse(headerLine, "part header").get("columns"), table);
            if (!header.equals(columns)) {
                throw new IOException("a part of table " + table + " has a header unlike the table's");
            }
            byte[] line;
            while ((line = readLine(in, decompressed, decompressedBudget)) != null) {
                JsonNode row = parse(line, "row");
                if (!row.isArray() || row.size() != columns.size()) {
                    throw new IOException("a row of table " + table + " has the wrong number of values");
                }
                Object[] values = new Object[columns.size()];
                for (int i = 0; i < values.length; i++) {
                    values[i] = value(row.get(i));
                }
                sink.row(values);
                rows++;
            }
        }
        return rows;
    }

    private static Object value(JsonNode n) throws IOException {
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isString()) {
            return n.stringValue();
        }
        if (n.isBoolean()) {
            return n.booleanValue();
        }
        if (n.isIntegralNumber()) {
            BigInteger big = n.bigIntegerValue();
            return big.bitLength() < 64 ? (Object) big.longValue() : big;
        }
        if (n.isNumber()) {
            return n.doubleValue();
        }
        throw new IOException("a value in the backup is not a scalar");
    }

    private static byte[] readLine(InputStream in, long[] decompressed, long budget) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        boolean any = false;
        while ((b = in.read()) >= 0) {
            any = true;
            if (++decompressed[0] > budget) {
                throw new IOException("the database backup expands beyond its budget");
            }
            if (b == '\n') {
                return line.toByteArray();
            }
            if (line.size() >= LINE_MAX) {
                throw new IOException("a line in the database backup is longer than " + LINE_MAX + " bytes");
            }
            line.write(b);
        }
        return any && line.size() > 0 ? line.toByteArray() : null;
    }

    static JsonNode parse(byte[] bytes, String what) throws IOException {
        if (bytes.length > LINE_MAX) {
            throw new IOException(what + " is too large");
        }
        try {
            return JSON.readTree(bytes);
        } catch (JacksonException e) {
            throw new IOException(what + " is not valid JSON");
        }
    }

    private static String text(JsonNode node, String field) throws IOException {
        JsonNode v = node == null ? null : node.get(field);
        if (v == null || !v.isString()) {
            throw new IOException("the database backup is missing '" + field + "'");
        }
        return v.stringValue();
    }

    private static String requireMatch(Pattern p, String value, String what) throws IOException {
        if (!p.matcher(value).matches()) {
            throw new IOException("the database backup holds a malformed " + what);
        }
        return value;
    }

    /** The mapper the participant writes with. */
    static JsonMapper json() {
        return JSON;
    }
}
