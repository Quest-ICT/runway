package mx.quest.runway.widget;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import mx.quest.runway.spi.model.PageMeta;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.storage.PathSafety;
import mx.quest.runway.storage.StorageLayout;
import org.springframework.stereotype.Component;

/**
 * Builds the {@code widgets} render-model entry the {@link WidgetAwareDecoupledLogicResolver}'s
 * generated {@code th:utext="${widgets['<id>']}"} rules read: a map from anchor id to the widget's
 * pre-rendered markup. The host scans the page template and its master for {@code data-widget}
 * anchors, materializes an immutable {@link mx.quest.runway.spi.model.WidgetView} per anchor, and
 * asks the {@link ContentWidgetRegistry} to render it.
 *
 * <p>The same {@link WidgetScanner} that drives the resolver's rule generation drives this map, over
 * the same template files, so the keys always line up. Anchors in the <b>master</b> are chrome
 * (their {@code isChrome} is {@code true}); anchors in the page body are not. An anchor whose widget
 * is unknown or has nothing to show yields an empty string, so the resolver's rule clears the
 * author's preview placeholder (the soft-degrade contract).
 *
 * <p><b>Per-instance configuration is resolved from {@link WidgetInstanceConfigService}</b>: each anchor's
 * {@code <type>/<instance>} pair keys into the tenant's {@code widget-instances.yml}, so two placements of the
 * same widget type render with independent config. An unconfigured instance carries an empty config map, which
 * the SPI defines as "use the widget's defaults" — so a widget with no per-instance settings behaves exactly as
 * before this store existed.
 */
@Component
public class WidgetModelBuilder {

    private static final String TEMPLATES_DIR = "templates";
    private static final String SUFFIX = ".html";

    private final ContentWidgetRegistry registry;
    private final StorageLayout layout;
    private final WidgetInstanceConfigService instanceConfig;

    public WidgetModelBuilder(ContentWidgetRegistry registry, StorageLayout layout,
                              WidgetInstanceConfigService instanceConfig) {
        this.registry = registry;
        this.layout = layout;
        this.instanceConfig = instanceConfig;
    }

    /** As the full {@link #build(String, String, String, SiteView, PageMeta, Locale, Map, String) form} with no query and no path (a bare preview). */
    public Map<String, String> build(String siteId, String pageName, String master,
                                     SiteView site, PageMeta page, Locale locale) {
        return build(siteId, pageName, master, site, page, locale, Map.of(), "");
    }

    /** As the full form with no request path (for renders not tied to a public request). */
    public Map<String, String> build(String siteId, String pageName, String master,
                                     SiteView site, PageMeta page, Locale locale, Map<String, String> query) {
        return build(siteId, pageName, master, site, page, locale, query, "");
    }

    /**
     * Renders every content widget anchored in the page or its master into a map keyed by anchor id.
     *
     * @param siteId   the current tenant's id (traversal-validated by the storage layout)
     * @param pageName the page template's name (its slug — the {@code <name>.html} file)
     * @param master   the master template's name (its {@code <name>.html} file), or {@code null}
     * @param site     the resolved site view handed to each widget
     * @param page     the resolved page view handed to each widget
     * @param locale   the resolved request locale handed to each widget
     * @param query    the request's query params (first value per name) handed to each widget, so a widget can
     *                 render request-dependent output (search results for {@code ?q=}, a {@code ?sent}
     *                 confirmation); pass an empty map when there is none
     * @param path     the public request path exactly as served ({@code request.getRequestURI()} — locale
     *                 prefix included), reaching each widget as {@link mx.quest.runway.spi.model.WidgetView#path()};
     *                 empty when the render is not tied to a public request (previews)
     * @return anchor id → widget markup (empty string for a soft-degraded anchor); never {@code null}
     */
    public Map<String, String> build(String siteId, String pageName, String master,
                                     SiteView site, PageMeta page, Locale locale, Map<String, String> query,
                                     String path) {
        Map<String, String> widgets = new HashMap<>();
        addFrom(widgets, siteId, pageName, false, site, page, locale, query, path);
        if (master != null && !master.equals(pageName)) {
            addFrom(widgets, siteId, master, true, site, page, locale, query, path);
        }
        return widgets;
    }

    /**
     * Renders only the <b>master's</b> widget anchors — the variant a <b>virtual</b> render (a content
     * source's page) uses: its body is a plugin-owned template, not a tenant file this builder could scan, so
     * only the tenant chrome's anchors (a header search box, a footer widget) are filled. All anchors are
     * chrome ({@code isChrome=true}).
     *
     * @param path the public request path exactly as served (see the full {@code build} form); empty for
     *             renders not tied to a public request
     * @return anchor id → widget markup (empty string for a soft-degraded anchor); never {@code null}
     */
    public Map<String, String> buildForMaster(String siteId, String master, SiteView site, PageMeta page,
                                              Locale locale, Map<String, String> query, String path) {
        Map<String, String> widgets = new HashMap<>();
        if (master != null) {
            addFrom(widgets, siteId, master, true, site, page, locale, query, path);
        }
        return widgets;
    }

    /**
     * Whether the page template or the master carries at least one {@code data-widget} anchor — the anchors
     * {@link #build} (or, with a {@code null} page, {@link #buildForMaster}) would fill. An anchor counts whether or
     * not its widget type is installed. The host asks this before deciding whether a render with a query string can be
     * revalidated: a widget may combine the query with data the kernel cannot see.
     *
     * @param pageName the page template's name, or {@code null} for a virtual render (master anchors only)
     * @param master   the master template's name, or {@code null}
     */
    public boolean hasAnchors(String siteId, String pageName, String master) {
        return anchoredIn(siteId, pageName) || (master != null && !master.equals(pageName) && anchoredIn(siteId, master));
    }

    private boolean anchoredIn(String siteId, String templateName) {
        if (templateName == null) {
            return false;
        }
        String html = readTemplate(siteId, templateName);
        return html != null && !WidgetScanner.scan(html).isEmpty();
    }

    private void addFrom(Map<String, String> widgets, String siteId, String templateName, boolean chrome,
                         SiteView site, PageMeta page, Locale locale, Map<String, String> query, String path) {
        String html = readTemplate(siteId, templateName);
        if (html == null) {
            return;
        }
        for (WidgetBinding binding : WidgetScanner.scan(html)) {
            Map<String, Object> config = instanceConfig.config(siteId, binding.type(), binding.instanceId());
            mx.quest.runway.spi.model.WidgetView view = new mx.quest.runway.spi.model.WidgetView(
                    site, page, locale, binding.instanceId(), chrome, config, query, path);
            widgets.put(binding.anchorId(), registry.render(binding.type(), view).html());
        }
    }

    /** Reads a tenant template's HTML, or {@code null} when the file is absent. Traversal-safe. */
    private String readTemplate(String siteId, String templateName) {
        Path templates = layout.siteDir(siteId).resolve(TEMPLATES_DIR); // siteDir validates siteId
        Path file = PathSafety.resolveConfined(templates, templateName + SUFFIX);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException("reading template " + file, ex);
        }
    }
}
