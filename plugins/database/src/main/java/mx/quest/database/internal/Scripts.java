package mx.quest.database.internal;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Migration scripts as text: the {@code index} file, the checksum and the statement splitting. Pure functions.
 *
 * <ul>
 *   <li>The index lists one script per line, {@code V<n>__<name>.sql}, in order; blank lines and {@code #} comments
 *       are skipped. Versions must start at 1 and rise by one, so a missing or reordered script is caught when the
 *       plugin starts using the database, not half-way through a migration.</li>
 *   <li>The checksum is the SHA-256 of the normalized text: BOM stripped, CRLF and CR turned into LF, trailing
 *       whitespace trimmed from every line and from the end. Comments stay in: MariaDB runs {@code /*! … *}{@code /}
 *       comments.</li>
 *   <li>Statements are separated by a line holding only {@value #SEPARATOR}. Each chunk is one
 *       {@code Statement.execute}. A chunk that switches the connection elsewhere ({@code USE},
 *       {@code SET search_path}, {@code SET SESSION}) is refused before anything runs.</li>
 * </ul>
 */
public final class Scripts {

    /** The statement separator line. */
    public static final String SEPARATOR = "-- runway:next";

    /** The largest script read, in bytes. */
    public static final int MAX_SCRIPT_BYTES = 1 << 20;

    private static final Pattern SCRIPT_NAME = Pattern.compile("V([1-9][0-9]{0,8})__([A-Za-z0-9_]{1,100})\\.sql");

    private static final Pattern REFUSED = Pattern.compile(
            "(?is)^(use\\b|set\\s+(session\\b|local\\s+search_path\\b|search_path\\b)|"
                    + "select\\s+set_config\\s*\\(\\s*'search_path')");

    private Scripts() {
    }

    /** One entry of an index. */
    public record Entry(int version, String fileName) {
    }

    /**
     * Parses an index file.
     *
     * @throws IllegalArgumentException on a malformed name, a duplicate, or a version that does not follow
     */
    public static List<Entry> parseIndex(String text) {
        List<Entry> entries = new ArrayList<>();
        int line = 0;
        for (String raw : normalize(text).split("\n", -1)) {
            line++;
            String name = raw.strip();
            if (name.isEmpty() || name.startsWith("#")) {
                continue;
            }
            Matcher m = SCRIPT_NAME.matcher(name);
            if (!m.matches()) {
                throw new IllegalArgumentException("index line " + line + " is not V<n>__<name>.sql: " + name);
            }
            int version = Integer.parseInt(m.group(1));
            int expected = entries.size() + 1;
            if (version != expected) {
                throw new IllegalArgumentException("index line " + line + ": expected V" + expected + ", found "
                        + name);
            }
            entries.add(new Entry(version, name));
        }
        return List.copyOf(entries);
    }

    /** Decodes script bytes as UTF-8 and normalizes them (see the class comment). */
    public static String normalize(byte[] bytes) {
        return normalize(new String(bytes, StandardCharsets.UTF_8));
    }

    /** Normalizes script text (see the class comment). */
    public static String normalize(String text) {
        String s = text;
        if (!s.isEmpty() && s.charAt(0) == '﻿') {
            s = s.substring(1);
        }
        s = s.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder out = new StringBuilder(s.length());
        for (String line : s.split("\n", -1)) {
            out.append(line.stripTrailing()).append('\n');
        }
        return out.toString().stripTrailing();
    }

    /** The checksum of normalized text. */
    public static String checksum(String normalized) {
        return Names.sha256Hex(normalized);
    }

    /**
     * Splits normalized text into statements, skipping chunks that hold only whitespace and comments.
     *
     * @throws IllegalArgumentException when a statement would switch the connection's schema or session
     */
    public static List<String> split(String normalized) {
        List<String> statements = new ArrayList<>();
        StringBuilder chunk = new StringBuilder();
        for (String line : normalized.split("\n", -1)) {
            if (line.strip().equals(SEPARATOR)) {
                add(statements, chunk.toString());
                chunk.setLength(0);
            } else {
                chunk.append(line).append('\n');
            }
        }
        add(statements, chunk.toString());
        return List.copyOf(statements);
    }

    private static void add(List<String> statements, String chunk) {
        String body = chunk.strip();
        String code = withoutLeadingComments(body);
        if (code.isEmpty()) {
            return;
        }
        if (REFUSED.matcher(code).find()) {
            throw new IllegalArgumentException("statement " + (statements.size() + 1) + " changes the connection's "
                    + "schema or session, which a migration may not do: " + firstLine(code));
        }
        statements.add(body);
    }

    /**
     * The text after any leading whitespace, {@code --} line comments and ordinary block comments. A MariaDB
     * executable comment ({@code /*!}) is code, so it ends the stripping and is checked like any statement.
     */
    static String withoutLeadingComments(String text) {
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (text.startsWith("--", i)) {
                int end = text.indexOf('\n', i);
                i = end < 0 ? n : end + 1;
            } else if (text.startsWith("/*", i) && !text.startsWith("/*!", i)) {
                int end = text.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
            } else {
                break;
            }
        }
        String rest = text.substring(i);
        if (rest.startsWith("/*!")) {
            // Check what the executable comment runs: /*!USE x*/ or /*!50000 USE x*/.
            String inner = rest.substring(3).replaceFirst("^[0-9]{5,6}", "");
            return inner.stripLeading();
        }
        return rest;
    }

    private static String firstLine(String code) {
        int nl = code.indexOf('\n');
        String line = nl < 0 ? code : code.substring(0, nl);
        return line.length() > 80 ? line.substring(0, 80) + "…" : line;
    }
}
