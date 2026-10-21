package mx.quest.discover;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Null-safe readers over the parsed {@code content.schema} entry maps (nested {@code Map}/{@code List} produced
 * by the YAML parser). Shared by the {@link SchemaType} implementations so each stays a small pure mapper.
 */
final class Schemas {

    private Schemas() {
    }

    /** The string value of {@code key}, or {@code null} when absent/blank. */
    static String str(Map<String, Object> map, String key) {
        Object v = map == null ? null : map.get(key);
        if (v == null) {
            return null;
        }
        String s = v.toString();
        return s.isBlank() ? null : s;
    }

    /** The string value of {@code key}, or {@code def} when absent/blank. */
    static String str(Map<String, Object> map, String key, String def) {
        String v = str(map, key);
        return v == null ? def : v;
    }

    /** The list-of-maps value of {@code key} (e.g. {@code faq}, {@code locations}, {@code people}); never null. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> maps(Map<String, Object> map, String key) {
        Object v = map == null ? null : map.get(key);
        if (!(v instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                out.add((Map<String, Object>) m);
            }
        }
        return out;
    }

    /** The list-of-strings value of {@code key} (a scalar is treated as a one-element list); never null. */
    static List<String> strings(Map<String, Object> map, String key) {
        Object v = map == null ? null : map.get(key);
        if (v instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object o : list) {
                if (o != null && !o.toString().isBlank()) {
                    out.add(o.toString());
                }
            }
            return out;
        }
        if (v instanceof String s && !s.isBlank()) {
            return List.of(s);
        }
        return List.of();
    }

    /**
     * {@code url} made absolute against the site {@code base} when it is root-relative ({@code /img/a.png}); any
     * other value as given. JSON-LD URLs must be absolute; until 2.2.0 a root-relative image was emitted as written,
     * which search engines reject.
     */
    static String absolute(String url, String base) {
        return url != null && url.startsWith("/") && !url.startsWith("//") && base != null && !base.isBlank()
                ? base + url : url;
    }

    /** {@link #absolute} applied to each URL of a list. */
    static List<String> absolute(List<String> urls, String base) {
        List<String> out = new ArrayList<>(urls.size());
        for (String url : urls) {
            out.add(absolute(url, base));
        }
        return out;
    }
}
