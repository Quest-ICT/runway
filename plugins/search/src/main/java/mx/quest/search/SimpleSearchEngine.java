package mx.quest.search;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import mx.quest.runway.spi.model.CatalogEntry;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.spi.service.PluginSettings;
import mx.quest.runway.spi.service.Sites;
import mx.quest.search.spi.SearchCorpus;
import mx.quest.search.spi.SearchHit;
import mx.quest.search.spi.SearchQuery;
import mx.quest.search.spi.SiteSearchEngine;

/**
 * The built-in {@link SiteSearchEngine}: a dependency-free full-text matcher that indexes each site's whole
 * {@link SearchCorpus} — published pages AND content-source items (the entries plugins publish, …), minus the site's search
 * exclusions — by localized <b>title, description AND body</b>. It reads each entry's indexable material through the
 * corpus, reduces it to plain text with {@link HtmlText}, and holds a small per-site index of {@link SearchDocument}s.
 * The index is dropped the moment anything that may reshape the corpus changes ({@link #invalidate}, called by the
 * plugin's {@code SiteEventListener} for every page write, content change, site lifecycle event and plugin settings
 * save) and additionally refreshed by a freshness TTL. It needs no external index and is always available, so
 * search works the moment the plugin is installed — a richer engine (e.g. {@code search-lucene}) transparently
 * supersedes it by declaring a higher {@link #priority()}.
 *
 * <h2>Scoring</h2>
 * Score is a configurable weighted term-frequency (per-site {@link SearchSiteConfig}): a term in the title is
 * worth {@link SearchSiteConfig#titleWeight()}, in the description {@link SearchSiteConfig#descriptionWeight()},
 * and per body occurrence {@link SearchSiteConfig#bodyWeight()} (body counts are capped so one keyword-stuffed
 * page can't dominate). Flat bonuses reward a page that contains <em>all</em> query terms
 * ({@link SearchSiteConfig#allTermsBonus()}) and one that contains the whole query as a phrase in the title or
 * body. The weights are data, so a site tunes ranking with no code change.
 *
 * <h2>One document per item and language, and its URL</h2>
 * Since 2.2.0 each corpus entry becomes one document <b>per language the item exists in</b>: its title, description
 * and body in that language (the body is the page as rendered there, bundle strings included), linked at
 * {@link CatalogEntry#pathFor} that language. A query searches the documents of its own language
 * ({@link SearchQuery#locale()}, the results page's language) plus, for an item that does not exist in that
 * language, the item's <b>primary-locale</b> document (the site's default language when the item exists in it, else
 * its first language) — so a visitor on {@code /es/…} gets Spanish titles, snippets and links, and still finds a page
 * the site publishes in English only. A query in a language the site does not speak searches the site default's
 * documents. Before 2.2.0 only the primary-locale document existed, so every language searched default-language
 * text.
 *
 * <h2>Snippets, isolation</h2>
 * Snippets are built by {@link SearchSnippet} from the body (falling back to the description) and honour the frozen
 * {@link SearchHit} snippet contract: escape everything, wrap only the matched terms in {@code <mark>}. Each hit also
 * carries the whole words its terms matched ({@link MatchedWords}), which the target page highlights. Every read
 * is scoped to {@code query.siteId()} — the index is keyed per site, so one tenant never sees another's content.
 */
public class SimpleSearchEngine implements SiteSearchEngine {

    static final String KEY = "search/simple";

    /** Bounds the per-body term count so one keyword-stuffed page can't swamp a genuinely relevant one. */
    private static final int MAX_BODY_HITS_PER_TERM = 20;

    /** Distinct site indexes held at once (least recently used dropped first); each is small. */
    static final int MAX_SITE_INDEXES = 512;

    private final SearchCorpus corpus;
    private final Sites sites;
    private final PluginSettings settings;
    private final SearchProperties properties;

