package mx.quest.runway.site;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import mx.quest.runway.event.KernelEventOrder;
import mx.quest.runway.platform.PlatformSettingsStore;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Resolves an incoming request's host to a site, and holds the resolved site for the current
 * request.
 *
 * <p>A lazily-built, cached {@code host → siteId} index backs {@link #resolve(String)}; it is
 * rebuilt when a {@link SiteChangedEvent} fires (create/suspend/delete/…). The "current site" is
 * bound per request by {@link SiteContextFilter} and read by everything downstream via
 * {@link #current()} — the kernel's single answer to "which tenant is this request for?".
 *
 * <p><b>Catch-all default site.</b> When no configured host matches, resolution falls back to the
 * platform's <b>default site</b> ({@link PlatformSettingsStore} → {@code defaultSiteId}) — the one site that
 * answers requests not explicitly tied to a host. This is a pure host-<i>resolution</i> fallback: the
 * resolved default is still a normal tenant, bound and isolated exactly like any other; only the "which
 * site?" answer changes, never authz or tenant isolation. With no default configured, an unmatched host
 * resolves to empty (a 404), the pre-default behaviour.
 */
@Component
public class SiteResolver {

    private final SiteStore sites;
    private final PlatformSettingsStore platform;
    private final ThreadLocal<Site> current = new ThreadLocal<>();
    private volatile Map<String, String> hostIndex;

    public SiteResolver(SiteStore sites, PlatformSettingsStore platform) {
        this.sites = Objects.requireNonNull(sites, "sites");
        this.platform = Objects.requireNonNull(platform, "platform");
    }

    /**
     * The site whose hosts include {@code host} (port stripped, case-insensitive); failing an explicit match,
     * the platform's catch-all {@linkplain PlatformSettingsStore default site}; else empty.
     */
    public Optional<Site> resolve(String host) {
        if (host != null) {
            String id = index().get(normalizeHost(host));
            if (id != null) {
                return sites.find(id);
            }
        }
        // No explicit host match — fall back to the catch-all default site, but only while it is ACTIVE. A
        // suspended default must not turn every unmatched host into a 503 (that would surprise the operator, who
        // suspended one site, not all unbound traffic); an absent/deleted/dangling default id resolves to empty
        // (a 404), the pre-default behaviour.
        return platform.get().defaultSite().flatMap(sites::find).filter(Site::active);
    }

    /**
     * The site whose hosts <b>explicitly</b> include {@code host} — the same index lookup as {@link #resolve},
     * but with <b>no catch-all fallback</b>: an unmatched host yields empty rather than the platform default site.
     *
     * <p>For callers that must not answer for a tenant the caller did not name. The per-site health probe is the
     * motivating case: under {@link #resolve} a probe aimed at a typo'd or de-configured hostname would silently
     * be answered by the default site and report <em>a different tenant</em> as healthy — a false green, which is
     * strictly worse than no health check at all. Request routing keeps using {@link #resolve}; this method adds
     * no behaviour there and changes none.
     */
    public Optional<Site> resolveExplicit(String host) {
        if (host == null) {
            return Optional.empty();
        }
        String id = index().get(normalizeHost(host));
        return id == null ? Optional.empty() : sites.find(id);
    }

    /** Binds the resolved site for the current request (called by {@link SiteContextFilter}). */
    public void bind(Site site) {
        current.set(Objects.requireNonNull(site, "site"));
    }

    /** The site bound to the current request, or empty if none was resolved (e.g. an app-realm host). */
    public Optional<Site> current() {
        return Optional.ofNullable(current.get());
    }

    /** Clears the current-request binding (called in a {@code finally} by {@link SiteContextFilter}). */
    public void clear() {
        current.remove();
    }

    /**
     * Invalidates the host index so it is rebuilt on the next resolve. Synchronized on the same monitor as
     * {@link #index()} so an invalidation can never be lost to a build that is in flight on another thread (which
     * would otherwise re-publish a now-stale snapshot over the null).
     */
    @EventListener
    @Order(KernelEventOrder.STORES)
    public synchronized void onSiteChanged(SiteChangedEvent event) {
        hostIndex = null;
    }

    /**
     * The host→siteId index, lazily (re)built. Correct double-checked locking: the hot path (index already built)
     * reads the {@code volatile} field with no lock, and only the build is serialized — so two concurrent
     * cache-miss threads can't both run {@link #buildIndex()} and have the staler snapshot win the last write.
     */
    private Map<String, String> index() {
        Map<String, String> snapshot = hostIndex;
        if (snapshot == null) {
            synchronized (this) {
                snapshot = hostIndex;
                if (snapshot == null) {
                    snapshot = buildIndex();
                    hostIndex = snapshot;
                }
            }
        }
        return snapshot;
    }

    private Map<String, String> buildIndex() {
        Map<String, String> map = new HashMap<>();
        for (Site site : sites.findAll()) {
            for (String host : site.hosts()) {
                map.putIfAbsent(normalizeHost(host), site.id());
            }
        }
        return Map.copyOf(map);
    }

    private static String normalizeHost(String host) {
        String h = host.toLowerCase(Locale.ROOT).trim();
        int colon = h.indexOf(':');
        return colon >= 0 ? h.substring(0, colon) : h;
    }
}
