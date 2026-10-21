package mx.quest.runway.content;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import mx.quest.runway.page.ContentUrls;
import mx.quest.runway.page.LocaleUrls;
import mx.quest.runway.page.Routable;
import mx.quest.runway.spi.model.CatalogEntry;
import mx.quest.runway.spi.model.ContentRef;
import mx.quest.runway.spi.model.LocalizedUrl;
import mx.quest.runway.spi.model.PageMeta;
import mx.quest.runway.spi.model.SiteView;

/**
 * Adapts a source's {@link ContentRef} onto the kernel's {@link Routable} dispatch surface, so virtual content
 * runs the <em>same</em> locale-dispatch ladder, render model and head treatment as a real page. The
 * {@link ContentRef} methods already mirror {@code Page}'s semantics exactly (that parity is the record's
 * contract); this adapter only adds the kernel-side identity and chrome decisions:
 *
 * <ul>
 *   <li><b>meta slug = {@code <sourceKey>:<id>}</b> — the composite identity head contributors and URL maps
 *       see. {@code :} cannot appear in a page slug (PathSafety), so the namespace can never collide with a
 *       real page's; the composite must never reach a file-reading path (it is an identity, not a filename);</li>
 *   <li><b>master = the {@code master} param, else {@code _master}</b> — the tenant's own chrome always wraps
 *       the content (tenant-owns-its-look); a source may point at another master the tenant ships via
 *       {@code params: {master: ...}}.</li>
 * </ul>
 *
 * @param sourceKey the owning {@link mx.quest.runway.spi.ContentSource#key()} (never {@code null})
 * @param ref       the content's metadata (never {@code null})
 */
public record RoutableContent(String sourceKey, ContentRef ref) implements Routable {

    /** The default master template a virtual page renders under when the ref names none. */
    public static final String DEFAULT_MASTER = "_master";

    /** The {@code params} key a ref may set to pick another master the tenant ships. */
    public static final String MASTER_PARAM = "master";

    public RoutableContent {
        Objects.requireNonNull(sourceKey, "sourceKey");
        Objects.requireNonNull(ref, "ref");
    }

    /** The composite identity ({@code <sourceKey>:<id>}) — collision-free with page slugs, never a filename. */
    public String compositeId() {
        return sourceKey + ":" + ref.id();
    }

    @Override
    public String pathFor(Locale locale) {
        return ref.pathFor(locale);
    }

    @Override
    public boolean hasLocale(Locale locale, Locale siteDefault) {
        return ref.hasLocale(locale, siteDefault);
    }

    @Override
    public Locale primaryLocale(Locale siteDefault) {
        return ref.primaryLocale(siteDefault);
    }

    @Override
    public String titleFor(Locale locale) {
        return ref.titleFor(locale);
    }

    @Override
    public String descriptionFor(Locale locale) {
        return ref.descriptionFor(locale);
    }

    @Override
    public List<Locale> locales() {
        return ref.locales();
    }

    @Override
    public String canonical() {
        return ref.canonical();
    }

    @Override
    public boolean noindex() {
        return ref.noindex();
    }

    @Override
    public String activeNav() {
        return ref.activeNav();
    }

    @Override
    public Map<String, String> params() {
        return ref.params();
    }

    @Override
    public String cacheControl() {
        return ref.cacheControl();
    }

    @Override
    public String masterTemplate() {
        String master = ref.params().get(MASTER_PARAM);
        return (master == null || master.isBlank()) ? DEFAULT_MASTER : master;
    }

    /**
     * This item as the SPI's {@link CatalogEntry}: its metadata plus one {@link LocalizedUrl} per available locale
     * and its {@code x-default} path — every URL computed by {@link ContentUrls}/{@link LocaleUrls}, the same
     * authority the {@code <head>} alternates and the sitemap use, so a plugin's listing cannot disagree with them.
     * The canonical of each URL is the ref's override as given, else the URL itself.
     *
     * @param site the site the item is listed for (its locales clamp the available set)
     * @return the entry (never {@code null})
     */
    public CatalogEntry toCatalogEntry(SiteView site) {
        Locale siteDefault = site.defaultLocale();
        String override = ref.canonical();
        List<LocalizedUrl> urls = ContentUrls.availableLocales(this, site).stream()
                .map(locale -> {
                    String path = LocaleUrls.urlFor(this, locale, siteDefault);
                    return new LocalizedUrl(locale, path, override);
                })
                .toList();
        return new CatalogEntry(sourceKey, ref, urls, ContentUrls.xDefault(this, site, ""));
    }

    @Override
    public PageMeta meta(Locale locale, Locale siteDefault) {
        return new PageMeta(compositeId(), ref.titleFor(locale), ref.descriptionFor(locale), ref.locales(),
                ref.primaryLocale(siteDefault), ref.published(), ref.noindex(), sourceKey, ref.provenance());
    }
}
