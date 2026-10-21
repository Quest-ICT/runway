package mx.quest.discover;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Parses the per-tenant {@code content.schema} text (a small YAML document keyed by URL-path pattern) and
 * resolves the entry for a given page path. Parsing is memoized by the raw text, so it happens once per distinct
 * config value (which changes only when the tenant edits it) rather than per request.
 *
 * <p>Format — a map of path pattern → entry. An entry is either <b>one type-block</b> (a map with a {@code type}
 * plus type-specific fields) or a <b>list of type-blocks</b> when a single page carries several types (e.g. a
 * pricing page that is both an {@code FAQPage} and a set of {@code Offer}s):
 * <pre>
 * /coffee-*:
 *   type: Product
 *   currency: USD
 * /pricing:
 *   - type: FAQPage
 *     faq: [ { q: "Why ranges?", a: "Scope drives cost…" } ]
 *   - type: Service
 *     offers: [ { name: "Advisory Sprint", price: "18000", currency: USD } ]
 * </pre>
 * A pattern ending in {@code *} is a prefix match; an <b>exact</b> path always wins, and among prefix patterns
 * the <b>longest</b> wins. A malformed document disables typing (returns no entries) rather than breaking the page.
 */
final class PageSchemaConfig {

    private static final YAMLMapper YAML = YAMLMapper.builder().build();
    private static final int MAX_CACHE = 128;
    // Memo keyed by the raw config text — self-evicting (a changed value is a new key); bounded as a backstop.
    // Value: pattern → entry, where an entry is a Map (one type-block) or a List of Maps (several).
    private static final Map<String, Map<String, Object>> CACHE = new ConcurrentHashMap<>();

    private PageSchemaConfig() {
    }

    /** Parses {@code content.schema} into {@code pattern → entry}; empty for blank/malformed input. */
    static Map<String, Object> parse(String text) {
        if (text == null || text.isBlank()) {
            return Map.of();
        }
        Map<String, Object> cached = CACHE.get(text);
        if (cached != null) {
            return cached;
        }
        Map<String, Object> parsed = doParse(text);
        if (CACHE.size() > MAX_CACHE) {
            CACHE.clear();
        }
        CACHE.put(text, parsed);
        return parsed;
    }

    /**
     * Why {@code text} cannot be used at all, or {@code null} when it parses (blank included): the YAML error, or a
     * root that is not a map of patterns. {@link #parse} turns either into "no types" so a page never breaks; this
     * tells the site manager instead.
     */
    static String syntaxProblem(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            Object root = YAML.readValue(text, Object.class);
            return root instanceof Map<?, ?> ? null : "the text is not a list of 'pattern: type' entries";
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            int newline = message.indexOf('\n');
            return newline > 0 ? message.substring(0, newline) : message;
        }
    }

    /** The raw entries whose value is neither one block nor a list of blocks: they are ignored. */
    static List<String> ignoredPatterns(String text) {
        List<String> out = new ArrayList<>();
        try {
            Object root = YAML.readValue(text, Object.class);
            if (root instanceof Map<?, ?> map) {
                for (var entry : map.entrySet()) {
                    if (!(entry.getValue() instanceof Map || entry.getValue() instanceof List)) {
                        out.add(String.valueOf(entry.getKey()));
                    }
                }
            }
        } catch (Exception ignored) {
            // syntaxProblem reports it
        }
        return out;
    }

    private static Map<String, Object> doParse(String text) {
        try {
            Object root = YAML.readValue(text, Object.class);
            if (!(root instanceof Map<?, ?> map)) {
                return Map.of();
            }
            Map<String, Object> out = new LinkedHashMap<>();
            for (var entry : map.entrySet()) {
                // An entry is one type-block (Map) or several (List); anything else is ignored.
                if (entry.getKey() != null && (entry.getValue() instanceof Map || entry.getValue() instanceof List)) {
                    out.put(entry.getKey().toString(), entry.getValue());
                }
            }
            return out;
        } catch (Exception ex) {
            return Map.of(); // a malformed content.schema disables typing rather than breaking the page
        }
    }

    /** The entry (a Map or a List) whose pattern matches {@code path}: exact wins, else the longest {@code *}-prefix. */
    static Object resolve(Map<String, Object> patterns, String path) {
        String pattern = matchingPattern(patterns, path);
        return pattern == null ? null : patterns.get(pattern);
    }

    /**
     * The pattern that matches {@code path}: the path itself when listed, else the longest {@code *}-prefix whose
     * prefix starts the path; {@code null} when none does. A {@code *} anywhere but at the end is a literal
     * character.
     */
    static String matchingPattern(Map<String, Object> patterns, String path) {
        if (patterns.isEmpty() || path == null) {
            return null;
        }
        if (patterns.containsKey(path)) {
            return path;
        }
        String best = null;
        int bestLen = -1;
        for (String pattern : patterns.keySet()) {
            if (pattern.endsWith("*")) {
                String prefix = pattern.substring(0, pattern.length() - 1);
                if (path.startsWith(prefix) && prefix.length() > bestLen) {
                    best = pattern;
                    bestLen = prefix.length();
                }
            }
        }
        return best;
    }

    /** Normalizes a resolved entry to its list of type-blocks: a single Map → one block; a List → its Map items. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> blocks(Object entry) {
        if (entry instanceof Map<?, ?> map) {
            return List.of((Map<String, Object>) map);
        }
        if (entry instanceof List<?> list) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    out.add((Map<String, Object>) map);
                }
            }
            return out;
        }
        return List.of();
    }
}
