package mx.quest.discover;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The JSON-LD core: @id conventions, origin extraction, node building, and safe <script> serialization. */
class LdTest {

    @Test
    void idConventionsAreStableAndAnchored() {
        assertEquals("https://x.test/#website", Ld.websiteId("https://x.test"));
        assertEquals("https://x.test/#organization", Ld.organizationId("https://x.test"));
        assertEquals("https://x.test/about#webpage", Ld.webpageId("https://x.test/about"));
    }

    @Test
    void originExtractsSchemeAndHost() {
        assertEquals("https://x.test", Ld.origin("https://x.test/about?q=1"));
        assertEquals("http://x.test:8080", Ld.origin("http://x.test:8080/"));
        assertEquals("", Ld.origin("/relative/path"));
        assertEquals("", Ld.origin(null));
    }

    @Test
    void scriptWrapsAGraphAndOmitsEmpty() {
        assertEquals("", Ld.script(List.of()), "an empty graph contributes nothing");
        Map<String, Object> node = Ld.node("WebPage", "https://x.test/#p");
        Ld.put(node, "name", "Hello");
        String out = Ld.script(List.of(node));
        assertTrue(out.contains("<script type=\"application/ld+json\">"));
        assertTrue(out.contains("\"@context\":\"https://schema.org\""));
        assertTrue(out.contains("\"@graph\""));
        assertTrue(out.contains("\"@type\":\"WebPage\""));
        assertTrue(out.contains("\"name\":\"Hello\""));
    }

    @Test
    void scriptNeutralizesHtmlBreakoutInsideTheScriptElement() {
        Map<String, Object> node = Ld.node("WebPage", null);
        Ld.put(node, "name", "a </script><b> & c");
        String out = Ld.script(List.of(node));
        assertFalse(out.contains("</script><b>"), "raw < / > / & must not survive inside the script element");
        assertTrue(out.contains("\\u003c"));
        assertTrue(out.contains("\\u003e"));
        assertTrue(out.contains("\\u0026"));
    }

    @Test
    void putOmitsBlanksAndPutListCollapsesSingletons() {
        Map<String, Object> node = Ld.node("Organization", null);
        Ld.put(node, "name", "  ");
        Ld.put(node, "url", null);
        assertFalse(node.containsKey("name"));
        assertFalse(node.containsKey("url"));
        Ld.putList(node, "sameAs", List.of("https://x.test"));
        assertEquals("https://x.test", node.get("sameAs"), "a single sameAs is a scalar");
        Ld.putList(node, "sameAs", List.of("https://a", "https://b"));
        assertEquals(List.of("https://a", "https://b"), node.get("sameAs"), "multiple is an array");
    }
}
