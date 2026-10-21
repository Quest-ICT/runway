package mx.quest.runway.spi.testkit;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import mx.quest.runway.spi.model.CatalogEntry;
import mx.quest.runway.spi.service.ContentCatalogView;

/**
 * A programmable {@link ContentCatalogView} for plugin tests: the test states what the site's listing holds, which
 * real pages own which paths, and what indexable text an entry has.
 *
 * <pre>{@code
 * FakeContentCatalog catalog = new FakeContentCatalog()
 *         .site("acme")
 *         .entry("acme", entry)                        // a CatalogEntry built by the test
 *         .page("acme", "/about", Locale.ENGLISH, "about")
 *         .text("acme", "blog:hello", "<p>Hello</p>");
 * }</pre>
 *
 * The input rules match the kernel's: an unsafe site id throws {@link IllegalArgumentException}; an unknown,
 * well-formed id answers empty, {@code ""} or {@code false}. {@link #servablePath} applies the kernel's path rule (a
 * root-relative path with no {@code ..}, {@code //}, {@code |} or control characters) and its reserved surface ({@code /__…}, {@code /actuator},
 * {@code /error}, {@code /app/…}, {@code /login}, {@code /logout}, {@code /forgot-password}, {@code /reset}) plus any
 * path the test {@linkplain #route declares as an endpoint route}. {@link #refresh} is counted. Thread-safe.
 */
public final class FakeContentCatalog implements ContentCatalogView {

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final List<String> RESERVED_EXACT = List.of("/actuator", "/error", "/login", "/logout",
            "/forgot-password", "/reset");
    private static final List<String> RESERVED_PREFIXES = List.of("/__", "/actuator", "/app/");

    private final Set<String> sites = ConcurrentHashMap.newKeySet();
    private final Map<String, List<CatalogEntry>> entries = new ConcurrentHashMap<>();
    private final Map<String, String> pages = new ConcurrentHashMap<>();
    private final Map<String, String> texts = new ConcurrentHashMap<>();
    private final Set<String> routes = ConcurrentHashMap.newKeySet();
    private final List<String> refreshed = new CopyOnWriteArrayList<>();

    /** Declares a site that exists (with no entries yet). */
    public FakeContentCatalog site(String siteId) {
        sites.add(safe(siteId));
        return this;
    }

    /** Adds an entry to the site's listing (declaring the site). */
    public FakeContentCatalog entry(String siteId, CatalogEntry entry) {
        site(siteId);
        entries.computeIfAbsent(siteId, k -> new CopyOnWriteArrayList<>()).add(Objects.requireNonNull(entry));
        return this;
    }

    /** Removes every entry of the site (it stays known). */
    public FakeContentCatalog clear(String siteId) {
        entries.remove(safe(siteId));
        return this;
    }

    /** Declares that the published real page {@code slug} serves {@code path} in {@code locale}. */
    public FakeContentCatalog page(String siteId, String path, Locale locale, String slug) {
        site(siteId);
        pages.put(siteId + "|" + locale.toLanguageTag() + "|" + path, slug);
        return this;
    }

    /** Sets the indexable text of the entry {@code compositeId} ({@code <sourceKey>:<id>}). */
    public FakeContentCatalog text(String siteId, String compositeId, String text) {
        site(siteId);
        texts.put(siteId + "|" + compositeId, text);
        return this;
    }

    /** Declares an exact endpoint route ({@code /sitemap.xml}), which {@link #servablePath} then refuses. */
    public FakeContentCatalog route(String path) {
        routes.add(path);
        return this;
    }

    /** The site ids {@link #refresh} was called with, in order. */
    public List<String> refreshed() {
        return List.copyOf(refreshed);
    }

    @Override
    public List<CatalogEntry> entries(String siteId) {
        safe(siteId);
        return List.copyOf(entries.getOrDefault(siteId, List.of()));
    }

    @Override
    public String indexableText(String siteId, CatalogEntry entry, Locale locale) {
        safe(siteId);
        Objects.requireNonNull(entry, "entry");
        Objects.requireNonNull(locale, "locale");
        return texts.getOrDefault(siteId + "|" + entry.compositeId(), "");
    }

    @Override
    public Optional<String> pageAt(String siteId, String path, Locale locale) {
        safe(siteId);
        return Optional.ofNullable(pages.get(siteId + "|" + locale.toLanguageTag() + "|" + path));
    }

    @Override
    public boolean servablePath(String siteId, String path) {
        safe(siteId);
        if (!sites.contains(siteId) || !safePagePath(path) || routes.contains(path)) {
            return false;
        }
        if (RESERVED_EXACT.contains(path)) {
            return false;
        }
        for (String prefix : RESERVED_PREFIXES) {
            if (path.startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void refresh(String siteId) {
        refreshed.add(safe(siteId));
    }

    /** The kernel's page-path rule: root-relative, no {@code ..}, {@code //}, {@code |} or control characters. */
    private static boolean safePagePath(String path) {
        if (path == null || path.isEmpty() || path.charAt(0) != '/') {
            return false;
        }
        if (path.indexOf('|') >= 0 || path.contains("..") || path.contains("//")) {
            return false;
        }
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                return false;
            }
        }
        for (String reserved : List.of("/actuator", "/static", "/error")) {
            if (path.equals(reserved) || path.startsWith(reserved + "/")) {
                return false;
            }
        }
        return true;
    }

    private static String safe(String siteId) {
        if (siteId == null || !SAFE_ID.matcher(siteId).matches() || siteId.contains("..")) {
            throw new IllegalArgumentException("unsafe site id: " + siteId);
        }
        return siteId;
    }
}
