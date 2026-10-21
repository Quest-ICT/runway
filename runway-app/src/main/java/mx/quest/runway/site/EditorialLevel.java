package mx.quest.runway.site;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * How strictly a site's page publishing is governed — the per-site <b>editorial level</b> chosen on the tenant
 * Site config screen and stored as {@code editorial:} in {@code site.yml}.
 *
 * <ul>
 *   <li>{@link #ADVISORY} (the default, and what every site did before the level existed): the quality panel
 *       reports, nothing is gated. Anyone with {@code DISABLE_PAGES} publishes; anyone with {@code EDIT_PAGES}
 *       saves straight to the live files.</li>
 *   <li>{@link #ENFORCED}: publishing a page, and saving a page that is already published, runs the quality
 *       checks first; any {@code ERROR} finding refuses the action. Warnings and infos never block.</li>
 *   <li>{@link #REVIEWED}: everything {@link #ENFORCED} does, plus a second pair of eyes. A user without
 *       {@code APPROVE_PAGES} cannot publish: they submit a draft for review, and their edits to a live page are
 *       kept as a <em>pending revision</em> that leaves the live page untouched until an approver approves it.
 *       A user who holds {@code APPROVE_PAGES} publishes their own changes directly.</li>
 * </ul>
 *
 * <p>The YAML form is the lower-case name. A missing key reads as {@link #ADVISORY}, so every existing
 * {@code site.yml} keeps today's behaviour. An <b>unrecognised</b> value also reads as {@link #ADVISORY} (with a
 * warning in the log) rather than failing the site load: a typo in a hand-edited file must not take a site down,
 * and advisory is the level that changes nothing.
 */
public enum EditorialLevel {

    /** Report only; gate nothing (default). */
    ADVISORY,
    /** Quality {@code ERROR}s block publishing and saving a published page. */
    ENFORCED,
    /** {@link #ENFORCED} plus approval by an {@code APPROVE_PAGES} holder. */
    REVIEWED;

    private static final Logger log = LoggerFactory.getLogger(EditorialLevel.class);

    /** Whether quality {@code ERROR} findings block publishing at this level. */
    public boolean enforcesChecks() {
        return this != ADVISORY;
    }

    /** Whether publishing needs an {@code APPROVE_PAGES} holder at this level. */
    public boolean requiresReview() {
        return this == REVIEWED;
    }

    /** The stored (and form) key: the lower-case name. */
    @JsonValue
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Parses a stored or submitted key, case-insensitively. {@code null}/blank is {@link #ADVISORY}; an unknown
     * value is {@link #ADVISORY} too, logged, so a hand-edit typo never prevents the site from loading.
     */
    @JsonCreator
    public static EditorialLevel fromKey(String key) {
        if (key == null || key.isBlank()) {
            return ADVISORY;
        }
        for (EditorialLevel level : values()) {
            if (level.key().equals(key.trim().toLowerCase(Locale.ROOT))) {
                return level;
            }
        }
        log.warn("unknown editorial level '{}' — treating the site as advisory", key);
        return ADVISORY;
    }

    /** Parses a submitted form value strictly: empty for anything that is not exactly one of the three keys. */
    public static java.util.Optional<EditorialLevel> parseStrict(String key) {
        if (key == null) {
            return java.util.Optional.empty();
        }
        for (EditorialLevel level : values()) {
            if (level.key().equals(key.trim())) {
                return java.util.Optional.of(level);
            }
        }
        return java.util.Optional.empty();
    }
}
