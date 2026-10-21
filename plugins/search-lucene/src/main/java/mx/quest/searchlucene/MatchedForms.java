package mx.quest.searchlucene;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import mx.quest.search.spi.SearchHit;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute;
import org.apache.lucene.search.FuzzyQuery;
import org.apache.lucene.util.automaton.ByteRunnable;
import org.apache.lucene.util.automaton.CompiledAutomaton;

/**
 * The words a query matched in one document, as they are written there, for {@link SearchHit#terms()}: the page a
 * result opens highlights these, so they must be the <em>surface</em> forms, not the analyzed terms. The query
 * {@code maestría} is the term {@code maestr} after Spanish stemming; the page says {@code maestrías}, and that is
 * the word to highlight.
 *
 * <p>Each stored text (title, description, body) is run through the same analyzer as the index, and a token whose
 * term is a query term is kept with the characters its offsets point at. When the site has typo tolerance on, a
 * token within the query's fuzzy edit distance of a term counts too, by the same rule as the fuzzy clauses
 * ({@link LuceneSearchEngine#maxEdits}, a one-character fixed prefix, transpositions). The fuzzy clauses expand to at
 * most 50 index terms, so on a large site a word within the distance may be highlighted although it did not add to
 * the score; it is still a word the query nearly spelled.
 *
 * <p>Bounded: at most {@link SearchHit#MAX_TERMS} forms, and only the first {@value #MAX_SCAN_CHARS} characters of
 * a text are read, so a very long page costs a fixed amount per hit. Any analysis failure returns what was found.
 */
final class MatchedForms {

    /** Characters of one text read per hit; the forms of a real page appear well before this. */
    static final int MAX_SCAN_CHARS = 64 * 1024;

    private final Analyzer analyzer;
    private final Set<String> exact;
    private final List<ByteRunnable> near;

    private MatchedForms(Analyzer analyzer, List<String> terms, boolean fuzzy) {
        this.analyzer = analyzer;
        this.exact = new HashSet<>(terms);
        this.near = fuzzy ? automata(terms) : List.of();
    }

    /**
     * The matcher for one query, built once and used for every hit: the fuzzy automata cost about half a millisecond
     * per term to build, so building them per hit made a long query over many hits cost seconds of CPU.
     *
     * @param analyzer the analyzer the index was built with
     * @param terms    the analyzed query terms
     * @param fuzzy    whether the site's ranking adds fuzzy clauses
     */
    static MatchedForms forQuery(Analyzer analyzer, List<String> terms, boolean fuzzy) {
        return new MatchedForms(analyzer, terms, fuzzy);
    }

    /** The forms found in one hit's stored texts, the most visible first (title, description, body). */
    List<String> in(List<String> texts) {
        Set<String> forms = new LinkedHashSet<>();
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                continue;
            }
            collect(analyzer, text.length() > MAX_SCAN_CHARS ? text.substring(0, MAX_SCAN_CHARS) : text,
                    exact, near, forms);
            if (forms.size() >= SearchHit.MAX_TERMS) {
                break;
            }
        }
        return List.copyOf(forms);
    }

    private static List<ByteRunnable> automata(List<String> terms) {
        List<ByteRunnable> out = new ArrayList<>();
        for (String term : terms) {
            int edits = LuceneSearchEngine.maxEdits(term);
            if (edits > 0) {
                CompiledAutomaton automaton = FuzzyQuery.getFuzzyAutomaton(term, edits, 1, true);
                ByteRunnable runnable = automaton.getByteRunnable();
                if (runnable != null) {
                    out.add(runnable);
                }
            }
        }
        return out;
    }

    private static void collect(Analyzer analyzer, String text, Set<String> exact, List<ByteRunnable> near,
                                Set<String> forms) {
        try (TokenStream ts = analyzer.tokenStream("body", text)) {
            CharTermAttribute term = ts.addAttribute(CharTermAttribute.class);
            OffsetAttribute offset = ts.addAttribute(OffsetAttribute.class);
            ts.reset();
            while (ts.incrementToken() && forms.size() < SearchHit.MAX_TERMS) {
                String t = term.toString();
                if (exact.contains(t) || accepted(near, t)) {
                    int start = offset.startOffset();
                    int end = offset.endOffset();
                    if (start >= 0 && end <= text.length() && start < end) {
                        forms.add(text.substring(start, end).toLowerCase(Locale.ROOT));
                    }
                }
            }
            ts.end();
        } catch (IOException | RuntimeException e) {
            // Highlighting is a convenience: keep what was found rather than fail the search.
        }
    }

    private static boolean accepted(List<ByteRunnable> near, String term) {
        if (near.isEmpty()) {
            return false;
        }
        byte[] bytes = term.getBytes(StandardCharsets.UTF_8);
        for (ByteRunnable automaton : near) {
            if (automaton.run(bytes, 0, bytes.length)) {
                return true;
            }
        }
        return false;
    }
}
