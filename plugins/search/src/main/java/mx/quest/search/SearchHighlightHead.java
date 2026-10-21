package mx.quest.search;

import java.util.Objects;
import mx.quest.runway.spi.HeadContributor;
import mx.quest.runway.spi.model.HeadContext;
import mx.quest.runway.spi.service.PluginSettings;

/**
 * Puts the result highlighter on every page of a site that has search and {@code highlight} on (since 2.1.0). What
 * it adds to the {@code <head>} is one {@code <script src defer>} for {@code static/highlight-loader.js}, a file of a
 * few hundred bytes that does nothing unless the address carries the {@link HighlightMarker} fragment, now or after a
 * {@code hashchange}; only then does it load {@code static/highlight.js}, which does the work.
 *
 * <p><b>CSP.</b> An external same-origin script, because the kernel stamps the page's nonce only on scripts the
 * host-allowlist policy would also run: an inline script stays unstamped, and blocked, under {@code enforce}. The
 * plugin writes no nonce itself; the script the loader creates is trusted through {@code 'strict-dynamic'} (and is
 * same-origin under an allowlist policy).
 *
 * <p>Nothing is added to an editor preview, which no result links to. The output depends only on this plugin's
 * per-site settings, which the kernel folds into the page's {@code ETag} itself, so {@link #headInputs} stays empty.
 */
public class SearchHighlightHead implements HeadContributor {

    /** The one tag added to each page. */
    static final String LOADER = "<script src=\"/__plugin/search/static/highlight-loader.js"
            + SearchConfig.ASSET_QUERY + "\" defer></script>";

    private final PluginSettings settings;

    public SearchHighlightHead(PluginSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    @Override
    public String key() {
        return "search.highlight";
    }

    @Override
    public String render(HeadContext context) {
        if (context.preview()) {
            return "";
        }
        SearchSiteConfig cfg = SearchSiteConfig.of(settings.site(context.site().id()));
        return cfg.enabled() && cfg.highlight() ? LOADER : "";
    }
}
