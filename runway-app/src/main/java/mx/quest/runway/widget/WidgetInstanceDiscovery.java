package mx.quest.runway.widget;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;
import mx.quest.runway.storage.StorageLayout;
import org.springframework.stereotype.Component;

/**
 * Finds every content-widget <em>placement</em> in a site — the distinct {@code <type>/<instance>} anchors the
 * designer wrote into the site's templates — so the tenant <b>Content widgets</b> screen can list them and offer
 * a per-instance editor. It scans every {@code sites/<id>/templates/*.html} file with the same
 * {@link WidgetScanner} the render path uses, so the discovered set is exactly the set that renders.
 *
 * <p>Pure discovery: it reads templates and returns data; it never renders, resolves config, or touches plugin
 * code. A widget type appearing in several templates is reported once, with the list of templates it appears in.
 * The result is sorted (type, then instance) for a stable screen.
 */
@Component
public class WidgetInstanceDiscovery {

    private static final String TEMPLATES_DIR = "templates";
    private static final String SUFFIX = ".html";

    private final StorageLayout layout;

    public WidgetInstanceDiscovery(StorageLayout layout) {
        this.layout = layout;
    }

    /**
     * One placed widget instance: the anchor's type and instance id, plus which template file(s) place it.
     *
     * @param type      the widget type ({@code <type>} half of a {@code data-widget} anchor)
     * @param instance  the instance id ({@code <instance>} half)
     * @param templates the template base-names (slugs) that carry this anchor, sorted, de-duplicated
     */
    public record PlacedWidget(String type, String instance, List<String> templates) {

        /** The composite {@code <type>/<instance>} key — the storage key and the form's path segments. */
        public String key() {
            return type + "/" + instance;
        }
    }

    /**
     * The distinct widget instances placed anywhere in {@code siteId}'s templates, sorted by (type, instance).
     *
     * @param siteId the bound tenancy (traversal-validated by the storage layout)
     * @return the placements (never {@code null}; empty when the site places no widgets)
     */
    public List<PlacedWidget> discover(String siteId) {
        Path templatesDir = layout.siteDir(siteId).resolve(TEMPLATES_DIR); // siteDir validates siteId
        if (!Files.isDirectory(templatesDir)) {
            return List.of();
        }
        // key = "type/instance"; value = (type, instance, sorted-unique template names)
        Map<String, TreeSet<String>> byKey = new LinkedHashMap<>();
        Map<String, String[]> parts = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(templatesDir)) {
            files.filter(Files::isRegularFile)
                 .filter(p -> p.getFileName().toString().endsWith(SUFFIX))
                 .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                 .forEach(p -> scanInto(p, byKey, parts));
        } catch (IOException e) {
            throw new UncheckedIOException("listing templates for site " + siteId, e);
        }
        List<PlacedWidget> out = new ArrayList<>(byKey.size());
        parts.forEach((key, tv) -> out.add(new PlacedWidget(tv[0], tv[1], List.copyOf(byKey.get(key)))));
        out.sort(Comparator.comparing(PlacedWidget::type).thenComparing(PlacedWidget::instance));
        return out;
    }

    private void scanInto(Path template, Map<String, TreeSet<String>> byKey, Map<String, String[]> parts) {
        String name = template.getFileName().toString();
        String base = name.substring(0, name.length() - SUFFIX.length());
        String html;
        try {
            html = Files.readString(template, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return; // an unreadable template contributes no placements — skip it, don't fail the whole screen
        }
        for (WidgetBinding binding : WidgetScanner.scan(html)) {
            String key = binding.dataWidget();
            byKey.computeIfAbsent(key, k -> new TreeSet<>()).add(base);
            parts.putIfAbsent(key, new String[]{binding.type(), binding.instanceId()});
        }
    }
}
