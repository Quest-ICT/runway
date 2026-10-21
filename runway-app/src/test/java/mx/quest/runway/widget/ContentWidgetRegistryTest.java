package mx.quest.runway.widget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import mx.quest.runway.spi.ContentWidget;
import mx.quest.runway.spi.model.PageMeta;
import mx.quest.runway.spi.model.RenderFragment;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.spi.model.WidgetView;
import org.junit.jupiter.api.Test;

/** Registry indexing by key, soft-degrade for unknown/null, and fail-fast collisions. */
class ContentWidgetRegistryTest {

    private static WidgetView view() {
        SiteView site = new SiteView("acme", "Acme", Locale.ENGLISH, List.of(Locale.ENGLISH), "https://acme.test");
        PageMeta page = new PageMeta("home", "Home", "", List.of(Locale.ENGLISH), Locale.ENGLISH, true, false);
        return new WidgetView(site, page, Locale.ENGLISH, "main", false, java.util.Map.of());
    }

    private static ContentWidget widget(String key, String html) {
        return new ContentWidget() {
            @Override public String key() { return key; }
            @Override public RenderFragment render(WidgetView v) { return RenderFragment.of(html); }
        };
    }

    @Test
    void emptyRegistrySoftDegradesEverything() {
        ContentWidgetRegistry registry = new ContentWidgetRegistry(List.of());
        assertTrue(registry.isEmpty());
        assertTrue(registry.render("anything", view()).isEmpty());
    }

    @Test
    void rendersAKnownType() {
        ContentWidgetRegistry registry = new ContentWidgetRegistry(List.of(widget("hello", "<p>hi</p>")));
        assertEquals("<p>hi</p>", registry.render("hello", view()).html());
    }

    @Test
    void unknownTypeSoftDegradesToEmpty() {
        ContentWidgetRegistry registry = new ContentWidgetRegistry(List.of(widget("hello", "<p>hi</p>")));
        assertTrue(registry.render("missing", view()).isEmpty());
    }

    @Test
    void nullFragmentFromWidgetIsNormalizedToEmpty() {
        ContentWidget rogue = new ContentWidget() {
            @Override public String key() { return "rogue"; }
            @Override public RenderFragment render(WidgetView v) { return null; }
        };
        assertTrue(new ContentWidgetRegistry(List.of(rogue)).render("rogue", view()).isEmpty());
    }

    @Test
    void duplicateKeysFailFast() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new ContentWidgetRegistry(List.of(widget("dup", "<a>"), widget("dup", "<b>"))));
        assertTrue(ex.getMessage().contains("dup"), ex::getMessage);
    }

    @Test
    void blankKeyFailsFast() {
        assertThrows(IllegalStateException.class,
                () -> new ContentWidgetRegistry(List.of(widget(" ", "<a>"))));
    }
}
