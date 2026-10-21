package mx.quest.runway.content;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import mx.quest.runway.plugin.ContributionGuard;
import mx.quest.runway.spi.ContentSource;
import mx.quest.runway.spi.model.ContentBody;
import mx.quest.runway.spi.model.ContentRef;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.storage.PathSafety;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Resolves a request path to a content source's page — the kernel half of virtual-page routing, consulted by
 * the page controller <b>only after real pages miss</b> (the structural "real page wins" precedence). The {@link
 * PagesContentSource} is skipped here — routing
 * already resolved pages against the store directly, so consulting their projection again would be a redundant
 * second lookup of the same data.
 *
 * <h2>Fail-closed sanitization</h2>
 * Every source answer is re-checked before it routes — the kernel backstop for the contracts a source could
 * violate: the ref must be <b>published</b>, its matched path must be <b>path-safe</b> and actually equal the
 * requested path for the lookup locale (a source cannot answer for a different URL), and its
 * {@code bodyTemplate} must live in an allowed namespace ({@code __plugin__/<id>/…} or
 * {@code __site__/<thisSiteId>/…}) so a source can never point the renderer at another tenant's files. A
 * violating answer is dropped with a WARN — omission, never disclosure.
 *
 * <h2>Failure containment vs. loud collisions</h2>
 * A <b>throwing</b> source is logged and treated as a miss (degrade-don't-crash: a broken plugin must not 500
 * a tenant's whole 404 surface). But <b>two sources claiming the same path</b> is a real configuration
 * conflict and stays LOUD: an {@link IllegalStateException} naming both sources and ids (the controller turns
 * it into a 500) — byte-consistent with the page store's never-silently-shadow discipline for duplicate page
 * paths. Recovery is operator action (the per-tenant plugin settings), not a silent arbitrary winner.
 */
@Component
public class ContentRouter {

    private static final Logger log = LoggerFactory.getLogger(ContentRouter.class);

    /** A resolved virtual page: the owning source and its matched, sanitized ref. */
    public record ContentMatch(ContentSource source, RoutableContent content) {
    }

    private final ContentSourceRegistry registry;

    public ContentRouter(ContentSourceRegistry registry) {
        this.registry = registry;
    }

    /** Whether any source beyond the built-in pages projection is registered (else routing skips the seam). */
    public boolean hasVirtualSources() {
        return registry.sources().stream().anyMatch(s -> !PagesContentSource.KEY.equals(s.key()));
    }

    /**
     * Resolves {@code path} (locale prefix already stripped) for the locale the URL implies, consulting every
     * registered source except the pages projection. See the class contract for sanitization, failure
     * containment, and the loud virtual-vs-virtual collision.
     *
     * @param site         the site being served
     * @param path         the request path, leading {@code /}, no locale prefix
     * @param lookupLocale the locale the URL implies (prefix locale, else the site default)
     * @return the single matching virtual page, or empty
     * @throws IllegalStateException when two sources claim the same path (the loud collision)
     */
    public Optional<ContentMatch> resolve(SiteView site, String path, Locale lookupLocale) {
        List<ContentMatch> matches = new ArrayList<>(1);
        for (ContentSource source : registry.sources()) {
            if (PagesContentSource.KEY.equals(source.key())) {
                continue; // real pages were already consulted first, against the store itself
            }
            // Contained: a source that throws is treated as a miss (logged once per minute, counted against its plugin).
            Optional<ContentRef> answer = registry.guard().call(source, ContributionGuard.Axis.CONTENT_SOURCE,
                    source.key(), () -> source.resolve(site, path, lookupLocale), Optional::empty);
            if (answer == null || answer.isEmpty()) {
                continue;
            }
            ContentRef ref = answer.get();
            if (sanitize(source, ref, site, path, lookupLocale)) {
                matches.add(new ContentMatch(source, new RoutableContent(source.key(), ref)));
            }
        }
        if (matches.size() > 1) {
            // Two plugins claiming one URL is a configuration conflict, not a tie to break silently.
            throw new IllegalStateException("virtual-path collision on '" + path + "' for site '" + site.id()
                    + "': " + matches.get(0).source().key() + ":" + matches.get(0).content().ref().id()
                    + " and " + matches.get(1).source().key() + ":" + matches.get(1).content().ref().id());
        }
        return matches.isEmpty() ? Optional.empty() : Optional.of(matches.get(0));
    }

    /**
     * The matched item's body payload under the containment policy: empty when the source's {@code body()} throws or
     * returns {@code null} (logged once per minute, counted against its plugin), so the caller can render the
     * chrome around an empty body and serve it uncacheable.
     */
    public Optional<ContentBody> body(ContentMatch match, SiteView site, Locale locale) {
        ContentSource source = match.source();
        return Optional.ofNullable(registry.guard().callNonNull(source, ContributionGuard.Axis.CONTENT_SOURCE,
                source.key(), () -> source.body(site, match.content().ref().id(), locale), () -> null));
    }

    /** The fail-closed re-check of a source's answer; a {@code false} means dropped (already WARNed). */
    private boolean sanitize(ContentSource source, ContentRef ref, SiteView site, String path,
                             Locale lookupLocale) {
        if (!ref.published()) {
            log.warn("content source '{}' answered an UNPUBLISHED ref '{}' for '{}' (site '{}') — dropped",
                    source.key(), ref.id(), path, site.id());
            return false;
        }
        String matched = ref.pathFor(lookupLocale);
        if (!path.equals(matched)) {
            log.warn("content source '{}' ref '{}' does not actually serve '{}' in {} (its path is '{}') — dropped",
                    source.key(), ref.id(), path, lookupLocale.toLanguageTag(), matched);
            return false;
        }
        if (!PathSafety.isSafePagePath(matched)) {
            log.warn("content source '{}' ref '{}' has an unsafe path '{}' — dropped", source.key(), ref.id(),
                    matched);
            return false;
        }
        String template = ref.bodyTemplate();
        boolean allowedNamespace = template.startsWith("__plugin__/")
                || template.startsWith("__site__/" + site.id() + "/");
        if (!allowedNamespace) {
            log.warn("content source '{}' ref '{}' names a body template outside the allowed namespaces: '{}' "
                    + "— dropped", source.key(), ref.id(), template);
            return false;
        }
        // The master param becomes a template name inside the tenant's templates/ dir; resolution is
        // traversal-confined downstream, but rejecting a suspicious name HERE turns a would-be per-URL 500
        // into a clean drop with a diagnosable log line.
        String master = new RoutableContent(source.key(), ref).masterTemplate();
        if (master.contains("..") || master.startsWith("/") || master.contains("\\")) {
            log.warn("content source '{}' ref '{}' names a suspicious master template '{}' — dropped",
                    source.key(), ref.id(), master);
            return false;
        }
        return true;
    }
}
