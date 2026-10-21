package mx.quest.searchlucene;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.ar.ArabicAnalyzer;
import org.apache.lucene.analysis.bg.BulgarianAnalyzer;
import org.apache.lucene.analysis.ca.CatalanAnalyzer;
import org.apache.lucene.analysis.cz.CzechAnalyzer;
import org.apache.lucene.analysis.da.DanishAnalyzer;
import org.apache.lucene.analysis.de.GermanAnalyzer;
import org.apache.lucene.analysis.el.GreekAnalyzer;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.es.SpanishAnalyzer;
import org.apache.lucene.analysis.eu.BasqueAnalyzer;
import org.apache.lucene.analysis.fi.FinnishAnalyzer;
import org.apache.lucene.analysis.fr.FrenchAnalyzer;
import org.apache.lucene.analysis.gl.GalicianAnalyzer;
import org.apache.lucene.analysis.hi.HindiAnalyzer;
import org.apache.lucene.analysis.hu.HungarianAnalyzer;
import org.apache.lucene.analysis.hy.ArmenianAnalyzer;
import org.apache.lucene.analysis.id.IndonesianAnalyzer;
import org.apache.lucene.analysis.it.ItalianAnalyzer;
import org.apache.lucene.analysis.lt.LithuanianAnalyzer;
import org.apache.lucene.analysis.lv.LatvianAnalyzer;
import org.apache.lucene.analysis.nl.DutchAnalyzer;
import org.apache.lucene.analysis.no.NorwegianAnalyzer;
import org.apache.lucene.analysis.pt.PortugueseAnalyzer;
import org.apache.lucene.analysis.ro.RomanianAnalyzer;
import org.apache.lucene.analysis.ru.RussianAnalyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.sv.SwedishAnalyzer;
import org.apache.lucene.analysis.th.ThaiAnalyzer;
import org.apache.lucene.analysis.tr.TurkishAnalyzer;

/**
 * Chooses a Lucene {@link Analyzer} per content/query language, so a site's pages are tokenized <b>and
 * stemmed</b> for the language they are written in — a query for {@code "running"} matches a page that says
 * {@code "run"}, a Spanish query for {@code "corriendo"} matches {@code "correr"}, and so on. Where Lucene ships
 * a language-specific analyzer (which bundles that language's stopword list and stemmer), that analyzer is used;
 * for any language Lucene has no analyzer for — or an unknown/blank language — it falls back to
 * {@link StandardAnalyzer} (tokenize + lowercase, no stemming), which is always correct, just less clever.
 *
 * <p><b>Why the same analyzer must be used at index and query time:</b> stemming is an analysis-time transform.
 * If the index stemmed {@code "running" → "run"} but the query analyzer did not, the query term {@code "running"}
 * would never match the indexed term {@code "run"}. This class is the single source of truth for "the analyzer
 * for language X", called from both the indexer and the searcher, so the two can never drift.
 *
 * <p>Analyzers are <b>immutable and thread-safe</b> once built, and building one is not free (it reads a
 * stopword resource), so instances are cached per language key. The lookup key is the lowercased ISO language
 * code (e.g. {@code "en"}, {@code "es"}); a full {@link Locale} is reduced to its language.
 */
final class LocaleAnalyzers {

    /** Language code → a factory for that language's analyzer. Absent code ⇒ StandardAnalyzer fallback. */
    private static final Map<String, Supplier<Analyzer>> BY_LANGUAGE = Map.ofEntries(
            Map.entry("ar", ArabicAnalyzer::new),
            Map.entry("bg", BulgarianAnalyzer::new),
            Map.entry("ca", CatalanAnalyzer::new),
            Map.entry("cs", CzechAnalyzer::new),
            Map.entry("da", DanishAnalyzer::new),
            Map.entry("de", GermanAnalyzer::new),
            Map.entry("el", GreekAnalyzer::new),
            Map.entry("en", EnglishAnalyzer::new),
            Map.entry("es", SpanishAnalyzer::new),
            Map.entry("eu", BasqueAnalyzer::new),
            Map.entry("fi", FinnishAnalyzer::new),
            Map.entry("fr", FrenchAnalyzer::new),
            Map.entry("gl", GalicianAnalyzer::new),
            Map.entry("hi", HindiAnalyzer::new),
            Map.entry("hu", HungarianAnalyzer::new),
            Map.entry("hy", ArmenianAnalyzer::new),
            Map.entry("id", IndonesianAnalyzer::new),
            Map.entry("it", ItalianAnalyzer::new),
            Map.entry("lt", LithuanianAnalyzer::new),
            Map.entry("lv", LatvianAnalyzer::new),
            Map.entry("nl", DutchAnalyzer::new),
            Map.entry("no", NorwegianAnalyzer::new),
            Map.entry("pt", PortugueseAnalyzer::new),
            Map.entry("ro", RomanianAnalyzer::new),
            Map.entry("ru", RussianAnalyzer::new),
            Map.entry("sv", SwedishAnalyzer::new),
            Map.entry("th", ThaiAnalyzer::new),
            Map.entry("tr", TurkishAnalyzer::new));

    /** Built analyzers, cached by language key. Analyzers are thread-safe, so caching is a pure win. */
    private final ConcurrentMap<String, Analyzer> cache = new ConcurrentHashMap<>();

    /**
     * The analyzer for {@code locale}'s language — a stemming language analyzer where Lucene ships one, else
     * {@link StandardAnalyzer}. Never {@code null}. The returned analyzer is shared and must not be closed by the
     * caller (the engine owns lifecycle via {@link #close()}).
     *
     * @param locale the content/query locale; a {@code null} locale is treated as the standard fallback
     */
    Analyzer forLocale(Locale locale) {
        String lang = (locale == null) ? "" : locale.getLanguage().toLowerCase(Locale.ROOT);
        return forLanguage(lang);
    }

    /** As {@link #forLocale(Locale)} but keyed directly by an ISO language code (blank/unknown ⇒ standard). */
    Analyzer forLanguage(String language) {
        String lang = (language == null) ? "" : language.toLowerCase(Locale.ROOT);
        return cache.computeIfAbsent(lang, k -> {
            Supplier<Analyzer> factory = BY_LANGUAGE.get(k);
            return (factory != null) ? factory.get() : new StandardAnalyzer();
        });
    }

    /** Whether a genuine language analyzer (with stemming) exists for {@code language} — for the console/tests. */
    static boolean hasLanguageAnalyzer(String language) {
        return language != null && BY_LANGUAGE.containsKey(language.toLowerCase(Locale.ROOT));
    }

    /** Release every cached analyzer (called when the engine is disposed). Idempotent. */
    void close() {
        cache.values().forEach(Analyzer::close);
        cache.clear();
    }
}
