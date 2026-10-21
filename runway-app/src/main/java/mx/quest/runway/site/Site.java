package mx.quest.runway.site;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import mx.quest.runway.storage.PathSafety;

/**
 * The stored per-site (tenant) record — one {@code site.yml} under {@code sites/<id>/}. This is
 * the kernel's internal domain model (not the SPI boundary; that is
 * {@link mx.quest.runway.spi.model.SiteView}, materialized by {@link SiteViews}), so it can evolve
 * freely.
 *
 * <p>The {@code id} doubles as the site's folder name, so it must be a safe identifier; the
 * canonical constructor enforces that and defensively copies/normalizes the rest. Records persist
 * to and load from YAML via {@link mx.quest.runway.storage.YamlStore}.
 *
 * @param id            stable identifier and folder name (must be a safe id — no {@code ..} or
 *                      separators)
 * @param name          human-facing site/brand name (never {@code null})
 * @param hosts         hostnames that resolve to this site (never {@code null}; copied; may be empty)
 * @param defaultLocale the site's default locale (never {@code null})
 * @param locales       the locales the site offers (never {@code null}; copied; may be empty)
 * @param baseUrl       explicit base URL for absolute links, or {@code null} to derive from the
 *                      primary host
 * @param status        the lifecycle state ({@code null} normalized to {@link SiteStatus#ACTIVE})
 * @param editorial     how strictly page publishing is governed ({@code null} normalized to
 *                      {@link EditorialLevel#ADVISORY}, so a {@code site.yml} without the key reads exactly as it
 *                      did before the level existed)
 */
