package mx.quest.runway.i18n;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for per-tenant i18n, bound from {@code runway.i18n.*}.
 */
@ConfigurationProperties("runway.i18n")
public class I18nProperties {

    /**
     * How often, in seconds, a message lookup made <b>outside</b> a public page render (an error page, a plugin's
     * off-request text) and the operator console's bundle re-check their files on disk; {@code 0} = on every
     * lookup. A public page re-checks the site's bundles on every request regardless, because its ETag
     * folds them and must describe the text the page is rendered with.
     */
    private int cacheSeconds = 5;

    public int getCacheSeconds() {
        return cacheSeconds;
    }

    public void setCacheSeconds(int cacheSeconds) {
        this.cacheSeconds = cacheSeconds;
    }
}
