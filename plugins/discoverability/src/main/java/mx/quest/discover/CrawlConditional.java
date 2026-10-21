package mx.quest.discover;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import mx.quest.runway.spi.model.CatalogEntry;
import mx.quest.runway.spi.model.LocalizedUrl;
import mx.quest.runway.spi.model.RouteRequest;
import mx.quest.runway.spi.model.RouteResponse;

/**
 * Conditional-GET support for the crawl surfaces (sitemap.xml, llms.txt): crawlers hit these often, and the
 * content only changes when the site's content does — so both endpoints emit {@code Last-Modified} with
 * {@code Cache-Control: no-cache} (cache it, but REVALIDATE every time), and answer {@code If-Modified-Since}
 * with an empty {@code 304} instead of re-serializing the whole document.
 *
 * <p><b>The validator must reflect MEMBERSHIP, not just member mtimes.</b> These documents are lists: deleting
 * or unpublishing an item only <em>removes</em> an {@code updatedAt} from the pool, so a naive
 * {@code max(updatedAt)} stays the same (or moves backward) and a revalidating crawler would keep its cached
 * copy — still advertising the removed URL — indefinitely. The validator therefore combines a per-site
 * <b>fingerprint</b> of the document-shaping facts (item identity, paths, {@code updatedAt}, {@code noindex},
 * and the host-computed URLs: every locale's address and canonical, and the {@code x-default}) — plus, per
 * document, the <b>document-level facts</b> it prints that no item carries (llms.txt's site name and
 * {@code geo.summary}) — with a <b>monotonic</b> emitted instant: while the fingerprint is unchanged the emitted value is stable; any
 * change advances it to {@code max(newest updatedAt, previous)} — or to <em>now</em> when the change carries no
 * newer mtime (a removal, an addition with a null {@code updatedAt}). A future-dated {@code updatedAt} (clock
 * skew, a touched file) is clamped to now so it cannot pin the validator and mask later real changes.
 *
 * <p>State is in-memory only. On restart the first request per site seeds the emitted instant with <em>now</em>
 * — deliberately: seeding with the content mtime would resurrect the removal blind spot for any membership
 * change that happened around the restart. The cost is one full {@code 200} per crawler after a restart; the
 * value then stabilizes until content actually changes. HTTP dates are second-granular, so instants are
 * truncated before comparing.
 */
