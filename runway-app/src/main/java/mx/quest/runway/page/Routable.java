package mx.quest.runway.page;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import mx.quest.runway.spi.model.PageMeta;

/**
 * The kernel-internal <b>dispatch surface</b> of anything servable at a public site URL — the exact facts the
 * locale-dispatch ladder, the render model, and the {@code <head>} treatment consume. {@link Page} implements
 * it directly; a plugin's content is adapted onto it by {@code content.RoutableContent}. Retyping the shared
 * helpers ({@link LocaleUrls}, {@link LocaleNegotiator}, {@link RenderModelBuilder}) to this view is what
 * guarantees virtual content behaves <b>byte-identically</b> to a real page through every row of the dispatch
 * table (SITE-MANAGER-GUIDE §9): both run the <em>same code</em>, not a re-implementation — the design's
 * no-two-class-system rule applied to routing.
 *
 * <p>Deliberately NOT part of the SPI: plugins produce {@link mx.quest.runway.spi.model.ContentRef}s; how the
 * kernel dispatches them is kernel-internal and free to evolve.
 */
public interface Routable {

    /** The public path for {@code locale}: a localized override if any, else the default path. */
    String pathFor(Locale locale);

    /** Whether available in {@code locale} (an empty {@link #locales()} means site-default only). */
    boolean hasLocale(Locale locale, Locale siteDefault);

    /** The primary locale: the site default if available, else the first declared. */
    Locale primaryLocale(Locale siteDefault);

    /** The title for {@code locale}, falling back to any present value, else empty. */
    String titleFor(Locale locale);

    /** The description for {@code locale}, falling back to any present value, else empty. */
    String descriptionFor(Locale locale);

    /** The locales this is published in (empty ⇒ site default only). */
    List<Locale> locales();

    /** The canonical-URL override for the {@code <head>}, or {@code null} for the computed canonical. */
    String canonical();

    /** Whether robots must be told not to index this content. */
    boolean noindex();

    /** The navigation id this content highlights, or {@code null}. */
    String activeNav();

    /** Free-form template parameters (never {@code null}; may be empty). */
    Map<String, String> params();

    /** The {@code Cache-Control} override, or {@code null} for the platform default. */
    String cacheControl();

    /** The name of the master (chrome) template within the site's own {@code templates/} folder. */
    String masterTemplate();

    /** The immutable SPI view of this content as rendered in {@code locale} (drives head injection). */
    PageMeta meta(Locale locale, Locale siteDefault);
}