public record Site(String id, String name, List<String> hosts, Locale defaultLocale,
                   List<Locale> locales, String baseUrl, SiteStatus status,
                   boolean maintenance, String maintenanceMessage, EditorialLevel editorial) {

    public Site {
        PathSafety.requireSafeId(id);
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(defaultLocale, "defaultLocale");
        hosts = (hosts == null) ? List.of() : List.copyOf(hosts);
        locales = normalizeLocales(defaultLocale, locales);
        status = (status == null) ? SiteStatus.ACTIVE : status;
        maintenanceMessage = normalizeMessage(maintenance, maintenanceMessage);
        editorial = (editorial == null) ? EditorialLevel.ADVISORY : editorial;
    }

    /**
     * The pre-editorial nine-arg form, kept so every caller that predates the editorial level keeps compiling and
     * builds an {@link EditorialLevel#ADVISORY advisory} site — today's behaviour.
     */
    public Site(String id, String name, List<String> hosts, Locale defaultLocale,
                List<Locale> locales, String baseUrl, SiteStatus status,
                boolean maintenance, String maintenanceMessage) {
        this(id, name, hosts, defaultLocale, locales, baseUrl, status, maintenance, maintenanceMessage, null);
    }

    /**
     * Canonicalizes the offered locales so the <b>default is always first</b> — the load-bearing "default-first"
     * invariant the rest of the kernel assumes: {@link mx.quest.runway.i18n.SiteMessagesStore} maps the base
     * {@code messages.properties} to {@code locales.get(0)} (so the Messages editor's default column is that
     * file), and page/URL resolution treats the first locale as the default. The record is the single place
     * this is guaranteed, so a <b>hand-edited or imported</b> {@code site.yml} that changes {@code defaultLocale}
     * without reordering {@code locales} (or omits the default from the list) is repaired here rather than
     * silently mis-labelling the default's messages as another locale's — the exact defect a manual
     * {@code defaultLocale: es → en} edit caused (the editor showed the new default's column empty because it
     * was resolved as a non-default suffixed file). An empty list is left empty (a site offering no locales).
     */
    private static List<Locale> normalizeLocales(Locale defaultLocale, List<Locale> locales) {
        List<Locale> copy = (locales == null) ? List.of() : List.copyOf(locales);
        if (copy.isEmpty() || copy.get(0).equals(defaultLocale)) {
            return copy;
        }
        List<Locale> reordered = new java.util.ArrayList<>(copy.size() + 1);
        reordered.add(defaultLocale); // the default leads; then the remaining offered locales in their order
        for (Locale locale : copy) {
            if (!locale.equals(defaultLocale)) {
                reordered.add(locale);
            }
        }
        return List.copyOf(reordered);
    }

    /**
     * Convenience constructor for callers that do not set maintenance state (the common case): maintenance
     * off, no message. Keeps the pre-maintenance seven-arg call shape working.
     */
    public Site(String id, String name, List<String> hosts, Locale defaultLocale,
                List<Locale> locales, String baseUrl, SiteStatus status) {
        this(id, name, hosts, defaultLocale, locales, baseUrl, status, false, null);
    }

    /** Upper bound on the stored maintenance message — it is echoed verbatim into every public 503 body. */
    static final int MAX_MAINTENANCE_MESSAGE = 2000;

    private static String normalizeMessage(boolean maintenance, String message) {
        if (!maintenance || message == null || message.isBlank()) {
            return null;
        }
        String stripped = message.strip();
        return stripped.length() <= MAX_MAINTENANCE_MESSAGE
                ? stripped
                : stripped.substring(0, MAX_MAINTENANCE_MESSAGE);
    }

    /** Whether the site is live (served normally). */
    public boolean active() {
        return status == SiteStatus.ACTIVE;
    }

    /** Whether the site is operator-suspended. */
    public boolean suspended() {
        return status == SiteStatus.SUSPENDED;
    }

    /** This site with a new {@link #status} (maintenance state preserved). */
    public Site withStatus(SiteStatus newStatus) {
        return new Site(id, name, hosts, defaultLocale, locales, baseUrl, newStatus, maintenance, maintenanceMessage,
                editorial);
    }

    /** This site with maintenance mode toggled and an optional visitor-facing message. */
    public Site withMaintenance(boolean on, String message) {
        return new Site(id, name, hosts, defaultLocale, locales, baseUrl, status, on, message, editorial);
    }

    /**
     * This site with a new tenant-editable configuration — display {@link #name}, {@link #defaultLocale} and
     * available {@link #locales} — every routing/lifecycle field preserved. This is exactly the field set a
     * <em>site manager</em> may change on the tenant config screen: {@link #hosts} (routing), {@link #status},
     * {@link #maintenance} and {@link #baseUrl} are platform/app-realm concerns and stay untouched, so the
     * method cannot be used to escalate a tenant edit into a routing change. The record's canonical
     * constructor re-validates and defensively copies the new locale list.
     *
     * @param newName          the new display name (never {@code null})
     * @param newDefaultLocale the new default locale (never {@code null})
     * @param newLocales       the new available-locale list (never {@code null}; copied)
     * @return the reconfigured site
     */
    public Site withConfig(String newName, Locale newDefaultLocale, List<Locale> newLocales) {
        return new Site(id, newName, hosts, newDefaultLocale, newLocales, baseUrl, status, maintenance,
                maintenanceMessage, editorial);
    }

    /**
     * This site with a new routing {@link #hosts} set — every other field preserved. Routing is an
     * <em>app-realm</em> concern (never tenant-editable via {@link #withConfig}); the caller
     * ({@link SiteLifecycleService#updateHosts}) validates and normalizes the list before this is applied. The
     * canonical constructor defensively copies the new list.
     *
     * @param newHosts the new hostname list (never {@code null}; copied; may be empty ⇒ reachable only as the
     *                 platform catch-all default)
     * @return the re-hosted site
     */
    public Site withHosts(List<String> newHosts) {
        return new Site(id, name, newHosts, defaultLocale, locales, baseUrl, status, maintenance, maintenanceMessage,
                editorial);
    }

    /**
     * This site with a new {@link #baseUrl} ({@code null} ⇒ derived from the first host) — every other field
     * preserved. An app-realm concern like {@link #hosts}; the caller validates it ({@link SiteBaseUrl}).
     */
    public Site withBaseUrl(String newBaseUrl) {
        return new Site(id, name, hosts, defaultLocale, locales, newBaseUrl, status, maintenance, maintenanceMessage,
                editorial);
    }

    /**
     * This site with a new {@link #editorial} level — every other field preserved. Set from the tenant Site config
     * screen through {@link SiteLifecycleService#updateEditorial}.
     *
     * @param level the new level ({@code null} ⇒ {@link EditorialLevel#ADVISORY})
     * @return the re-governed site
     */
    public Site withEditorial(EditorialLevel level) {
        return new Site(id, name, hosts, defaultLocale, locales, baseUrl, status, maintenance, maintenanceMessage,
                level);
    }
}
