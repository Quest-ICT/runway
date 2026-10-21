package mx.quest.runway.widget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.widget.WidgetInstanceDiscovery.PlacedWidget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Discovery scans a site's templates and reports the distinct, sorted widget placements it finds. */
class WidgetInstanceDiscoveryTest {

    private static final String SITE = "acme";

    private WidgetInstanceDiscovery discovery(Path external) {
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        return new WidgetInstanceDiscovery(StorageLayout.from(props));
    }

    private static void template(Path external, String name, String html) throws IOException {
        Path dir = external.resolve("sites").resolve(SITE).resolve("templates");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name + ".html"), html, StandardCharsets.UTF_8);
    }

    @Test
    void distinctInstancesAreReportedSortedWithTheirTemplates(@TempDir Path external) throws IOException {
        // "carousel/home" appears in two templates; "search/main" in one; a malformed anchor is ignored.
        template(external, "master", "<div id=\"c\" data-widget=\"carousel/home\"></div>"
                + "<div id=\"s\" data-widget=\"search/main\"></div>");
        template(external, "index", "<div id=\"c2\" data-widget=\"carousel/home\"></div>"
                + "<div data-widget=\"carousel/nope\"></div>"); // no id → skipped

        List<PlacedWidget> placed = discovery(external).discover(SITE);

        assertEquals(2, placed.size(), "carousel/home is de-duplicated across templates");
        assertEquals("carousel", placed.get(0).type());   // sorted: carousel before search
        assertEquals("home", placed.get(0).instance());
        assertEquals(List.of("index", "master"), placed.get(0).templates(), "both templates, sorted");
        assertEquals("search/main", placed.get(1).key());
        assertEquals(List.of("master"), placed.get(1).templates());
    }

    @Test
    void aSiteWithNoTemplatesDirectoryYieldsNothing(@TempDir Path external) {
        assertTrue(discovery(external).discover(SITE).isEmpty());
    }

    @Test
    void aTemplateWithNoWidgetsYieldsNothing(@TempDir Path external) throws IOException {
        template(external, "plain", "<h1>Hello</h1><p>No widgets here.</p>");
        assertTrue(discovery(external).discover(SITE).isEmpty());
    }
}
