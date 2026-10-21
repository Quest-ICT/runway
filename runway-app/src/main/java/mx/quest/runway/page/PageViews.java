package mx.quest.runway.page;

import java.util.Locale;
import java.util.Objects;
import mx.quest.runway.spi.model.PageMeta;
import mx.quest.runway.spi.model.Provenance;

/**
 * Materializes the immutable SPI {@link PageMeta} from a {@link Page} for a given locale — the host
 * resolving everything locale-dependent before any SPI sees it, so an extension never touches the
 * mutable page model.
 */
public final class PageViews {

    private PageViews() {
    }

    /** The SPI page view for {@code page} rendered in {@code locale}. */
    public static PageMeta toView(Page page, Locale locale, Locale siteDefault) {
        Objects.requireNonNull(page, "page");
        Objects.requireNonNull(locale, "locale");
        return new PageMeta(
                page.slug(),
                page.titleFor(locale),
                page.descriptionFor(locale),
                page.locales(),
                page.primaryLocale(siteDefault),
                page.published(),
                page.noindex(),
                null,
                // updatedAt is left unset here on purpose. This projection sees only the Page RECORD, and a
                // page's modification time lives in its FILES — PagesContentSource derives it from their
                // mtimes. Guessing one here (Instant.now(), say) would be worse than absent: it would assert a
                // "last updated" that changes on every render.
                new Provenance(page.author(), page.publishedAt(), null),
                page.nosnippet());
    }
}