final class CrawlConditional {

    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);

    /** What the site's crawl documents last looked like (fingerprint) and the validator emitted for it. */
    private record Validator(String fingerprint, Instant emitted) {
    }

    /**
     * Validator state per site <b>and document</b>. Until 2.1.0 the sitemap and llms.txt shared one record per site
     * (both derive from the same catalog list); llms.txt now also folds facts the sitemap does not print, and two
     * documents with different fingerprints sharing one record would advance each other's validator on every
     * alternate request.
     */
    private static final ConcurrentMap<String, Validator> STATE = new ConcurrentHashMap<>();

    /** The sitemap's document name — its validator folds the catalog only. */
    static final String SITEMAP = "sitemap.xml";
    /** The llms.txt document name — its validator also folds the site name and summary it prints. */
    static final String LLMS = "llms.txt";

    /** The time source — a test hook ({@link #reset} restores it); production always uses the system clock. */
    static java.util.function.Supplier<Instant> clock = Instant::now;

    private CrawlConditional() {
    }

    /** Test hook: forget all per-site validator state (simulates a restart) and restore the system clock. */
    static void reset() {
        STATE.clear();
        clock = Instant::now;
    }

    /** The sitemap's validator: {@link #lastModified(String, String, List, List)} with no document-level facts. */
    static Instant lastModified(String siteId, List<CatalogEntry> items) {
        return lastModified(siteId, SITEMAP, items, List.of());
    }

    /**
     * The {@code Last-Modified} validator for {@code siteId}'s crawl document {@code document} built from
     * {@code items} and the document-level {@code facts} it prints besides them (in a fixed order; a {@code null}
     * fact folds as empty) — membership-aware and monotonic per the class contract (never in the future, never moves
     * backward). A change to a fact advances the validator to <em>now</em>: it carries no mtime of its own.
     */
    static Instant lastModified(String siteId, String document, List<CatalogEntry> items, List<String> facts) {
        Instant now = clock.get().truncatedTo(ChronoUnit.SECONDS);
        Instant newest = newestUpdatedAt(items, now);
        String fingerprint = fingerprint(items, facts);
        // A printable separator ('|' is never part of a site id), never a control character.
        Validator computed = STATE.compute(siteId + '|' + document, (id, prev) -> {
            if (prev == null) {
                return new Validator(fingerprint, now); // restart seed: now, per the class contract
            }
            if (prev.fingerprint().equals(fingerprint)) {
                return prev; // unchanged document keeps its validator — this is what makes 304s possible
            }
            Instant advanced = (newest != null && newest.isAfter(prev.emitted())) ? newest : now;
            if (!advanced.isAfter(prev.emitted())) {
                // Cannot advance within HTTP-date granularity (a change in the same second as the previous
                // advance, or a backward clock). Keep the OLD state — fingerprint included — so a later
                // request re-detects the change and advances once the clock has moved; committing the new
                // fingerprint now would absorb the change at the old validator forever.
                return prev;
            }
            return new Validator(fingerprint, advanced);
        });
        return computed.emitted();
    }

    /** The newest {@code updatedAt} across {@code items} clamped to {@code now}, or null when none carries one. */
    private static Instant newestUpdatedAt(List<CatalogEntry> items, Instant now) {
        Instant newest = null;
        for (CatalogEntry item : items) {
            Instant updated = item.ref().updatedAt();
            if (updated != null && (newest == null || updated.isAfter(newest))) {
                newest = updated;
            }
        }
        if (newest == null) {
            return null;
        }
        newest = newest.truncatedTo(ChronoUnit.SECONDS);
        return newest.isAfter(now) ? now : newest; // a future mtime must not pin the validator
    }

    /**
     * A digest of the document-shaping facts: identity, paths, mtime, indexability, and the URLs the host computed —
     * order-sensitive. The URLs are folded in because they change without any item changing: a site that starts
     * serving another locale gains alternates, and a canonical override moves an item in or out of the sitemap.
     * (Until 2.0.0 they were not, so a crawler revalidating after such a change kept its old copy.) The document-level
     * facts come first, each length-prefixed so no fact can shift characters into the next (until 2.1.0 there were
     * none, so editing the site name or {@code geo.summary} changed llms.txt without advancing its validator).
     */
    private static String fingerprint(List<CatalogEntry> items, List<String> facts) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
        StringBuilder sb = new StringBuilder(128);
        sb.append("facts#").append(facts.size()).append(';');
        for (String fact : facts) {
            String value = fact == null ? "" : fact;
            sb.append(value.length()).append(':').append(value);
        }
        sb.append('\n');
        digest.update(sb.toString().getBytes(StandardCharsets.UTF_8));
        for (CatalogEntry item : items) {
            sb.setLength(0);
            sb.append(item.compositeId()).append('|').append(item.ref().path()).append('|')
                    .append(item.ref().localizedPaths()).append('|').append(item.ref().updatedAt()).append('|')
                    .append(item.ref().noindex()).append('|').append(item.xDefaultPath());
            for (LocalizedUrl url : item.urls()) {
                sb.append('|').append(url.locale().toLanguageTag()).append('=').append(url.path())
                        .append('>').append(url.canonical());
            }
            sb.append('\n');
            digest.update(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    /** Whether the request's {@code If-Modified-Since} already covers {@code lastModified} (null ⇒ no). */
    static boolean notModified(RouteRequest req, Instant lastModified) {
        if (lastModified == null) {
            return false;
        }
        String header = header(req, "If-Modified-Since");
        if (header == null || header.isBlank()) {
            return false;
        }
        try {
            Instant since = Instant.from(HTTP_DATE.parse(header.trim()));
            return !lastModified.truncatedTo(ChronoUnit.SECONDS).isAfter(since);
        } catch (RuntimeException unparseable) {
            return false; // an invalid date = an unconditional request
        }
    }

    /** The empty {@code 304} carrying the same validator/caching headers as the {@code 200} would. */
    static RouteResponse notModifiedResponse(Instant lastModified) {
        return new RouteResponse(304, cacheHeaders(lastModified, null), new byte[0]);
    }

    /** The response headers for a fresh {@code 200}: content type + validator + revalidate-always caching. */
    static Map<String, String> cacheHeaders(Instant lastModified, String contentType) {
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        if (contentType != null) {
            headers.put("Content-Type", contentType);
        }
        if (lastModified != null) {
            headers.put("Last-Modified", HTTP_DATE.format(lastModified.truncatedTo(ChronoUnit.SECONDS)));
        }
        headers.put("Cache-Control", "no-cache");
        return headers;
    }

    private static String header(RouteRequest req, String name) {
        for (var entry : req.headers().entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }
}
