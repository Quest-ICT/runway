package mx.quest.runway.site;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * The rule for a site's {@link Site#baseUrl}: the origin every absolute link of the site is built on — canonical
 * and alternate links, the sitemap, structured data, social cards and IndexNow pings.
 *
 * <p>Empty means "derived": {@code https://<first host>} ({@link SiteViews#toView}). Set it when that is wrong: the
 * site is served over plain {@code http}, on a non-default port, or its public name is another of its hosts (the
 * {@code www} form, say).
 *
 * <h2>Accepted form</h2>
 * {@code http} or {@code https}, a host, an optional port, and nothing else: no path (sites are served at the root of
 * their host, so a path would make every absolute link point elsewhere), no query, fragment or user name. Stored
 * lowercased without a trailing {@code /}.
 */
public final class SiteBaseUrl {

    /** Longest accepted base URL. */
    static final int MAX_LENGTH = 300;

    private SiteBaseUrl() {
    }

    /**
     * {@code text} in its stored form, or {@code null} for blank (the derived default).
     *
     * @throws IllegalArgumentException naming what is wrong
     */
    public static String normalize(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("the base URL is longer than " + MAX_LENGTH + " characters");
        }
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("'" + trimmed + "' is not a URL such as https://www.example.com");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) {
            throw new IllegalArgumentException("the base URL must start with https:// or http://");
        }
        if (uri.getHost() == null || uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("'" + trimmed + "' is not a URL such as https://www.example.com");
        }
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        if (!path.isEmpty() && !path.equals("/")) {
            throw new IllegalArgumentException("the base URL cannot have a path: a site is served at the root of its host");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("the base URL cannot have a query or a fragment");
        }
        return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + (uri.getPort() >= 0 ? ":" + uri.getPort() : "");
    }
}
