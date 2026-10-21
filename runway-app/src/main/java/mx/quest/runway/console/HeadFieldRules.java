package mx.quest.runway.console;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The accepted values of the site fields that end up in every page's head or in the Web App Manifest: a value a
 * browser or a social network does not understand is ignored by it silently, so the console refuses it with the
 * reason instead. Blank always means "not set" (the platform's automatic choice).
 */
final class HeadFieldRules {

    /** The Twitter/X card types that exist. */
    static final List<String> TWITTER_CARDS = List.of("summary", "summary_large_image");
    /** The manifest {@code display} modes browsers know, most to least app-like. */
    static final List<String> DISPLAY_MODES = List.of("fullscreen", "standalone", "minimal-ui", "browser");

    /** A CSS hex colour: {@code #rgb}, {@code #rgba}, {@code #rrggbb} or {@code #rrggbbaa}. */
    private static final Pattern HEX_COLOUR = Pattern.compile("#(?:[0-9a-fA-F]{3,4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})");
    /** An Open Graph type: {@code website}, {@code article}, {@code video.movie}, {@code music.song}, … */
    private static final Pattern OG_TYPE = Pattern.compile("[a-z][a-z0-9_]*(?:[.:][a-z][a-z0-9_]*)?");

    private HeadFieldRules() {
    }

    static String twitterCard(String value) {
        return oneOf(value, TWITTER_CARDS, "op.rules.twitterCard",
                "The Twitter card must be summary or summary_large_image, or empty for automatic.");
    }

    static String display(String value) {
        return oneOf(value, DISPLAY_MODES, "op.rules.display",
                "Display must be fullscreen, standalone, minimal-ui or browser, or empty.");
    }

    static String ogType(String value) {
        String v = blankToNull(value);
        if (v != null && (v.length() > 40 || !OG_TYPE.matcher(v.toLowerCase(Locale.ROOT)).matches())) {
            throw new SiteConfigInput.Invalid("op.rules.ogType",
                    "The content type must be an Open Graph type such as website or article.");
        }
        return v == null ? null : v.toLowerCase(Locale.ROOT);
    }

    static String colour(String value) {
        String v = blankToNull(value);
        if (v != null && !HEX_COLOUR.matcher(v).matches()) {
            throw new SiteConfigInput.Invalid("op.rules.colour", "A colour must be written as #rrggbb, such as #0d6efd.");
        }
        return v == null ? null : v.toLowerCase(Locale.ROOT);
    }

    static String language(String value) {
        String v = blankToNull(value);
        if (v != null) {
            Locale locale = Locale.forLanguageTag(v);
            // Two or three letters for the language itself (ISO 639): Java also accepts 5-8 letters ("english").
            if (locale.getLanguage().length() < 2 || locale.getLanguage().length() > 3
                    || !locale.toLanguageTag().equalsIgnoreCase(v)) {
                throw new SiteConfigInput.Invalid("op.rules.language",
                        "The language must be a language tag such as en, es or pt-BR.");
            }
            return locale.toLanguageTag();
        }
        return null;
    }

    private static String oneOf(String value, List<String> allowed, String key, String fallback) {
        String v = blankToNull(value);
        if (v == null) {
            return null;
        }
        String lower = v.toLowerCase(Locale.ROOT);
        if (!allowed.contains(lower)) {
            throw new SiteConfigInput.Invalid(key, fallback);
        }
        return lower;
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.strip();
    }
}
