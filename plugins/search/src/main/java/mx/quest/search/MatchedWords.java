package mx.quest.search;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import mx.quest.search.spi.SearchHit;

/**
 * The words the built-in engine matched in one document, for {@link SearchHit#terms()}. The built-in engine matches
 * a query term anywhere inside a word ({@code maestr} finds {@code maestrías}), so the word to highlight on the
 * target page is the whole word around each occurrence, not the term. Reads the lowercased fields of a
 * {@link SearchDocument}, title first, so the words a visitor sees first are the ones kept when the cap is reached.
 */
final class MatchedWords {

    /** Occurrences of one term examined per field; enough to collect the distinct forms of a real page. */
    private static final int MAX_OCCURRENCES_PER_TERM = 200;

    private MatchedWords() {
    }

    /** The distinct whole words containing any of {@code terms}, in field order, at most {@link SearchHit#MAX_TERMS}. */
    static List<String> in(SearchDocument doc, List<String> terms) {
        Set<String> words = new LinkedHashSet<>();
        for (String field : List.of(doc.normTitle(), doc.normDescription(), doc.normBody())) {
            for (String term : terms) {
                collect(field, term, words);
                if (words.size() >= SearchHit.MAX_TERMS) {
                    return List.copyOf(words);
                }
            }
        }
        return List.copyOf(words);
    }

    private static void collect(String text, String term, Set<String> words) {
        if (term.isEmpty()) {
            return;
        }
        int seen = 0;
        for (int i = text.indexOf(term); i >= 0 && seen < MAX_OCCURRENCES_PER_TERM;
                i = text.indexOf(term, i + term.length())) {
            seen++;
            int start = i;
            while (start > 0 && isWordChar(text.charAt(start - 1))) {
                start--;
            }
            int end = i + term.length();
            while (end < text.length() && isWordChar(text.charAt(end))) {
                end++;
            }
            if (end - start <= SearchHit.MAX_TERM_LENGTH) {
                words.add(text.substring(start, end));
                if (words.size() >= SearchHit.MAX_TERMS) {
                    return;
                }
            }
        }
    }

    /** A letter, digit or combining mark: the characters the page highlighter also treats as part of a word. */
    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || Character.getType(c) == Character.NON_SPACING_MARK;
    }
}