    /**
     * Per-site index cache, least recently used first. An entry is dropped by {@link #invalidate} and rebuilt lazily
     * on the next query; the freshness TTL ({@link SearchProperties#getRefreshMinutes()}) remains as the backstop for
     * changes no event announces (a file edited by hand), and {@link #reindex} drops + rebuilds eagerly.
     */
    private final Map<String, SiteIndex> indexes = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, SiteIndex> eldest) {
                    return size() > MAX_SITE_INDEXES;
                }
            });

    /**
     * Bumped by every {@link #invalidate}. A build started before an invalidation may have read the old corpus, so
     * it is served to its own caller but not cached: the next query rebuilds from the new state.
     */
    private final AtomicLong generation = new AtomicLong();

    /** One build at a time per site: concurrent first queries after a change wait for one build, not start N. */
    private final Map<String, Object> locks = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The sites whose index this thread is building. A build renders pages (kernel 1.0.0-rc6), and a page's widget
     * that searched the same site would re-enter the build and recurse; such a search answers nothing instead.
     */
    private static final ThreadLocal<Set<String>> BUILDING = ThreadLocal.withInitial(java.util.HashSet::new);

    public SimpleSearchEngine(SearchCorpus corpus, Sites sites, PluginSettings settings,
                              SearchProperties properties) {
        this.corpus = Objects.requireNonNull(corpus, "corpus");
        this.sites = Objects.requireNonNull(sites, "sites");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public List<SearchHit> search(SearchQuery query) {
        List<String> terms = terms(query.text());
        if (terms.isEmpty() || BUILDING.get().contains(query.siteId())) {
            return List.of();
        }
        SearchSiteConfig cfg = SearchSiteConfig.of(settings.site(query.siteId()));
        SiteIndex index = index(query.siteId());
        List<SearchDocument> documents = index.documents(query.locale());
        String phrase = query.text().toLowerCase(Locale.ROOT).strip();

        List<Scored> scored = new ArrayList<>();
        for (SearchDocument doc : documents) {
            double score = score(doc, terms, phrase, cfg);
            if (score > 0) {
                scored.add(new Scored(doc, score));
            }
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed()
                .thenComparing(s -> s.doc().title(), String.CASE_INSENSITIVE_ORDER));
        // The snippet and the matched words are built for the hits returned only, not for every document scored.
        List<SearchHit> hits = new ArrayList<>(Math.min(scored.size(), query.limit()));
        for (Scored s : scored.subList(0, Math.min(scored.size(), query.limit()))) {
            SearchDocument doc = s.doc();
            hits.add(new SearchHit(doc.url(), doc.title(), snippet(doc, terms, cfg), s.score(),
                    MatchedWords.in(doc, terms)));
        }
        return List.copyOf(hits);
    }

    /** A document and its score, before the cut to the query's limit. */
    private record Scored(SearchDocument doc, double score) {
    }

    /**
     * Drops the cached index for {@code siteId} and rebuilds it from current content — the SPI's manual reindex
     * hook. It also refreshes the kernel's listing first ({@link SearchCorpus#refresh}): the button exists for
     * changes no event announced (files edited by hand), and those never evicted the listing either. Returns the
     * number of items in the freshly built index (not per-language documents), so the caller can report the new size.
     */
    @Override
    public int reindex(String siteId) {
        corpus.refresh(siteId);
        invalidate(siteId);
        return index(siteId).items();
    }

    /**
     * Drops the site's cached index, so the next query rebuilds it from the current corpus. Called from the
     * plugin's {@code SiteEventListener} for every change that may reshape the corpus, after the kernel has evicted
     * its own caches — so the rebuild reads the new state.
     */
    void invalidate(String siteId) {
        generation.incrementAndGet();
        indexes.remove(siteId);
    }

    /**
     * The number of items (published, non-excluded pages and content) the built-in engine searches for a site —
     * items, not per-language documents, so the count does not grow with the number of languages.
     */
    public int documentCount(String siteId) {
        return index(siteId).items();
    }

    // --- scoring -----------------------------------------------------------------------------------

    private static double score(SearchDocument doc, List<String> terms, String phrase, SearchSiteConfig cfg) {
        double score = 0;
        int termsPresent = 0;
        for (String term : terms) {
            boolean inTitle = doc.normTitle().contains(term);
            boolean inDescription = doc.normDescription().contains(term);
            int bodyHits = Math.min(MAX_BODY_HITS_PER_TERM, count(doc.normBody(), term));
            if (inTitle) {
                score += cfg.titleWeight();
            }
            if (inDescription) {
                score += cfg.descriptionWeight();
            }
            score += (double) cfg.bodyWeight() * bodyHits;
            if (inTitle || inDescription || bodyHits > 0) {
                termsPresent++;
            }
        }
        if (score <= 0) {
            return 0;
        }
        if (termsPresent == terms.size() && terms.size() > 1) {
            score += cfg.allTermsBonus();
        }
        if (!phrase.isBlank()) {
            if (doc.normTitle().contains(phrase)) {
                score += cfg.phraseTitleBonus();
            }
            if (doc.normBody().contains(phrase)) {
                score += cfg.phraseBodyBonus();
            }
        }
        return score;
    }

    private static int count(String haystack, String needle) {
        if (needle.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    private static List<String> terms(String text) {
        return List.of(text.toLowerCase(Locale.ROOT).trim().split("\\s+")).stream()
                .filter(s -> !s.isBlank()).distinct().toList();
    }

    /**
     * The highlighted snippet for a hit: built from the body when the body contains a term, else from the
     * description (which is where a metadata-only match's context lives), honouring the frozen snippet contract
     * via {@link SearchSnippet}.
     */
    private static String snippet(SearchDocument doc, List<String> terms, SearchSiteConfig cfg) {
        boolean bodyHasTerm = terms.stream().anyMatch(t -> doc.normBody().contains(t));
        String source = bodyHasTerm || doc.description().isBlank() ? doc.body() : doc.description();
        if (source.isBlank()) {
            source = doc.description().isBlank() ? doc.body() : doc.description();
        }
        return SearchSnippet.build(source, terms, cfg.snippetLength());
    }

    // --- index -------------------------------------------------------------------------------------

    /** The site's index, rebuilt if absent or stale (older than the configured freshness TTL). */
    private SiteIndex index(String siteId) {
        SiteIndex cached = indexes.get(siteId);
        if (cached != null && !cached.isStale(properties.freshnessMillis())) {
            return cached;
        }
        synchronized (locks.computeIfAbsent(siteId, k -> new Object())) {
            cached = indexes.get(siteId);
            if (cached != null && !cached.isStale(properties.freshnessMillis())) {
                return cached; // built by the query this one waited for
            }
            long before = generation.get();
            SiteIndex fresh;
            BUILDING.get().add(siteId);
            try {
                fresh = build(siteId);
            } finally {
                BUILDING.get().remove(siteId);
            }
            synchronized (indexes) {
                if (generation.get() == before) {
                    indexes.put(siteId, fresh);
                }
            }
            return fresh;
        }
    }

    /**
     * Builds the per-site index over the {@link SearchCorpus} — every published, non-excluded item, pages and
     * content-source items alike — one {@link SearchDocument} per item and language it exists in, then one view per
     * language (see the class comment). An unknown site has an empty index.
     */
    private SiteIndex build(String siteId) {
        SiteView site = sites.find(siteId).orElse(null);
        if (site == null) {
            return new SiteIndex(Map.of(), "", 0, System.currentTimeMillis());
        }
        Locale siteDefault = site.defaultLocale();
        Map<String, List<SearchDocument>> own = new LinkedHashMap<>();
        List<Item> items = new ArrayList<>();
        for (CatalogEntry entry : corpus.corpus(siteId)) {
            Locale primary = entry.ref().primaryLocale(siteDefault);
            Set<String> languages = new LinkedHashSet<>();
            SearchDocument primaryDoc = null;
            for (Locale locale : itemLocales(entry, siteDefault)) {
                if (!languages.add(locale.getLanguage())) {
                    continue;
                }
                SearchDocument doc = document(siteId, entry, locale);
                own.computeIfAbsent(locale.getLanguage(), k -> new ArrayList<>()).add(doc);
                if (locale.getLanguage().equals(primary.getLanguage())) {
                    primaryDoc = doc;
                }
            }
            if (primaryDoc == null) {
                primaryDoc = document(siteId, entry, primary);
            }
            items.add(new Item(primaryDoc, Set.copyOf(languages)));
        }
        Set<String> viewLanguages = new LinkedHashSet<>();
        viewLanguages.add(siteDefault.getLanguage());
        site.locales().forEach(l -> viewLanguages.add(l.getLanguage()));
        viewLanguages.addAll(own.keySet());
        Map<String, List<SearchDocument>> views = new HashMap<>();
        for (String language : viewLanguages) {
            List<SearchDocument> view = new ArrayList<>(own.getOrDefault(language, List.of()));
            for (Item item : items) {
                if (!item.languages().contains(language)) {
                    view.add(item.primary());
                }
            }
            views.put(language, List.copyOf(view));
        }
        return new SiteIndex(Map.copyOf(views), siteDefault.getLanguage(), items.size(), System.currentTimeMillis());
    }

    /** The languages an item exists in, in its declared order; an empty list means the site default only. */
    private static List<Locale> itemLocales(CatalogEntry entry, Locale siteDefault) {
        List<Locale> locales = entry.ref().locales();
        return locales.isEmpty() ? List.of(siteDefault) : locales;
    }

    /** The item's document in {@code locale}: title, description and body in that language, at its address there. */
    private SearchDocument document(String siteId, CatalogEntry entry, Locale locale) {
        String title = localized(entry.ref().title(), locale, entry.ref().id());
        String description = localized(entry.ref().description(), locale, "");
        // Raw indexable material (empty when a source doesn't offer it, or fails) — stripped of markup here.
        String body = HtmlText.toText(corpus.indexableText(siteId, entry, locale));
        return SearchDocument.of(entry.compositeId(), entry.pathFor(locale), title, description, body);
    }

    /** An item's primary-locale document and the languages it has documents in, while a view is assembled. */
    private record Item(SearchDocument primary, Set<String> languages) {
    }

    /** A map value for {@code locale}'s language, else any present value, else {@code fallback}. */
    private static String localized(Map<String, String> byLocale, Locale locale, String fallback) {
        String v = byLocale.get(locale.getLanguage());
        if (v == null || v.isBlank()) {
            v = byLocale.values().stream().filter(x -> x != null && !x.isBlank()).findFirst().orElse(null);
        }
        return (v == null || v.isBlank()) ? fallback : v;
    }

    /**
     * One site's built index: the documents each language searches (its own plus the primary-locale documents of
     * items it lacks), the site default's language, the number of items, and the wall-clock time it was built (for
     * the freshness TTL).
     */
    private record SiteIndex(Map<String, List<SearchDocument>> views, String defaultLanguage, int items,
                             long builtAtMillis) {

        /** The documents a query in {@code locale} searches; the site default's when the site lacks that language. */
        List<SearchDocument> documents(Locale locale) {
            List<SearchDocument> view = views.get(locale.getLanguage());
            return view != null ? view : views.getOrDefault(defaultLanguage, List.of());
        }

        boolean isStale(long freshnessMillis) {
            return System.currentTimeMillis() - builtAtMillis >= freshnessMillis;
        }
    }
}
