package mx.quest.runway.widget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Anchor extraction: well-formed bindings, attribute-order independence, and skipping malformed anchors. */
class WidgetScannerTest {

    @Test
    void findsAWellFormedAnchor() {
        List<WidgetBinding> bindings =
                WidgetScanner.scan("<div id=\"slot\" data-widget=\"carousel/home\"></div>");
        assertEquals(1, bindings.size());
        WidgetBinding b = bindings.get(0);
        assertEquals("slot", b.anchorId());
        assertEquals("carousel", b.type());
        assertEquals("home", b.instanceId());
        assertEquals("carousel/home", b.dataWidget());
    }

    @Test
    void attributeOrderDoesNotMatter() {
        List<WidgetBinding> bindings =
                WidgetScanner.scan("<section data-widget=\"search/box\" id=\"s1\" class=\"c\"></section>");
        assertEquals(1, bindings.size());
        assertEquals("s1", bindings.get(0).anchorId());
        assertEquals("search", bindings.get(0).type());
    }

    @Test
    void returnsAnchorsInDocumentOrder() {
        List<WidgetBinding> bindings = WidgetScanner.scan(
                "<div id=\"a\" data-widget=\"t/one\"></div><p>x</p><div id=\"b\" data-widget=\"t/two\"></div>");
        assertEquals(List.of("a", "b"), bindings.stream().map(WidgetBinding::anchorId).toList());
        assertEquals(List.of("one", "two"), bindings.stream().map(WidgetBinding::instanceId).toList());
    }

    @Test
    void skipsAnchorWithoutAnId() {
        assertTrue(WidgetScanner.scan("<div data-widget=\"carousel/home\"></div>").isEmpty());
    }

    @Test
    void skipsMalformedDataWidgetValues() {
        assertTrue(WidgetScanner.scan("<div id=\"a\" data-widget=\"noslash\"></div>").isEmpty());
        assertTrue(WidgetScanner.scan("<div id=\"a\" data-widget=\"too/many/parts\"></div>").isEmpty());
        assertTrue(WidgetScanner.scan("<div id=\"a\" data-widget=\"bad type/home\"></div>").isEmpty());
        assertTrue(WidgetScanner.scan("<div id=\"a\" data-widget=\"\"></div>").isEmpty());
    }

    @Test
    void skipsUnsafeAnchorId() {
        assertTrue(WidgetScanner.scan("<div id=\"a b\" data-widget=\"t/i\"></div>").isEmpty());
    }

    @Test
    void emptyOrNullInputYieldsNoBindings() {
        assertTrue(WidgetScanner.scan("").isEmpty());
        assertTrue(WidgetScanner.scan(null).isEmpty());
        assertTrue(WidgetScanner.scan("<div id=\"x\">no widget here</div>").isEmpty());
    }
}
