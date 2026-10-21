package mx.quest.runway.widget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import mx.quest.runway.crypto.SecretCipher;
import mx.quest.runway.plugin.PluginSettingDef;
import mx.quest.runway.spi.ContentWidget;
import mx.quest.runway.spi.model.PageMeta;
import mx.quest.runway.spi.model.RenderFragment;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.spi.model.WidgetView;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The wiring that closes the "config is always empty" gap: an anchor's stored per-instance config reaches the
 * widget as {@code WidgetView.config()}; an unconfigured anchor still gets an empty map (the SPI default).
 */
class WidgetModelBuilderConfigTest {

    private static final String SITE = "acme";

    @Test
    void theStoredInstanceConfigReachesTheWidgetView(@TempDir Path external) throws IOException {
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);

        // A template that anchors the widget, and a stored config for that exact instance.
        writeTemplate(external, "home", "<div id=\"hero\" data-widget=\"carousel/home\"></div>");
        WidgetInstanceConfigService config = new WidgetInstanceConfigService(layout, noCipher());
        config.save(SITE, "carousel", "home", List.of(new PluginSettingDef("heading", "string", null, "l", null)),
                Map.of("heading", "Welcome"));

        AtomicReference<WidgetView> captured = new AtomicReference<>();
        ContentWidget capturing = new ContentWidget() {
            @Override public String key() {
                return "carousel";
            }
            @Override public RenderFragment render(WidgetView view) {
                captured.set(view);
                return RenderFragment.of("ok");
            }
        };
        WidgetModelBuilder builder = new WidgetModelBuilder(
                new ContentWidgetRegistry(List.of(capturing)), layout, config);

        SiteView site = new SiteView(SITE, "Acme", Locale.ENGLISH, List.of(Locale.ENGLISH), "https://acme.test");
        PageMeta page = new PageMeta("home", "Home", "", List.of(Locale.ENGLISH), Locale.ENGLISH, true, false);
        builder.build(SITE, "home", null, site, page, Locale.ENGLISH);

        assertEquals("Welcome", captured.get().config().get("heading"), "the store's value reaches the widget");
        assertEquals("home", captured.get().instanceId());
    }

    @Test
    void anUnconfiguredAnchorGetsAnEmptyConfig(@TempDir Path external) throws IOException {
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        writeTemplate(external, "home", "<div id=\"hero\" data-widget=\"carousel/promos\"></div>");

        AtomicReference<WidgetView> captured = new AtomicReference<>();
        ContentWidget capturing = new ContentWidget() {
            @Override public String key() {
                return "carousel";
            }
            @Override public RenderFragment render(WidgetView view) {
                captured.set(view);
                return RenderFragment.empty();
            }
        };
        WidgetModelBuilder builder = new WidgetModelBuilder(new ContentWidgetRegistry(List.of(capturing)),
                layout, new WidgetInstanceConfigService(layout, noCipher()));

        SiteView site = new SiteView(SITE, "Acme", Locale.ENGLISH, List.of(Locale.ENGLISH), "https://acme.test");
        PageMeta page = new PageMeta("home", "Home", "", List.of(Locale.ENGLISH), Locale.ENGLISH, true, false);
        builder.build(SITE, "home", null, site, page, Locale.ENGLISH);

        assertTrue(captured.get().config().isEmpty(), "no stored config → empty map (use widget defaults)");
    }

    @Test
    void theRequestQueryReachesTheWidget(@TempDir Path external) throws IOException {
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        writeTemplate(external, "home", "<div id=\"box\" data-widget=\"search/box\"></div>");

        AtomicReference<WidgetView> captured = new AtomicReference<>();
        ContentWidget capturing = new ContentWidget() {
            @Override public String key() {
                return "search";
            }
            @Override public RenderFragment render(WidgetView view) {
                captured.set(view);
                return RenderFragment.of("ok");
            }
        };
        WidgetModelBuilder builder = new WidgetModelBuilder(new ContentWidgetRegistry(List.of(capturing)),
                layout, new WidgetInstanceConfigService(layout, noCipher()));

        SiteView site = new SiteView(SITE, "Acme", Locale.ENGLISH, List.of(Locale.ENGLISH), "https://acme.test");
        PageMeta page = new PageMeta("home", "Home", "", List.of(Locale.ENGLISH), Locale.ENGLISH, true, false);
        builder.build(SITE, "home", null, site, page, Locale.ENGLISH, java.util.Map.of("q", "widgets"));

        // A request-dependent widget (search results, a ?sent confirmation) reads the query from its view.
        assertEquals("widgets", captured.get().query().get("q"), "the request query reaches the widget");
    }

    private static void writeTemplate(Path external, String name, String html) throws IOException {
        Path dir = external.resolve("sites").resolve(SITE).resolve("templates");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name + ".html"), html, StandardCharsets.UTF_8);
    }

    private static SecretCipher noCipher() {
        return new SecretCipher() {
            @Override public boolean isAvailable() {
                return false;
            }
            @Override public String encrypt(String plaintext) {
                throw new UnsupportedOperationException();
            }
            @Override public String decrypt(String value) {
                return value;
            }
            @Override public boolean isEncrypted(String value) {
                return value != null && value.startsWith("ENC(");
            }
        };
    }
}
