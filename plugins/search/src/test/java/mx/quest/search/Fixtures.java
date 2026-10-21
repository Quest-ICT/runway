package mx.quest.search;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import mx.quest.runway.spi.model.CatalogEntry;
import mx.quest.runway.spi.model.ContentRef;
import mx.quest.runway.spi.model.FileEntry;
import mx.quest.runway.spi.model.LocalizedUrl;
import mx.quest.runway.spi.model.RouteRequest;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.spi.service.PluginSiteFiles;
import mx.quest.search.spi.SearchHit;
import mx.quest.search.spi.SearchQuery;
import mx.quest.search.spi.SiteSearchEngine;

/** Shared builders for the plugin's tests: sites, catalog entries, requests and canned engines. */
final class Fixtures {

    static final SiteView ACME = new SiteView("acme", "Acme", Locale.ENGLISH, List.of(Locale.ENGLISH), null);

    private Fixtures() {
    }

    /** A published English-only entry of {@code source} at {@code /<id>}. */
    static CatalogEntry entry(String source, String id, String title, String description) {
        ContentRef ref = new ContentRef(id, "/" + id, Map.of(), List.of(Locale.ENGLISH), Map.of("en", title),
                Map.of("en", description), "", "tpl", true, Instant.EPOCH, false, null, null, Map.of());
        return new CatalogEntry(source, ref, List.of(new LocalizedUrl(Locale.ENGLISH, "/" + id, null)), null);
    }

    /** A published page (source {@code pages}, id = slug). */
    static CatalogEntry page(String slug, String title, String description) {
        return entry("pages", slug, title, description);
    }

    /** A request on site acme with an optional {@code q} and the kernel-gated client IP. */
    static RouteRequest request(String method, String path, String q, String clientIp) {
        Map<String, List<String>> query = q == null ? Map.of() : Map.of("q", List.of(q));
        return new RouteRequest(ACME, Locale.ENGLISH, method, path, query, Map.of(), new byte[0], Map.of(),
                clientIp, "http://acme.test");
    }

    static RouteRequest request(String method, String path, String q) {
        return request(method, path, q, "203.0.113.7");
    }

    /** An engine that returns canned hits and advertises a priority/availability. */
    static SiteSearchEngine engine(String key, int priority, boolean available, SearchHit... hits) {
        return new SiteSearchEngine() {
            @Override
            public String key() {
                return key;
            }

            @Override
            public List<SearchHit> search(SearchQuery q) {
                return List.of(hits);
            }

            @Override
            public int priority() {
                return priority;
            }

            @Override
            public boolean available(String siteId) {
                return available;
            }
        };
    }

    static SearchRateLimiter unlimited() {
        SearchProperties.RateLimit limits = new SearchProperties.RateLimit();
        limits.setPerIpPerMinute(0);
        limits.setPerIpPerHour(0);
        return new SearchRateLimiter(limits);
    }

    /** A per-site folder that holds nothing and accepts nothing (for tests that never touch files). */
    static final class NoFiles implements PluginSiteFiles {
        @Override
        public Optional<String> readText(String siteId, String relPath) {
            return Optional.empty();
        }

        @Override
        public void writeText(String siteId, String relPath, String text) {
            throw new UnsupportedOperationException("no files in this test");
        }

        @Override
        public boolean delete(String siteId, String relPath) {
            return false;
        }

        @Override
        public boolean deleteTree(String siteId, String relDir) {
            return false;
        }

        @Override
        public List<FileEntry> list(String siteId, String relDir) {
            return List.of();
        }

        @Override
        public Optional<FileEntry> stat(String siteId, String relPath) {
            return Optional.empty();
        }
    }
}
