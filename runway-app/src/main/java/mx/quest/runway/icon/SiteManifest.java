package mx.quest.runway.icon;

/**
 * A site's Web App Manifest fields, edited on the icons screen and served (with a derived {@code icons}
 * array) at {@code /manifest.webmanifest}. Persisted as {@code sites/<id>/manifest.yml}. Every field is
 * optional; a blank/absent value means "not set" and is simply omitted from the served manifest. An absent
 * file is {@link #EMPTY}.
 *
 * @param name            the application name
 * @param shortName       the short name (home-screen label) → {@code short_name}
 * @param description     a short description
 * @param themeColor      the browser-UI theme color (a {@code #rrggbb}) → {@code theme_color}
 * @param backgroundColor the splash background color → {@code background_color}
 * @param display         the display mode ({@code standalone}, {@code browser}, …)
 * @param lang            the primary language tag
 */
public record SiteManifest(String name, String shortName, String description, String themeColor,
                           String backgroundColor, String display, String lang) {

    /** The all-unset manifest — what an absent {@code manifest.yml} resolves to. */
    public static final SiteManifest EMPTY = new SiteManifest(null, null, null, null, null, null, null);

    /** Whether any field is set (so the endpoint can decide there is a manifest worth serving). */
    public boolean hasAny() {
        return notBlank(name) || notBlank(shortName) || notBlank(description) || notBlank(themeColor)
                || notBlank(backgroundColor) || notBlank(display) || notBlank(lang);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
