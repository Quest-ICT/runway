package mx.quest.runway.spi.model;

import java.util.Locale;
import java.util.Objects;

/**
 * A localized alternate of a page — one {@code (locale, url)} pair, the raw material for an
 * {@code hreflang} link. The set of alternates a page has is derived from its own available
 * locales, not the site's.
 *
 * @param locale the locale this alternate is for (never {@code null})
 * @param url    the absolute or root-relative URL of the page in that locale (never {@code null})
 */
public record Alternate(Locale locale, String url) {

    public Alternate {
        Objects.requireNonNull(locale, "locale");
        Objects.requireNonNull(url, "url");
    }

    /**
     * The value for an {@code hreflang} attribute — a <b>BCP&nbsp;47 language tag</b>.
     *
     * <p>Exists so the two surfaces that emit alternates cannot disagree. The head contributor formatted with
     * {@code locale.getLanguage()} and the sitemap with {@code locale.toLanguageTag()}: identical for a plain
     * {@code es}, divergent the moment a region appears ({@code pt} versus {@code pt-BR}). Two surfaces
     * describing the same page with different tags is precisely the kind of inconsistency that makes an engine
     * discard the whole annotation set, and nothing would have failed — they simply drift.
     *
     * <p>{@code toLanguageTag()} is the correct one: {@code hreflang} takes a language tag, optionally with a
     * region, and dropping the region silently mislabels a regional variant as the base language.
     *
     * <p><b>Not the same thing as the URL prefix.</b> The path prefix is the LANGUAGE only ({@code /es/…}) —
     * one directory per language, regardless of region — so a difference there is by design, not drift.
     */
    public String hreflang() {
        return locale.toLanguageTag();
    }
}
