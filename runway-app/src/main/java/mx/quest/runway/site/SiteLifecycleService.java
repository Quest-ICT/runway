package mx.quest.runway.site;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;
import mx.quest.runway.event.HostWriteScope;
import mx.quest.runway.i18n.SiteMessagesStore;
import mx.quest.runway.storage.StorageLayout;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Create / suspend / resume / delete a site, plus the default-site seed. Each mutation persists the
 * {@link Site} record and publishes a {@link SiteChangedEvent} so the {@link SiteResolver} index and
 * the kernel's caches stay fresh.
 *
 * <p>Scope note: this manages the site's <em>folder and lifecycle state</em>. Provisioning a
 * tenant superadmin (security realm), seeding presentation (templating), and backup/restore are
 * built in their own later steps.
 */
@Service
public class SiteLifecycleService {

    private final SiteStore sites;
    private final StorageLayout layout;
    private final ApplicationEventPublisher events;
    private final SiteMessagesStore messages;

    public SiteLifecycleService(SiteStore sites, StorageLayout layout, ApplicationEventPublisher events,
                                SiteMessagesStore messages) {
        this.sites = Objects.requireNonNull(sites, "sites");
        this.layout = Objects.requireNonNull(layout, "layout");
        this.events = Objects.requireNonNull(events, "events");
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    /**
     * Creates a new site (status {@link SiteStatus#ACTIVE}).
     *
     * @throws IllegalStateException    if a site with this id already exists
     * @throws IllegalArgumentException if a host is malformed or already belongs to another site ({@link SiteHosts})
     */
    public Site create(String id, String name, List<String> hosts, Locale defaultLocale, List<Locale> locales) {
        return create(id, name, hosts, defaultLocale, locales, null);
    }

    /**
     * Creates a site to be born from a chosen seed template. {@code templateId} is passed on the
     * {@link SiteChangedEvent.Kind#CREATED} event so {@link TenantSeeder} seeds from that template; a
     * {@code null}/blank id (or an unknown one) falls back to the built-in default. The template choice is
     * <b>not</b> persisted — the site owns its copy after creation (self-containment).
     *
     * @throws IllegalStateException    if a site with this id already exists
     * @throws IllegalArgumentException if a host is malformed or already belongs to another site ({@link SiteHosts})
     */
    public Site create(String id, String name, List<String> hosts, Locale defaultLocale, List<Locale> locales,
                       String templateId) {
        List<String> normalized = SiteHosts.normalize(hosts);
        Site site;
        synchronized (SiteHosts.CLAIMS) {
            if (sites.exists(id)) {
                throw new IllegalStateException("site already exists: " + id);
            }
            SiteHosts.requireUnclaimed(sites, id, normalized);
            site = new Site(id, name, normalized, defaultLocale, locales, null, SiteStatus.ACTIVE);
            sites.save(site);
        }
        publish(new SiteChangedEvent(site.id(), SiteChangedEvent.Kind.CREATED, templateId));
        return site;
    }

    /** Suspends the site (public requests are then short-circuited). */
    public Site suspend(String id) {
        return transition(id, SiteStatus.SUSPENDED, SiteChangedEvent.Kind.SUSPENDED);
    }

    /** Resumes a suspended site. */
    public Site resume(String id) {
        return transition(id, SiteStatus.ACTIVE, SiteChangedEvent.Kind.RESUMED);
    }

    /**
     * Toggles the <em>tenant-controlled</em> maintenance mode for a site and, when turning it on, an optional
     * visitor-facing message. Independent of the operator {@link SiteStatus} — an operator-suspended site
     * stays suspended regardless. Persists and publishes {@link SiteChangedEvent.Kind#UPDATED} so the resolver
     * cache refreshes and the maintenance filter sees the change on the next request.
     *
     * @param id      the site id
     * @param on      whether maintenance mode is on
     * @param message optional message shown to visitors (ignored/cleared when {@code on} is false)
     * @return the updated site
     * @throws IllegalArgumentException if no such site exists
     */
    public Site setMaintenance(String id, boolean on, String message) {
        Site site = sites.find(id).orElseThrow(() -> new IllegalArgumentException("no such site: " + id));
        Site updated = site.withMaintenance(on, message);
        sites.save(updated);
        publish(new SiteChangedEvent(id, SiteChangedEvent.Kind.UPDATED));
        return updated;
    }

    /**
     * Applies a <em>tenant-realm</em> configuration edit to a site: its display name and its language set
     * (available locales + the default). This is the site-manager-editable slice of the {@link Site} record;
     * routing ({@link Site#hosts}), lifecycle ({@link SiteStatus}/maintenance) and the id stay under app-realm
     * control and are preserved by {@link Site#withConfig}. Persists the record (which evicts the
     * {@link SiteStore} cache) and publishes {@link SiteChangedEvent.Kind#UPDATED} so the resolver index and
     * every per-site cache (params, SEO, i18n, manifest…) drop their stale entries — the new name/locales are
     * therefore visible on the very next request.
     *
     * <p><b>Validation is the caller's contract.</b> The default locale MUST be one of {@code locales}; the
     * name MUST be non-blank. This method assumes both hold (the controller enforces them and surfaces the
     * messages) and only guards the structural invariant — an unknown site id — so a bad tenancy can never
     * write another site's file.
     *
     * @param id             the (bound) site to reconfigure
     * @param name           the new non-blank display name
     * @param defaultLocale  the new default locale (must be in {@code locales})
     * @param locales        the available-locale list (must be non-empty and contain {@code defaultLocale})
     * @return the reconfigured site
     * @throws IllegalArgumentException if no such site exists
     */
    public Site updateConfig(String id, String name, Locale defaultLocale, List<Locale> locales) {
        Site site = sites.find(id).orElseThrow(() -> new IllegalArgumentException("no such site: " + id));
        // If the default locale changes, re-home the i18n bundle files so the base messages.properties keeps
        // holding the CURRENT default's content — otherwise switching the default silently mislabels the
        // suffix-less base file and blanks the old default's column in the Messages editor (see
        // SiteMessagesStore#migrateDefaultLocale). Done BEFORE the record save so a failed migration aborts the
        // whole change rather than leaving the record and the files disagreeing.
        if (!site.defaultLocale().equals(defaultLocale)) {
            try {
                messages.migrateDefaultLocale(id, site.defaultLocale(), defaultLocale);
            } catch (IOException ex) {
                throw new UncheckedIOException("Failed migrating i18n bundles for default-locale change on " + id, ex);
            }
        }
        Site updated = site.withConfig(name, defaultLocale, locales);
        sites.save(updated);
        publish(new SiteChangedEvent(id, SiteChangedEvent.Kind.UPDATED));
        return updated;
    }

    /**
     * Sets the site's {@linkplain EditorialLevel editorial level} — every other field preserved. A no-op (no
     * write, no event) when the level is unchanged. Changing it never touches pages: drafts stay drafts, pages in
     * review and pending revisions stay where they are (an approver can still act on them at any level), and the
     * new level governs the <em>next</em> publish or save.
     *
     * @param id    the (bound) site
     * @param level the new level (never {@code null})
     * @return the site as stored afterwards
     * @throws IllegalArgumentException if no such site exists
     */
    public Site updateEditorial(String id, EditorialLevel level) {
        java.util.Objects.requireNonNull(level, "level");
        Site site = sites.find(id).orElseThrow(() -> new IllegalArgumentException("no such site: " + id));
        if (site.editorial() == level) {
            return site;
        }
        Site updated = site.withEditorial(level);
        sites.save(updated);
        publish(new SiteChangedEvent(id, SiteChangedEvent.Kind.UPDATED));
        return updated;
    }

    /**
     * Applies an <em>app-realm</em> routing edit: replaces the site's {@link Site#hosts} set. Unlike the
     * tenant-editable {@link #updateConfig}, this changes routing, so the controller gates it by the grantable
     * {@code MANAGE_SITE_HOSTS} permission (delegable to a role/user; the master always passes). Each requested
     * host is lowercased and trimmed, blanks and duplicates are dropped, and every survivor must be a
     * syntactically valid dot-separated hostname (no scheme, port, path or whitespace). A host already claimed by
     * <em>another</em> site is rejected — two sites resolving the same host would make routing ambiguous. An empty
     * result is allowed (the site is then reachable only as the platform catch-all default). Persists the record
     * (evicting the {@link SiteStore} cache) and publishes {@link SiteChangedEvent.Kind#UPDATED} so the
     * {@link SiteResolver} host index refreshes on the very next request.
     *
     * @param id    the site to re-host
     * @param hosts the requested host list (each normalized here)
     * @return the re-hosted site
     * @throws IllegalArgumentException if the site is unknown, a host is malformed, or a host belongs to another site
     */
    public Site updateHosts(String id, List<String> hosts) {
        return rehost(id, hosts, false, null);
    }

    /**
     * Sets a site's hosts and its base URL together, as the Edit hosts dialog does: both decide the URLs the site is
     * reached and linked at. {@code baseUrl} is checked by {@link SiteBaseUrl} before anything is saved; blank
     * means derived from the first host. Every per-site cache is evicted ({@link SiteChangedEvent}), so canonical
     * links and the sitemap use the new base on the next request.
     *
     * @throws IllegalArgumentException for an unknown site, an invalid or claimed host, or an invalid base URL
     */
    public Site updateHosts(String id, List<String> hosts, String baseUrl) {
        return rehost(id, hosts, true, SiteBaseUrl.normalize(baseUrl));
    }

    /** Saves new hosts and, when {@code setBase}, the already-checked {@code base}; else the stored base is kept. */
    private Site rehost(String id, List<String> hosts, boolean setBase, String base) {
        Site site = sites.find(id).orElseThrow(() -> new IllegalArgumentException("no such site: " + id));
        List<String> normalized = SiteHosts.normalize(hosts);
        Site updated;
        synchronized (SiteHosts.CLAIMS) {
            // Reject any host already owned by a DIFFERENT site — ambiguous host→site routing must be impossible.
            SiteHosts.requireUnclaimed(sites, id, normalized);
            updated = site.withHosts(normalized);
            if (setBase) {
                updated = updated.withBaseUrl(base);
            }
            sites.save(updated);
        }
        publish(new SiteChangedEvent(id, SiteChangedEvent.Kind.UPDATED));
        return updated;
    }

    /**
     * Deletes a site and its entire folder.
     *
     * @return {@code true} if a site was deleted
     */
    public boolean delete(String id) {
        if (!sites.exists(id)) {
            return false;
        }
        Path dir = layout.siteDir(id); // traversal-safe
        deleteRecursively(dir);
        sites.evict(id);
        publish(new SiteChangedEvent(id, SiteChangedEvent.Kind.DELETED));
        return true;
    }

    /**
     * Seeds a default site if none exists yet (the first-run starting point). Returns the seeded
     * site, or empty if sites already exist.
     */
    public java.util.Optional<Site> seedDefaultSite() {
        if (!sites.findAll().isEmpty()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(
                create("default", "Default Site", List.of("localhost"), Locale.ENGLISH, List.of(Locale.ENGLISH)));
    }

    /**
     * Publishes a lifecycle event inside a {@link HostWriteScope}: the kernel listeners run synchronously as always,
     * and plugin {@code SiteEventListener} callbacks run when the scope closes — at the end of this publishing path,
     * after any host lock a caller holds around it has been released (when the caller opened a scope of its own,
     * as {@code BackupService} does), and before the lifecycle call returns.
     */
    private void publish(SiteChangedEvent event) {
        try (HostWriteScope scope = HostWriteScope.enter()) {
            events.publishEvent(event);
        }
    }

    private Site transition(String id, SiteStatus status, SiteChangedEvent.Kind kind) {
        Site site = sites.find(id).orElseThrow(() -> new IllegalArgumentException("no such site: " + id));
        Site updated = site.withStatus(status);
        sites.save(updated);
        publish(new SiteChangedEvent(id, kind));
        return updated;
    }

    private static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new UncheckedIOException("deleting " + path, e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("walking " + dir, e);
        }
    }
}
