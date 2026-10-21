package mx.quest.search;

import java.util.List;
import java.util.Locale;
import mx.quest.runway.spi.model.SettingsView;

/**
 * The plugin's typed, per-site settings — an immutable view over the {@link SettingsView} the kernel's
 * {@code PluginSettings.site(siteId)} returns for this plugin ({@code sites/<id>/plugins/search.yml} overlaid on the
 * {@code siteSettings:} defaults of {@code plugin.yml}). The kernel already applies the descriptor defaults; the code
 * fallbacks here only matter for a key the schema does not declare, and every one of them equals its descriptor
 * {@code default:} (a test holds the two equal).
 *
 * <p>The ranking weights (title/description/body) and the phrase/all-terms bonuses shape the score; the
 * {@code exclude} list drops items from every engine's corpus by their own id (a page's slug, a source item's id);
 * {@code snippetLength} bounds the highlighted excerpt; {@code enabled}, {@code resultLimit}, {@code resultsPath},
 * {@code placeholder} and {@code highlight} shape the public surface.
 */
final class SearchSiteConfig {

    static final boolean ENABLED_DEFAULT = true;
    static final boolean HIGHLIGHT_DEFAULT = true;
    static final int RESULT_LIMIT_DEFAULT = 20;
    static final String RESULTS_PATH_DEFAULT = "/search";
    static final int SNIPPET_MIN = 40;
    static final int SNIPPET_MAX = 400;
    static final int SNIPPET_DEFAULT = 160;
    static final int TITLE_WEIGHT_DEFAULT = 8;
    static final int DESCRIPTION_WEIGHT_DEFAULT = 4;
    static final int BODY_WEIGHT_DEFAULT = 1;
    static final int ALL_TERMS_BONUS_DEFAULT = 1000;
    static final int PHRASE_TITLE_BONUS_DEFAULT = 400;
    static final int PHRASE_BODY_BONUS_DEFAULT = 300;

    private final SettingsView view;

    private SearchSiteConfig(SettingsView view) {
        this.view = (view == null) ? SettingsView.empty() : view;
    }

    static SearchSiteConfig of(SettingsView view) {
        return new SearchSiteConfig(view);
    }

    /** Whether search serves this site at all (the box and the JSON API); default on. */
    boolean enabled() {
        return view.bool("enabled", ENABLED_DEFAULT);
    }

    /**
     * Whether a result link carries the matched words, so the page it opens highlights them (since 2.1.0; default
     * on). Off: plain links, and the highlighter is not added to the site's pages.
     */
    boolean highlight() {
        return view.bool("highlight", HIGHLIGHT_DEFAULT);
    }

    /** The most hits a query returns, clamped to {@code 1..100} (default 20). */
    int resultLimit() {
        return (int) view.integer("resultLimit", RESULT_LIMIT_DEFAULT, 1, 100);
    }

    /** Where the box submits {@code ?q=} to: the results page (default {@code /search}). */
    String resultsPath() {
        return view.string("resultsPath", RESULTS_PATH_DEFAULT).strip();
    }

    /** The box's placeholder, or {@code fallback} (the localized default text) when the site set none. */
    String placeholder(String fallback) {
        String value = view.string("placeholder", "").strip();
        return value.isEmpty() ? fallback : value;
    }

    /** Weight added when a query term appears in the title (default 8; title matches dominate). */
    int titleWeight() {
        return weight("titleWeight", TITLE_WEIGHT_DEFAULT, 1_000_000);
    }

    /** Weight added when a query term appears in the description (default 4). */
    int descriptionWeight() {
        return weight("descriptionWeight", DESCRIPTION_WEIGHT_DEFAULT, 1_000_000);
    }

    /** Weight added <em>per body occurrence</em> of a term, occurrences capped by the engine (default 1). */
    int bodyWeight() {
        return weight("bodyWeight", BODY_WEIGHT_DEFAULT, 1_000_000);
    }

    /** Flat bonus when the page contains <em>all</em> query terms across its fields (default 1000). */
    int allTermsBonus() {
        return weight("allTermsBonus", ALL_TERMS_BONUS_DEFAULT, 1_000_000_000);
    }

    /** Flat bonus when the title contains the whole query as a phrase (default 400). */
    int phraseTitleBonus() {
        return weight("phraseTitleBonus", PHRASE_TITLE_BONUS_DEFAULT, 1_000_000_000);
    }

    /** Flat bonus when the body contains the whole query as a phrase (default 300). */
    int phraseBodyBonus() {
        return weight("phraseBodyBonus", PHRASE_BODY_BONUS_DEFAULT, 1_000_000_000);
    }

    /** Characters of context per result snippet, clamped to [{@value #SNIPPET_MIN}, {@value #SNIPPET_MAX}]. */
    int snippetLength() {
        return (int) view.integer("snippetLength", SNIPPET_DEFAULT, SNIPPET_MIN, SNIPPET_MAX);
    }

    /**
     * The lowercase, trimmed, distinct ids to keep out of the search corpus (never {@code null}): a page's slug or a
     * content-source item's id. A YAML list or a comma/newline-separated string; a leading {@code /} and surrounding
     * blanks are tolerated, so an operator can paste either {@code legal/privacy} or {@code /legal/privacy}.
     */
    List<String> excludedIds() {
        return view.list("exclude").stream()
                .map(String::strip)
                .map(s -> s.startsWith("/") ? s.substring(1) : s)
                .map(s -> s.toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
    }

    private int weight(String key, int fallback, int max) {
        return (int) view.integer(key, fallback, 0, max);
    }
}
