package mx.quest.searchlucene;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import mx.quest.runway.spi.model.CatalogEntry;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.spi.service.PluginSettings;
import mx.quest.runway.spi.service.Sites;
import mx.quest.search.spi.SearchCorpus;
import mx.quest.search.spi.SearchHit;
import mx.quest.search.spi.SearchQuery;
import mx.quest.search.spi.SiteSearchEngine;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.ConstantScoreQuery;
import org.apache.lucene.search.FuzzyQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.PhraseQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.uhighlight.DefaultPassageFormatter;
import org.apache.lucene.search.uhighlight.UnifiedHighlighter;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.MMapDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Lucene-backed {@link SiteSearchEngine} — the {@code search-lucene} plugin's contribution to the {@code search}
 * plugin's engine SPI. It builds a per-site full-text index over the search plugin's {@link SearchCorpus} — every
 * published page and content-source item (the entries plugins publish, …) the site has not excluded from search — indexing each
 * item's <b>title</b>,
 * <b>description</b> and <b>body text</b> (extracted from the item's indexable material with {@link HtmlText})
 * and answers a
 * query with a <b>tiered, constant-score</b> relevance model layered on top of Lucene's BM25 term matching:
 *
 * <ol>
 *   <li><b>any-terms</b> — each analyzed query term as a {@code SHOULD} {@link TermQuery} across title (boosted),
 *       description (boosted) and body, plus optional typo-tolerant {@link FuzzyQuery} clauses. This is the
 *       BM25-scored base layer that separates documents <em>within</em> a tier.</li>
 *   <li><b>near phrase (body)</b> — the query terms as a {@link PhraseQuery} with a small slop, a
 *       {@code ConstantScoreQuery} worth {@code tierNearPhrase}: proximity beats scattered terms.</li>
 *   <li><b>exact phrase (body)</b> — the query as an exact ({@code slop=0}) body phrase, worth
 *       {@code tierPhraseBody}.</li>
 *   <li><b>exact phrase (title)</b> — the query as an exact title phrase, worth {@code tierPhraseTitle}: a page
 *       whose <em>title</em> is the query is the strongest possible match.</li>
 *   <li><b>all-terms-anywhere</b> — a {@code ConstantScoreQuery} worth {@code tierAllTerms} that fires only when
 *       every query term appears somewhere in the document, lifting complete matches above partial ones.</li>
 * </ol>
 *
 * <p>The tiers are additive {@code SHOULD} clauses, so a hit's score is the sum of the tiers it satisfies — an
 * exact-title-phrase hit outranks a near-phrase hit, which outranks a scattered-terms hit, deterministically.
 * Analysis (tokenization + <b>stemming</b>) is <b>per-locale</b> via {@link LocaleAnalyzers}, so a query matches
 * a stemmed surface form (an English query {@code running} matches {@code run}); the <em>same</em> analyzer is
 * used at index and query time so the two never drift. Result snippets are <b>query-focused and highlighted</b>:
 * {@link UnifiedHighlighter} wraps the matched terms in {@code <mark>…</mark>} within the best-matching body
 * passage, falling back to a plain leading snippet if highlighting yields nothing. Each hit also carries the words
 * the query matched as the page writes them ({@link MatchedForms}), which the page the result opens highlights.
 *
 * <p>The index covers the {@link SearchCorpus} the search plugin publishes — every published item, pages and
 * content-source items alike, minus the site's search exclusions. It is the same corpus the built-in engine
 * indexes, so what is searchable never depends on which engine serves the query, and the exclusion rule lives in
 * one place (the search plugin).
 *
 * <p><b>One index per language (since 3.2.0).</b> Each item is indexed once per language it exists in — its title,
 * description and body in that language (the body is the page as the kernel renders it there, bundle strings
 * included), linked at {@link CatalogEntry#pathFor} that language. A site has one index per language it speaks,
 * analyzed with that language's analyzer: the language's own documents plus, for an item that does not exist in
 * it, the item's <b>primary-locale</b> document (the site's default language when the item exists in it, else its
 * first language), so a page published in one language only is still found from every other. A query searches
 * the index of its own language ({@link SearchQuery#locale()}), with that index's analyzer; a language the site
 * does not speak searches the site default's. A fallback document is analyzed with the index's analyzer, not its
 * own language's — so it matches on exact and lightly stemmed forms, not on every inflection. Before 3.2.0 a site
 * had one index of primary-locale documents analyzed with the default language's analyzer, so every language
 * searched default-language text.
 *
 * <p>The index lives in heap ({@link ByteBuffersDirectory}) or on disk ({@link MMapDirectory}) per the app-level
 * {@link LuceneStorageProps}. It is cached per site, dropped by {@link #invalidate} — which the plugin's
 * {@code SiteEventListener} calls for every change that may reshape the corpus (a page write, a content source's
 * change, a site lifecycle event, any plugin's settings save) — rebuilt eagerly by the operator's {@link #reindex}
 * action, and additionally refreshed by a short TTL (the backstop for changes no event announces). The engine registers at {@link #priority()}
 * 100 (it supersedes the built-in engine) and is always {@link #available(String) available} — it builds on
 * demand. Per the SPI contract, {@link #search(SearchQuery)} <b>never throws</b>: any failure (I/O, a corrupt
 * index) is logged and returns no results.
 */
public class LuceneSearchEngine implements SiteSearchEngine, AutoCloseable {

    static final String KEY = "search/lucene";
    private static final Logger log = LoggerFactory.getLogger(LuceneSearchEngine.class);

    /** Indexed + query field names. */
    private static final String F_TITLE = "title";
    private static final String F_DESCRIPTION = "description";
    private static final String F_BODY = "body";
    /** Stored (display-only) field names. */
    private static final String S_URL = "s_url";
    private static final String S_TITLE = "s_title";
    private static final String S_DESCRIPTION = "s_description";
    private static final String S_BODY = "s_body";

    private static final long FRESHNESS_MILLIS = 30_000L;
    private static final int SNIPPET_MAX = 200;
    private static final int MAX_QUERY_TERMS = 32;
    /** Slop for the "near phrase" tier — terms may be up to this far apart / lightly reordered. */
    private static final int NEAR_SLOP = 1;
    /** Private markers the {@link UnifiedHighlighter} wraps hits in; rewritten to {@code <mark>}/{@code </mark>}
     *  only after the surrounding passage has been HTML-escaped, so a page's own {@code <} can't forge a tag. */
    private static final char MARK_OPEN_CH = '\u0001';
    private static final char MARK_CLOSE_CH = '\u0002';
    private static final String MARK_OPEN = String.valueOf(MARK_OPEN_CH);
    private static final String MARK_CLOSE = String.valueOf(MARK_CLOSE_CH);
    /** Guards the per-site subfolder used for a disk index (path-traversal / injection safe). */
    private static final Pattern SAFE_SITE_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    /** A language subdirectory name: an ISO 639 code as {@link Locale#getLanguage()} returns it. */
    private static final Pattern SAFE_LANGUAGE = Pattern.compile("[a-z]{2,8}");

    private final SearchCorpus corpus;
    private final Sites sites;
    private final PluginSettings settings;
    private final LuceneStorageProps storage;
    private final LocaleAnalyzers analyzers = new LocaleAnalyzers();

    private final Map<String, CachedIndex> cache = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    /**
     * Bumped by every {@link #invalidate}. A build that started before an invalidation may have read the old corpus,
     * so it is kept only as already-stale: it answers the query that built it, and the next query rebuilds.
     */
    private final AtomicLong generation = new AtomicLong();
    /** Site index folders already checked for a 3.1.x index (see {@link #deleteOldLayout}). */
    private final java.util.Set<Path> oldLayoutChecked = ConcurrentHashMap.newKeySet();
    /**
     * The sites whose index this thread is building. A build renders pages, and a page's widget that searched
     * the same site would re-enter the build (the per-site lock is reentrant) and recurse; such a search answers
     * nothing instead.
     */
    private static final ThreadLocal<java.util.Set<String>> BUILDING = ThreadLocal.withInitial(java.util.HashSet::new);

    /**
     * @param corpus   what to index: the search plugin's corpus (the content listing minus the site's exclusions)
     * @param sites    the kernel's site lookup (a site's default locale chooses the analyzer)
     * @param settings this plugin's per-site settings (the ranking knobs), read per query
     * @param storage  the app-level storage settings (heap or disk)
     */
    public LuceneSearchEngine(SearchCorpus corpus, Sites sites, PluginSettings settings, LuceneStorageProps storage) {
        this.corpus = Objects.requireNonNull(corpus, "corpus");
        this.sites = Objects.requireNonNull(sites, "sites");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    /** One language's index: its directory, the analyzer used to build it (reused at query time) and its size. */
    private record LanguageIndex(Directory dir, Analyzer analyzer, int documents) {
    }

    /**
     * A built per-site index: one {@link LanguageIndex} per language the site speaks, the site default's language
     * (the index a query in another language searches), the number of items indexed, and when it was built.
     */
    private record CachedIndex(Map<String, LanguageIndex> languages, String defaultLanguage, int items,
                               long builtAtMillis) {

        /** The same index, marked as already past its freshness window. */
        CachedIndex stale() {
            return new CachedIndex(languages, defaultLanguage, items, 0L);
        }

        /** The index a query in {@code locale} searches; the site default's when the site lacks that language. */
        LanguageIndex forQuery(Locale locale) {
            LanguageIndex index = languages.get(locale.getLanguage());
            return index != null ? index : languages.get(defaultLanguage);
        }

        void close() {
            languages.values().forEach(l -> closeQuietly(l.dir()));
        }
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public int priority() {
        return 100; // supersede the built-in engine wherever this plugin is installed
    }

    @Override
    public List<SearchHit> search(SearchQuery query) {
        if (BUILDING.get().contains(query.siteId())) {
            return List.of(); // a search from inside this site's own index build (see BUILDING)
        }
        LanguageIndex index;
        try {
            index = index(query.siteId()).forQuery(query.locale());
        } catch (RuntimeException e) {
            log.warn("lucene index unavailable for site '{}': {}", query.siteId(), e.toString());
            return List.of();
        }
        if (index == null || index.documents() == 0) {
            return List.of();
        }
        List<String> terms = analyze(index.analyzer(), query.text());
        if (terms.isEmpty()) {
            return List.of();
        }
        RankingConfig ranking = ranking(query.siteId());
        Query q = buildQuery(terms, ranking);
        try (DirectoryReader reader = DirectoryReader.open(index.dir())) {
            IndexSearcher searcher = new IndexSearcher(reader);
            TopDocs top = searcher.search(q, Math.max(1, query.limit()));
            String[] highlights = highlight(searcher, index.analyzer(), q, top);
            StoredFields stored = searcher.storedFields();
            MatchedForms matched = MatchedForms.forQuery(index.analyzer(), terms, ranking.fuzzy());
            List<SearchHit> hits = new ArrayList<>(top.scoreDocs.length);
            for (int i = 0; i < top.scoreDocs.length; i++) {
                ScoreDoc sd = top.scoreDocs[i];
                Document d = stored.document(sd.doc);
                String snippet = snippet(highlights[i], d);
                List<String> forms = matched.in(
                        List.of(nonNull(d.get(S_TITLE)), nonNull(d.get(S_DESCRIPTION)), nonNull(d.get(S_BODY))));
                hits.add(new SearchHit(d.get(S_URL), d.get(S_TITLE), snippet, sd.score, forms));
            }
            return List.copyOf(hits);
        } catch (IOException | RuntimeException e) {
            log.warn("lucene search failed for site '{}': {}", query.siteId(), e.toString());
            return List.of(); // an SPI never throws for a query; fall back to no results
        }
    }

    /**
     * The SPI's manual-reindex hook: drops the cached index and rebuilds it <b>now</b> from current content,
     * returning the fresh document count — so the operator's reindex action rebuilds this engine too, not only
     * the built-in one. A failed rebuild logs and reports {@code 0}, per the SPI's never-throw posture.
     */
    @Override
    public int reindex(String siteId) {
        corpus.refresh(siteId); // out-of-band edits fire no event; refresh the shared listing before rebuilding
        invalidate(siteId);
        return indexedDocuments(siteId);
    }

    // ------------------------------------------------------------------------------------------------- console

    /**
     * The number of items (pages and content) currently in this site's index — items, not per-language documents
     * (builds/refreshes it if needed) — for the console.
     */
    public int indexedDocuments(String siteId) {
        try {
            return index(siteId).items();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** The effective per-tenant title boost (for the console). */
    public float effectiveTitleBoost(String siteId) {
        return ranking(siteId).titleBoost();
    }

    /** The active storage mode ({@code heap}/{@code disk}) — for the console. */
    public String storageMode() {
        return storage.isDisk() ? "disk" : "heap";
    }

    // ----------------------------------------------------------------------------------------------- lifecycle

    /**
     * Drops the site's cached index, so the next query rebuilds it from the current corpus. Called from the plugin's
     * {@code SiteEventListener} for every change that may reshape the corpus, after the kernel has evicted its own
     * caches — so the rebuild reads the new state.
     */
    void invalidate(String siteId) {
        generation.incrementAndGet();
        evict(siteId);
    }

    private void evict(String siteId) {
        CachedIndex removed = cache.remove(siteId);
        if (removed != null) {
            removed.close();
        }
    }

    /** Release every cached directory and analyzer (Spring calls this on context shutdown). */
    @Override
    public void close() {
        cache.values().forEach(CachedIndex::close);
        cache.clear();
        analyzers.close();
    }

    // --------------------------------------------------------------------------------------------- index build

    private CachedIndex index(String siteId) {
        long now = System.currentTimeMillis();
        CachedIndex cached = cache.get(siteId);
        if (cached != null && now - cached.builtAtMillis() < FRESHNESS_MILLIS) {
            return cached;
        }
        synchronized (locks.computeIfAbsent(siteId, k -> new Object())) {
            cached = cache.get(siteId);
            if (cached != null && System.currentTimeMillis() - cached.builtAtMillis() < FRESHNESS_MILLIS) {
                return cached;
            }
            long started = generation.get();
            CachedIndex fresh;
            BUILDING.get().add(siteId);
            try {
                fresh = build(siteId);
            } finally {
                BUILDING.get().remove(siteId);
            }
            if (generation.get() != started) {
                fresh = fresh.stale(); // a change arrived mid-build: this one may hold the old corpus
            }
            CachedIndex prev = cache.put(siteId, fresh);
            if (prev != null) {
                prev.close();
            }
            return fresh;
        }
    }

    /** One item's indexed fields in one language. */
    private record IndexedText(String url, String title, String description, String body) {
    }

    /** An item's primary-locale fields and the languages it has its own documents in, while views are assembled. */
    private record Item(IndexedText primary, java.util.Set<String> languages) {
    }

    /**
     * Builds every language index of a site (see the class comment). Each item's text is read once per language it
     * exists in and reused by every index that holds it. An unknown site gets one empty index for English.
     */
    private CachedIndex build(String siteId) {
        SiteView site = sites.find(siteId).orElse(null);
        Locale siteDefault = site == null ? Locale.ENGLISH : site.defaultLocale();
        Map<String, List<IndexedText>> own = new java.util.LinkedHashMap<>();
        List<Item> items = new ArrayList<>();
        try {
            for (CatalogEntry entry : corpus.corpus(siteId)) { // empty for an unknown site
                Locale primary = entry.ref().primaryLocale(siteDefault);
                java.util.Set<String> languages = new java.util.LinkedHashSet<>();
                IndexedText primaryText = null;
                List<Locale> locales = entry.ref().locales().isEmpty() ? List.of(siteDefault) : entry.ref().locales();
                for (Locale locale : locales) {
                    if (!languages.add(locale.getLanguage())) {
                        continue;
                    }
                    IndexedText text = text(siteId, entry, locale);
                    own.computeIfAbsent(locale.getLanguage(), k -> new ArrayList<>()).add(text);
                    if (locale.getLanguage().equals(primary.getLanguage())) {
                        primaryText = text;
                    }
                }
                items.add(new Item(primaryText != null ? primaryText : text(siteId, entry, primary),
                        java.util.Set.copyOf(languages)));
            }
        } catch (RuntimeException e) {
            log.warn("lucene corpus read failed for site '{}': {}", siteId, e.toString());
        }
        java.util.Set<String> viewLanguages = new java.util.LinkedHashSet<>();
        viewLanguages.add(siteDefault.getLanguage());
        if (site != null) {
            site.locales().forEach(l -> viewLanguages.add(l.getLanguage()));
        }
        viewLanguages.addAll(own.keySet());
        Map<String, LanguageIndex> indexes = new java.util.HashMap<>();
        for (String language : viewLanguages) {
            List<IndexedText> view = new ArrayList<>(own.getOrDefault(language, List.of()));
            for (Item item : items) {
                if (!item.languages().contains(language)) {
                    view.add(item.primary());
                }
            }
            indexes.put(language, write(siteId, language, view));
        }
        return new CachedIndex(Map.copyOf(indexes), siteDefault.getLanguage(), items.size(),
                System.currentTimeMillis());
    }

    /** The item's fields in {@code locale}: title, description and body in that language, at its address there. */
    private IndexedText text(String siteId, CatalogEntry entry, Locale locale) {
        String title = localized(entry.ref().title(), locale, entry.ref().id());
        String description = localized(entry.ref().description(), locale, "");
        String body = HtmlText.toText(corpus.indexableText(siteId, entry, locale));
        return new IndexedText(entry.pathFor(locale), title, description, body);
    }

    /** Writes one language's index with that language's analyzer; a failed write leaves what was added so far. */
    private LanguageIndex write(String siteId, String language, List<IndexedText> texts) {
        Analyzer analyzer = analyzers.forLanguage(language);
        Directory dir;
        try {
            dir = newDirectory(siteId, language);
        } catch (IOException e) {
            log.warn("lucene directory init failed for site '{}' ({}), falling back to heap: {}", siteId, language,
                    e.toString());
            dir = new ByteBuffersDirectory();
        }
        int docs = 0;
        try (IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig(analyzer))) {
            writer.deleteAll(); // rebuild-from-scratch semantics for a disk directory that already had content
            for (IndexedText text : texts) {
                Document doc = new Document();
                doc.add(new TextField(F_TITLE, text.title(), Field.Store.NO));
                doc.add(new TextField(F_DESCRIPTION, text.description(), Field.Store.NO));
                // Body is BOTH analyzed (for matching) and stored (so the highlighter can re-analyze the source).
                doc.add(new TextField(F_BODY, text.body(), Field.Store.YES));
                doc.add(new StoredField(S_URL, text.url()));
                doc.add(new StoredField(S_TITLE, text.title()));
                doc.add(new StoredField(S_DESCRIPTION, text.description()));
                doc.add(new StoredField(S_BODY, text.body()));
                writer.addDocument(doc);
                docs++;
            }
        } catch (IOException | RuntimeException e) {
            log.warn("lucene index build failed for site '{}' ({}): {}", siteId, language, e.toString());
        }
        return new LanguageIndex(dir, analyzer, docs);
    }

    /**
     * A directory for one language of {@code siteId}: an in-memory {@link ByteBuffersDirectory} in heap mode, or a
     * memory-mapped {@link MMapDirectory} under {@code indexRoot/<siteId>/<language>} in disk mode. The site id is
     * validated against {@link #SAFE_SITE_ID}, the language against {@link #SAFE_LANGUAGE}, and the resolved path is
     * asserted to stay under the (normalized, absolute) index root, so a crafted id can never escape the index tree
     * — even though site ids are already path-safe upstream, storage paths get their own guard (defense in depth for
     * an operator-set root). Index files a 3.1.x build left directly under {@code indexRoot/<siteId>} (one index per
     * site) are deleted, so the old index does not linger beside the new ones.
     */
    static boolean isLuceneFile(String name) {
        return name.startsWith(org.apache.lucene.index.IndexFileNames.SEGMENTS) || name.equals("write.lock")
                || org.apache.lucene.index.IndexFileNames.CODEC_FILE_PATTERN.matcher(name).matches();
    }

    private Directory newDirectory(String siteId, String language) throws IOException {
        if (!storage.isDisk()) {
            return new ByteBuffersDirectory();
        }
        if (!SAFE_SITE_ID.matcher(siteId).matches()) {
            throw new IOException("unsafe site id for a disk index: '" + siteId + "'");
        }
        if (!SAFE_LANGUAGE.matcher(language).matches()) {
            throw new IOException("unsafe language for a disk index: '" + language + "'");
        }
        Path root = Path.of(storage.getIndexRoot()).toAbsolutePath().normalize();
        Path siteDir = root.resolve(siteId).normalize();
        Path languageDir = siteDir.resolve(language).normalize();
        if (!languageDir.startsWith(root) || !languageDir.getParent().equals(siteDir)) {
            throw new IOException("index path escapes the index root: " + languageDir);
        }
        Files.createDirectories(languageDir);
        deleteOldLayout(siteDir);
        return new MMapDirectory(languageDir);
    }

    /**
     * Deletes a 3.1.x single index left directly under a site's index directory — only files Lucene names
     * ({@code segments*}, {@code write.lock}, codec files such as {@code _0.cfs}), so an {@code indexRoot} pointed by
     * mistake at a folder holding other files loses none of them — once per site per process.
     */
    private void deleteOldLayout(Path siteDir) {
        if (!oldLayoutChecked.add(siteDir)) {
            return;
        }
        try (java.util.stream.Stream<Path> files = Files.list(siteDir)) {
            files.filter(Files::isRegularFile).filter(f -> isLuceneFile(f.getFileName().toString())).forEach(file -> {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                    // a file that cannot be deleted is harmless: no index reads it any more
                }
            });
        } catch (IOException ignored) {
            // same: leftovers are never read
        }
    }

    // --------------------------------------------------------------------------------------------- query build

    /**
     * Composes the tiered constant-score query from the analyzed terms and the site's ranking config. See the
     * class doc for the tier semantics; the tiers are additive {@code SHOULD} clauses so a document's score is
     * the sum of the tiers it matches.
     */
    Query buildQuery(List<String> terms, RankingConfig cfg) {
        BooleanQuery.Builder root = new BooleanQuery.Builder();

        // Tier 1: any-terms (BM25-scored base) — each term across the three fields, with per-field boosts, plus
        // optional fuzzy variants. This layer separates documents WITHIN the higher constant-score tiers.
        BooleanQuery.Builder anyTerms = new BooleanQuery.Builder();
        for (String term : terms) {
            anyTerms.add(new BoostQuery(new TermQuery(new Term(F_TITLE, term)), cfg.titleBoost()),
                    BooleanClause.Occur.SHOULD);
            anyTerms.add(new BoostQuery(new TermQuery(new Term(F_DESCRIPTION, term)), cfg.descriptionBoost()),
                    BooleanClause.Occur.SHOULD);
            anyTerms.add(new TermQuery(new Term(F_BODY, term)), BooleanClause.Occur.SHOULD);
            if (cfg.fuzzy()) {
                int edits = maxEdits(term);
                if (edits > 0) {
                    anyTerms.add(new BoostQuery(fuzzy(F_BODY, term, edits), cfg.fuzzyBoost()),
                            BooleanClause.Occur.SHOULD);
                    anyTerms.add(new BoostQuery(fuzzy(F_TITLE, term, edits), cfg.fuzzyBoost()),
                            BooleanClause.Occur.SHOULD);
                }
            }
        }
        root.add(anyTerms.build(), BooleanClause.Occur.SHOULD);

        // Tiers 2–4: phrase tiers (only meaningful for a multi-term query; a single term IS its own phrase, so
        // the phrase tiers would just double-count — skip them for one term).
        if (terms.size() > 1) {
            root.add(constant(phrase(F_TITLE, terms, 0), cfg.tierPhraseTitle()), BooleanClause.Occur.SHOULD);
            root.add(constant(phrase(F_BODY, terms, 0), cfg.tierPhraseBody()), BooleanClause.Occur.SHOULD);
            root.add(constant(phrase(F_BODY, terms, NEAR_SLOP), cfg.tierNearPhrase()), BooleanClause.Occur.SHOULD);
        }

        // Tier 5: all-terms-anywhere — every term must appear in some field; a constant lift for complete matches.
        root.add(constant(allTermsAnywhere(terms), cfg.tierAllTerms()), BooleanClause.Occur.SHOULD);

        return root.build();
    }

    /** Wraps {@code q} so it contributes a flat, position-independent {@code score} (the tier's constant). */
    private static Query constant(Query q, float score) {
        return new BoostQuery(new ConstantScoreQuery(q), score);
    }

    /** A {@link PhraseQuery} over {@code field} for the ordered {@code terms}, allowing {@code slop} reordering. */
    private static Query phrase(String field, List<String> terms, int slop) {
        PhraseQuery.Builder b = new PhraseQuery.Builder().setSlop(slop);
        for (String term : terms) {
            b.add(new Term(field, term));
        }
        return b.build();
    }

    /** A {@code MUST}-across-fields query: every term must match in title, description or body (in any position). */
    private static Query allTermsAnywhere(List<String> terms) {
        BooleanQuery.Builder all = new BooleanQuery.Builder();
        for (String term : terms) {
            BooleanQuery.Builder anyField = new BooleanQuery.Builder();
            anyField.add(new TermQuery(new Term(F_TITLE, term)), BooleanClause.Occur.SHOULD);
            anyField.add(new TermQuery(new Term(F_DESCRIPTION, term)), BooleanClause.Occur.SHOULD);
            anyField.add(new TermQuery(new Term(F_BODY, term)), BooleanClause.Occur.SHOULD);
            all.add(anyField.build(), BooleanClause.Occur.MUST);
        }
        return all.build();
    }

    /** A typo-tolerant {@link FuzzyQuery}: {@code edits} max, 1-char prefix fixed, 50 expansions, transpositions. */
    private static FuzzyQuery fuzzy(String field, String term, int edits) {
        return new FuzzyQuery(new Term(field, term), edits, 1, 50, true);
    }

    /** The Levenshtein edit budget for a term: none for very short terms, 1 for short, 2 for longer — so a fuzzy
     *  match never turns a 3-letter word into an unrelated one. */
    static int maxEdits(String term) {
        int len = term.length();
        if (len <= 2) {
            return 0;
        }
        return (len <= 5) ? 1 : 2;
    }

    // --------------------------------------------------------------------------------------------- highlighting

    /**
     * Query-focused highlighting over the stored body field: {@link UnifiedHighlighter} finds the best passage
     * for each top doc and wraps the matched terms in the private markers (rewritten to {@code <mark>} later).
     * Returns one snippet per top doc (aligned by index), each possibly {@code null} when the doc has no
     * highlightable body. Any highlighter failure degrades to an all-{@code null} array — the caller then falls
     * back to a plain leading snippet, so highlighting never breaks a search.
     */
    private static String[] highlight(IndexSearcher searcher, Analyzer analyzer, Query query, TopDocs top) {
        if (top.scoreDocs.length == 0) {
            return new String[0];
        }
        try {
            UnifiedHighlighter highlighter = UnifiedHighlighter.builder(searcher, analyzer)
                    .withFormatter(new DefaultPassageFormatter(MARK_OPEN, MARK_CLOSE, " … ", false))
                    .build();
            return highlighter.highlight(F_BODY, query, top, 1);
        } catch (IOException | RuntimeException e) {
            log.debug("lucene highlighting failed, falling back to plain snippets: {}", e.toString());
            return new String[top.scoreDocs.length];
        }
    }

    /**
     * The snippet for a hit: prefer the highlighter's query-focused, {@code <mark>}-wrapped passage; if there is
     * none, fall back to the page description, else a leading slice of the body — always plain (no markers). The
     * highlighted passage is produced by first HTML-escaping the raw passage, THEN turning the private markers
     * into real {@code <mark>} tags, so page content can never inject markup while the highlight tags survive.
     */
    private static String snippet(String highlighted, Document doc) {
        if (highlighted != null && (highlighted.indexOf(MARK_OPEN_CH) >= 0)) {
            return markup(highlighted);
        }
        String description = doc.get(S_DESCRIPTION);
        String base = (description != null && !description.isBlank()) ? description : doc.get(S_BODY);
        if (base == null || base.isBlank()) {
            return "";
        }
        base = base.strip();
        return base.length() <= SNIPPET_MAX ? base : base.substring(0, SNIPPET_MAX).stripTrailing() + "…";
    }

    /** Escapes the passage's HTML metacharacters, then rewrites the private markers to {@code <mark>} tags. */
    private static String markup(String passage) {
        String escaped = passage.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
        return escaped.replace(MARK_OPEN, "<mark>").replace(MARK_CLOSE, "</mark>").strip();
    }

    // -------------------------------------------------------------------------------------------------- shared

    /** Analyzes free text into its terms with {@code analyzer} (same transform used at index time), capped. */
    private static List<String> analyze(Analyzer analyzer, String text) {
        List<String> out = new ArrayList<>();
        try (TokenStream ts = analyzer.tokenStream(F_BODY, text)) {
            CharTermAttribute term = ts.addAttribute(CharTermAttribute.class);
            ts.reset();
            while (ts.incrementToken() && out.size() < MAX_QUERY_TERMS) {
                out.add(term.toString());
            }
            ts.end();
        } catch (IOException e) {
            return List.of();
        }
        return out;
    }

    /** This plugin's ranking knobs for the site, from its per-site settings (declared defaults applied). */
    private RankingConfig ranking(String siteId) {
        return RankingConfig.from(settings.site(siteId).values());
    }

    /** A map value for {@code locale}'s language, else any present value, else {@code fallback}. */
    private static String localized(Map<String, String> byLocale, Locale locale, String fallback) {
        String v = byLocale.get(locale.getLanguage());
        if (v == null || v.isBlank()) {
            v = byLocale.values().stream().filter(x -> x != null && !x.isBlank()).findFirst().orElse(null);
        }
        return (v == null || v.isBlank()) ? fallback : v;
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }

    private static void closeQuietly(Directory dir) {
        try {
            dir.close();
        } catch (IOException | UncheckedIOException e) {
            log.debug("failed to close a lucene directory: {}", e.toString());
        }
    }
}
