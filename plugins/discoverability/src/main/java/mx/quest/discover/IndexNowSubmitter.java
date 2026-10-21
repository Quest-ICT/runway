package mx.quest.discover;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import mx.quest.runway.spi.model.CatalogChange;
import mx.quest.runway.spi.model.LocalizedUrl;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.spi.service.PluginSettings;
import mx.quest.runway.spi.service.SiteEventListener;
import mx.quest.runway.spi.service.Sites;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Tells the IndexNow-participating search engines that a site's content changed, instead of waiting for them
 * to come back and find out.
 *
 * <h2>What this is, and what it is not</h2>
 * IndexNow is a published protocol (Bing, Yandex, Seznam, Naver, Yep). It is a <b>freshness</b> signal, not a
 * ranking one: it shortens the gap between publishing and being re-crawled by those engines. <b>Google does
 * not participate</b>, so this changes nothing about Google's view of a site — worth saying plainly, because
 * the opposite is widely implied and a tenant who turns this on should know what they are buying.
 *
 * <h2>Two switches, both off by default, and why</h2>
 * This is the first thing in the product that makes an <b>outbound</b> request on a tenant's behalf. So it
 * needs the <b>platform operator's</b> permission (they own the network and the egress policy) <i>and</i> the
 * tenant's (they own the URLs being disclosed). An upgrade must never start making network calls a deployment
 * was not making before, so neither switch defaults on. A tenant who enables it while the platform has not
 * gets a WARN naming both the tenant and the setting to flip — a feature that is silently inert is worse than
 * one that is off, because nobody can tell the difference from inside.
 *
 * <h2>What a change submits: where the item was, and where it is now</h2>
 * The submitter is a {@link SiteEventListener} in the {@code CONSUMER} phase. Each page write or content change
 * arrives as a {@link CatalogChange} carrying the item's URLs <b>before</b> the change (as the host last listed
 * them) and <b>after</b> it; the submitter queues the union. So a <b>deleted</b>, unpublished or moved item reports
 * its old address — the change most worth telling an engine about, and the one 1.x could not report, because it
 * resolved the URLs after the host had already dropped the item from its listing. Only the node where the change
 * happened submits ({@link CatalogChange#local()}): a cluster replays every change on its other nodes, and N nodes
 * must not send N submissions.
 *
 * <h2>Batched, bounded, and off every request thread</h2>
 * Changes accumulate per site and are submitted by a scheduled flush ({@link #flush()}, a Spring
 * {@code @Scheduled} method — it runs only while the host has scheduling enabled; see the method). A page save touching five localized URLs
 * must not become five outbound requests, and a bulk edit or a site restore must not become thousands.
 * Everything is bounded — queued URLs per site, URLs per submission — and overflow is dropped with a WARN
 * naming the tenant, never silently. No save ever waits on the network, and a failed submission is logged and
 * dropped rather than retried indefinitely: a stale IndexNow ping is worthless, and an unbounded retry queue
 * during an outage is a liability.
 *
 * <p>The HTTP call is injected as an {@link Exchange} seam (the same pattern the protection plugin's captcha
 * verifier uses), so the payload building, the gating and the bounds are unit-testable with no network at all.
 */
public class IndexNowSubmitter implements SiteEventListener {

    private static final Logger log = LoggerFactory.getLogger(IndexNowSubmitter.class);

    /** The fixed path the site's key file is served at — see {@link IndexNowKeyEndpoint}. */
    static final String KEY_PATH = "/indexnow-key.txt";

    private final Sites sites;
    private final PluginSettings settings;
    private final DiscoverabilityProperties properties;
    private final Exchange exchange;

    /** siteId → the URLs changed since the last flush. Bounded per site; insertion-ordered for stable batches. */
    private final Map<String, Set<String>> pending = new ConcurrentHashMap<>();

    /** Sites already warned that they turned IndexNow on while the platform has it off — once per site per run. */
    private final Set<String> warnedInert = ConcurrentHashMap.newKeySet();

    public IndexNowSubmitter(Sites sites, PluginSettings settings, DiscoverabilityProperties properties) {
        this(sites, settings, properties, defaultExchange(properties));
    }

    /**
     * @param exchange the HTTP seam that performs the submission POST — injectable so the payload, the gating
     *                 and the bounds are exercised without a real network call
     */
    IndexNowSubmitter(Sites sites, PluginSettings settings, DiscoverabilityProperties properties, Exchange exchange) {
        this.sites = sites;
        this.settings = settings;
        this.properties = properties;
        this.exchange = exchange;
    }

    // --- collecting -----------------------------------------------------------------------------------

    /**
     * A page was created, saved, renamed, deleted, published or unpublished, or a content source announced a change:
     * every URL the item answered on before the change and every URL it answers on now is stale upstream.
     *
     * <p>Acts only on {@link CatalogChange#local() local} changes — the node where the change happened submits; a
     * peer's replay is ignored. A change that names no item (a site lifecycle event, a settings save, a whole-site
     * page reload) carries no URLs and is ignored, as 1.x ignored the events without an id. Runs on the writer's
     * thread, so it only enqueues: the network call happens in {@link #flush()}.
     */
    @Override
    public void onCatalogChanged(CatalogChange change) {
        if (!change.local() || change.id() == null) {
            return;
        }
        enqueue(change.siteId(), change.previousUrls(), change.currentUrls());
    }

    /**
     * Queues the item's URLs from before and after the change — {@code previous ∪ current}, in that order,
     * duplicates dropped — if the site has IndexNow on.
     *
     * <p>Gated before doing any work, so a deployment with the feature off pays nothing per save.
     */
    void enqueue(String siteId, List<LocalizedUrl> previous, List<LocalizedUrl> current) {
        if (siteId == null) {
            return;
        }
        if (!properties.getIndexnow().isEnabled()) {
            warnIfInert(siteId);
            return;
        }
        SiteView site = siteView(siteId);
        if (site == null || key(siteId).isEmpty()) {
            return;
        }
        Set<String> urls = urlsOf(site, previous, current);
        if (urls.isEmpty()) {
            return;
        }
        int cap = Math.max(1, properties.getIndexnow().getMaxQueuedPerSite());
        Set<String> queue = pending.computeIfAbsent(siteId, k -> java.util.Collections.synchronizedSet(
                new LinkedHashSet<>()));
        synchronized (queue) {
            for (String url : urls) {
                if (queue.size() >= cap) {
                    log.warn("IndexNow queue for site '{}' is full ({} URLs) — dropping further changes until "
                            + "the next submission. Raise indexnow.max-queued-per-site if this is routine.",
                            siteId, cap);
                    return;
                }
                queue.add(url);
            }
        }
    }

    // --- submitting -----------------------------------------------------------------------------------

    /**
     * Submits each site's accumulated URLs.
     *
     * <p>The fixed rate is the platform's {@code indexnow.batch-seconds}; Spring cannot read a bean field for
     * {@code @Scheduled}, so the annotation names the property and the default matches
     * {@link DiscoverabilityProperties.IndexNow#getBatchSeconds()}. The value is kept inside the settings form's
     * range, 5 to 3600 seconds, because a value set by hand (in the host's configuration or the settings file)
     * never passes the form: 0 or a negative value made the start fail (the scheduler refuses a delay of 0 or less).
     * A site with nothing queued costs one map lookup.
     *
     * <p><b>Scheduling is the host's.</b> The method runs only while the host application has Spring scheduling
     * enabled, which the kernel does unconditionally. This plugin deliberately does not start a scheduler of its own
     * (a plugin thread the host neither knows about nor stops).
     */
    @Scheduled(fixedDelayString = "#{T(java.lang.Math).max(5, T(java.lang.Math).min(3600, "
            + "${runway.plugin.discoverability.indexnow.batch-seconds:60})) * 1000}")
    public void flush() {
        if (!properties.getIndexnow().isEnabled() || pending.isEmpty()) {
            return;
        }
        for (String siteId : List.copyOf(pending.keySet())) {
            Set<String> queue = pending.remove(siteId);
            if (queue == null || queue.isEmpty()) {
                continue;
            }
            List<String> urls;
            synchronized (queue) {
                urls = List.copyOf(queue);
            }
            submit(siteId, urls);
        }
    }

    /**
     * Submits {@code urls} for one site, trimming to the per-submission cap and logging what was dropped.
     *
     * <p>Re-checks the platform switch even though every caller already has. A gate on outbound traffic is not
     * a place to rely on "no caller can reach here without checking" — the check costs a field read, and the
     * failure mode of getting it wrong is a deployment making network calls its operator refused.
     */
    void submit(String siteId, List<String> urls) {
        if (!properties.getIndexnow().isEnabled()) {
            return;
        }
        SiteView site = siteView(siteId);
        String key = key(siteId);
        if (site == null || key.isEmpty() || site.baseUrl() == null || site.baseUrl().isBlank()) {
            return;
        }
        int cap = Math.max(1, properties.getIndexnow().getMaxUrlsPerSubmission());
        List<String> batch = urls;
        if (urls.size() > cap) {
            // Named, counted, and attributed to the tenant: a silent trim is indistinguishable from a site
            // that simply changed less than it did.
            log.warn("IndexNow submission for site '{}' has {} URLs, over the {} cap — submitting the first {} "
                    + "and DROPPING {}. The dropped pages are still crawled normally.",
                    siteId, urls.size(), cap, cap, urls.size() - cap);
            batch = urls.subList(0, cap);
        }
        String host = hostOf(site.baseUrl());
        String payload = payload(host, key, site.baseUrl() + KEY_PATH, batch);
        try {
            Exchange.Response response = exchange.post(properties.getIndexnow().getEndpoint(), payload);
            if (response.status() >= 200 && response.status() < 300) {
                log.debug("IndexNow: submitted {} URL(s) for site '{}' ({})", batch.size(), siteId,
                        response.status());
            } else {
                // 403 means the key file did not verify — the single most likely misconfiguration, and one the
                // tenant can fix, so say what it means rather than only what happened.
                log.warn("IndexNow submission for site '{}' was refused ({}): {}{}", siteId, response.status(),
                        abbreviate(response.body()),
                        response.status() == 403
                                ? " — the engine could not verify " + site.baseUrl() + KEY_PATH
                                  + "; check the key matches and the path is publicly reachable." : "");
            }
        } catch (IOException | RuntimeException failed) {
            // Dropped, not retried: a stale freshness ping is worthless, and an unbounded retry queue during an
            // outage is a liability. The pages are still crawled the ordinary way.
            log.warn("IndexNow submission for site '{}' failed ({} URL(s) not submitted): {}",
                    siteId, batch.size(), failed.toString());
        }
    }

    /**
     * The IndexNow JSON body.
     *
     * <p>Hand-built rather than through a mapper because it is four fields and a string list, and the plugin
     * has no JSON dependency of its own. Every value is escaped — {@code host} and {@code keyLocation} derive
     * from tenant-controlled configuration, so an unescaped quote would be a payload-injection hole, not a
     * formatting bug.
     */
    static String payload(String host, String key, String keyLocation, List<String> urls) {
        StringBuilder json = new StringBuilder(128 + urls.size() * 64);
        json.append("{\"host\":\"").append(escape(host))
                .append("\",\"key\":\"").append(escape(key))
                .append("\",\"keyLocation\":\"").append(escape(keyLocation))
                .append("\",\"urlList\":[");
        for (int i = 0; i < urls.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append('"').append(escape(urls.get(i))).append('"');
        }
        return json.append("]}").toString();
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    // --- helpers --------------------------------------------------------------------------------------

    /** The absolute form of every URL in {@code previous} then {@code current}, without duplicates. */
    private static Set<String> urlsOf(SiteView site, List<LocalizedUrl> previous, List<LocalizedUrl> current) {
        String base = site.baseUrl();
        Set<String> urls = new LinkedHashSet<>();
        if (base == null || base.isBlank()) {
            return urls;
        }
        for (List<LocalizedUrl> list : List.of(previous, current)) {
            for (LocalizedUrl url : list) {
                urls.add(url.absolute(base.trim()));
            }
        }
        return urls;
    }

    /** The site's IndexNow key, or empty when unset — which is how a tenant leaves the feature off. */
    /**
     * The promise in the class Javadoc: a site that turned IndexNow on while the platform has it off is told, in
     * the log, which setting to flip — once per site per run (the platform switch only changes on restart), and
     * after that at no cost per save. The site's settings form says the same thing to the tenant.
     */
    private void warnIfInert(String siteId) {
        if (warnedInert.contains(siteId)) {
            return;
        }
        Map<String, Object> cfg = settings.site(siteId).values();
        if (SiteConfig.bool(cfg, "indexnow.enabled", false) && warnedInert.add(siteId)) {
            log.warn("site '{}' has IndexNow enabled, but the platform has not permitted it — nothing is submitted. "
                    + "The platform operator turns it on with discoverability's app-level indexnow.enabled.", siteId);
        }
    }

    /** Whether the inert-site warning has been logged for this site (tests). */
    boolean warnedInert(String siteId) {
        return warnedInert.contains(siteId);
    }

    private String key(String siteId) {
        Map<String, Object> cfg = settings.site(siteId).values();
        if (!SiteConfig.bool(cfg, "indexnow.enabled", false)) {
            return "";
        }
        String key = SiteConfig.str(cfg, "indexnow.key", "").trim();
        if (key.isEmpty()) {
            log.warn("site '{}' has IndexNow enabled but no indexnow.key — nothing is submitted. Generate a "
                    + "random key and set it, then confirm {} serves it.", siteId, KEY_PATH);
        }
        return key;
    }

    private SiteView siteView(String siteId) {
        return sites.find(siteId).orElse(null);
    }

    /** The bare host of a base URL — what IndexNow's {@code host} field expects (no scheme, no path). */
    static String hostOf(String baseUrl) {
        try {
            String host = URI.create(baseUrl).getHost();
            return host == null ? "" : host;
        } catch (IllegalArgumentException notAUrl) {
            return "";
        }
    }

    private static String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        String flat = body.replaceAll("[\\r\\n]+", " ").trim();
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "…";
    }

    // --- the HTTP seam --------------------------------------------------------------------------------

    /** The outbound call, isolated so everything around it is testable without a network. */
    public interface Exchange {

        /**
         * POSTs {@code json} ({@code application/json}) to {@code url}.
         *
         * @return the endpoint's response (status + body)
         * @throws IOException if the request cannot be completed
         */
        Response post(String url, String json) throws IOException;

        /** A minimal HTTP response view — the status and body this submitter needs to log an outcome. */
        record Response(int status, String body) {
        }
    }

    private static Exchange defaultExchange(DiscoverabilityProperties properties) {
        Duration timeout = Duration.ofSeconds(Math.max(1, properties.getIndexnow().getTimeoutSeconds()));
        HttpClient client = HttpClient.newBuilder().connectTimeout(timeout).build();
        return (url, json) -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(timeout)
                        .header("Content-Type", "application/json; charset=utf-8")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                return new Exchange.Response(response.statusCode(), response.body());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("IndexNow submission interrupted", interrupted);
            }
        };
    }
}
