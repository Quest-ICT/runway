package mx.quest.runway.console;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The one validation of a site's <b>name and languages</b>, shared by the two screens that edit them — the
 * tenant's Site config ({@code /__admin/config}) and the operator's Sites screen ({@code /__app/sites}) — so the
 * two paths can never disagree about what a valid site configuration is. {@link
 * mx.quest.runway.site.SiteLifecycleService#updateConfig} documents that validation is the caller's contract; this
 * is that contract, in one place.
 *
 * <p>The rules: the name is not blank; a default language is given and well-formed; the available set is the
 * distinct, well-formed submitted tags in submitted order (blanks and malformed tags skipped); and the default must
 * be <em>in</em> that set — it is never silently added, because a default the operator did not list is a mistake
 * to show, not to repair. The result lists the default first (the {@link mx.quest.runway.site.Site} convention).
 */
final class SiteConfigInput {

    private SiteConfigInput() {
    }

    /** A validated name + language set, ready for {@code updateConfig}. */
    record Valid(String name, Locale defaultLocale, List<Locale> locales) {
    }

    /** A refused submission: {@code messageKey} is an operator-console key, {@code fallback} its English text. */
    static final class Invalid extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private final String messageKey;

        Invalid(String messageKey, String fallback) {
            super(fallback);
            this.messageKey = messageKey;
        }

        String messageKey() {
            return messageKey;
        }
    }

    /**
     * Validates a submission.
     *
     * @param name          the display name (trimmed)
     * @param defaultLocale the default language tag
     * @param localeTags    the available language tags (may be {@code null})
     * @return the validated configuration, default locale first
     * @throws Invalid if a rule fails; nothing has been written
     */
    static Valid validate(String name, String defaultLocale, List<String> localeTags) {
        if (name == null || name.isBlank()) {
            throw new Invalid("op.config.error.blankName", "The site name cannot be blank.");
        }
        Locale defaultLoc = parseLocale(defaultLocale);
        if (defaultLoc == null) {
            throw new Invalid("op.config.error.noDefault", "Choose a default language.");
        }
        List<Locale> available = parseLocales(localeTags);
        if (!available.contains(defaultLoc)) {
            throw new Invalid("op.config.error.defaultNotAvailable",
                    "The default language must be one of the available languages.");
        }
        return new Valid(name.trim(), defaultLoc, defaultFirst(defaultLoc, available));
    }

    /** Splits a comma-separated tag list (the Sites screen's single text field) into tags. */
    static List<String> splitTags(String csv) {
        List<String> out = new ArrayList<>();
        if (csv != null) {
            for (String token : csv.split(",")) {
                if (!token.isBlank()) {
                    out.add(token.trim());
                }
            }
        }
        return out;
    }

    private static List<Locale> parseLocales(List<String> tags) {
        List<Locale> locales = new ArrayList<>();
        if (tags != null) {
            for (String tag : tags) {
                Locale loc = parseLocale(tag);
                if (loc != null && !locales.contains(loc)) {
                    locales.add(loc);
                }
            }
        }
        return locales;
    }

    private static List<Locale> defaultFirst(Locale defaultLoc, List<Locale> available) {
        List<Locale> ordered = new ArrayList<>();
        ordered.add(defaultLoc);
        for (Locale loc : available) {
            if (!loc.equals(defaultLoc)) {
                ordered.add(loc);
            }
        }
        return ordered;
    }

    private static Locale parseLocale(String tag) {
        if (tag == null || tag.isBlank()) {
            return null;
        }
        Locale loc = Locale.forLanguageTag(tag.trim());
        return loc.getLanguage().isEmpty() ? null : loc;
    }
}
