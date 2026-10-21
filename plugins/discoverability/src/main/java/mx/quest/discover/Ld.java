package mx.quest.discover;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * The schema.org <b>JSON-LD builder</b> — the shared spine of both SEO+ and GEO. It builds ordered node maps,
 * serializes them into a single {@code @graph}, and wraps that in a {@code <script type="application/ld+json">}
 * block escaped so it can never break out of the {@code <head>}. Pure and stateless.
 *
 * <p>The {@code @id} conventions are the seam between the two capability areas: the SEO contributor defines the
 * {@code WebSite}/{@code WebPage}/{@code Organization} nodes, and the GEO contributor <b>enriches those same
 * nodes by @id reference</b> (adding {@code potentialAction}/{@code speakable}) rather than redefining them —
 * so a crawler merges them into one entity graph with no duplication.
 */
final class Ld {

    static final String CONTEXT = "https://schema.org";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private Ld() {
    }

    // --- stable @id conventions (shared by SEO + GEO) ----------------------

    static String websiteId(String base) {
        return base + "/#website";
    }

    static String organizationId(String base) {
        return base + "/#organization";
    }

    static String webpageId(String canonical) {
        return canonical + "#webpage";
    }

    static String softwareId(String base) {
        return base + "/#software";
    }

    /** The {@code scheme://host} origin of an absolute URL, or {@code ""} if it is not absolute. */
    static String origin(String url) {
        if (url == null) {
            return "";
        }
        int scheme = url.indexOf("://");
        if (scheme < 0) {
            return "";
        }
        int slash = url.indexOf('/', scheme + 3);
        return slash < 0 ? url : url.substring(0, slash);
    }

    // --- node building -----------------------------------------------------

    /** A new node with its {@code @type} (and {@code @id} when non-null). */
    static Map<String, Object> node(String type, String id) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("@type", type);
        if (id != null && !id.isBlank()) {
            m.put("@id", id);
        }
        return m;
    }

    /** Sets {@code key} to {@code value} only when the value is non-blank (empty properties are never emitted). */
    static void put(Map<String, Object> node, String key, String value) {
        if (value != null && !value.isBlank()) {
            node.put(key, value.trim());
        }
    }

    /** Sets {@code key} to a single value or an array (schema.org accepts either), omitting an empty list. */
    static void putList(Map<String, Object> node, String key, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        node.put(key, values.size() == 1 ? values.get(0) : values);
    }

    /** Sets {@code key} to an {@code @id} reference to another node. */
    static void ref(Map<String, Object> node, String key, String id) {
        if (id != null && !id.isBlank()) {
            node.put(key, Map.of("@id", id));
        }
    }

    /**
     * Serializes a {@code @graph} of nodes into an escaped {@code <script type="application/ld+json">} block,
     * or {@code ""} when the graph is empty. {@code <}, {@code >} and {@code &} are unicode-escaped so the JSON
     * can never terminate the surrounding {@code <script>} element.
     */
    static String script(List<Map<String, Object>> nodes) {
        List<Map<String, Object>> present = nodes.stream().filter(n -> n != null && !n.isEmpty()).toList();
        if (present.isEmpty()) {
            return "";
        }
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("@context", CONTEXT);
        graph.put("@graph", present);
        String json = JSON.writeValueAsString(graph)
                .replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026");
        return "\n<script type=\"application/ld+json\">" + json + "</script>";
    }
}
