package mx.quest.search;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import mx.quest.search.spi.SearchHit;

/**
 * The link from a search result to the page it found, carrying the words to highlight there: the hit's URL plus the
 * fragment {@code #rw-hl=<words>}, the words separated by spaces and percent-encoded as one component. The fragment
 * never reaches the server, so the page is served and cached exactly as without it; {@code static/highlight.js} on
 * the target page reads it, highlights the words and removes it from the address bar (see {@link SearchHighlightHead}).
 *
 * <p>A hit with no matched words, or whose URL already has a fragment, links plainly.
 */
final class HighlightMarker {

    /** The fragment key the page script reads. Changing it breaks links already shared, so it is fixed. */
    static final String KEY = "rw-hl";

    private HighlightMarker() {
    }

    /** The hit's link: its URL, with the marker when it has matched words and no fragment of its own. */
    static String link(SearchHit hit) {
        if (hit.terms().isEmpty() || hit.url().indexOf('#') >= 0) {
            return hit.url();
        }
        // URLEncoder writes a space as '+', which decodeURIComponent leaves alone; %20 decodes the same everywhere.
        String words = URLEncoder.encode(String.join(" ", hit.terms()), StandardCharsets.UTF_8).replace("+", "%20");
        return hit.url() + "#" + KEY + "=" + words;
    }
}
