package mx.quest.runway.spi.model;

import java.util.Locale;
import java.util.Objects;

/**
 * The immutable input to a {@link mx.quest.runway.spi.PageQualityCheck} — a fully resolved,
 * read-only page for advisory inspection, including its rendered HTML for the given locale.
 * A check inspects this view and returns findings; it never edits anything.
 *
 * <p>Page facts such as title, description, and available locales are reachable through
 * {@link #page()}, and the site's full locale set through {@link #site()}, so a check can audit
 * (for example) that {@code hreflang} only points to locales the page and site actually offer.
 *
 * @param site         the site being inspected (never {@code null})
 * @param page         the page metadata (never {@code null})
 * @param locale       the locale the {@code resolvedHtml} was rendered in (never {@code null})
 * @param canonical    the resolved canonical URL for this page+locale, for duplicate/correctness
 *                     checks, or {@code null} when not applicable
 * @param resolvedHtml the page's rendered HTML for that locale ({@code null} normalized to
 *                     empty), so checks can examine the actual delivered markup
 */
public record PageView(SiteView site, PageMeta page, Locale locale, String canonical, String resolvedHtml) {

    public PageView {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(page, "page");
        Objects.requireNonNull(locale, "locale");
        resolvedHtml = (resolvedHtml == null) ? "" : resolvedHtml;
    }
}
