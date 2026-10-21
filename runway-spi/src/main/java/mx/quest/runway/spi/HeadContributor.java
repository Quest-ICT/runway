package mx.quest.runway.spi;

import mx.quest.runway.spi.model.Capabilities;
import mx.quest.runway.spi.model.HeadContext;

/**
 * Contributes markup to a page's {@code <head>}. The host collects every {@code HeadContributor}
 * (core and plugin alike) and splices their output before {@code </head>} at the response edge,
 * <b>independently of the site's templates</b> — this is how SEO/GEO/analytics markup lands on
 * every page without requiring any code inside a tenant's template.
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li>{@link #render(HeadContext)} is a <b>pure function</b> of its {@link HeadContext}: it
 *       reads no request, no store, no other tenant, and returns plain markup. Implementations
 *       must be <b>stateless and thread-safe</b> — one instance serves many concurrent requests.</li>
 *   <li>Return the markup to inject, or an <b>empty string</b> to contribute nothing for this
 *       request (a no-op is always valid).</li>
 *   <li>{@link #key()} is a stable, unique identifier for this contributor; a duplicate among kernel contributions fails startup; a plugin's
 *       colliding contribution is refused (the later plugin's in load order) and reported (since SPI 0.4.0).</li>
 * </ul>
 *
 * <h2>Limits &amp; failure</h2>
 * A {@code render} or {@code headInputs} that throws a {@code RuntimeException} or {@code LinkageError} contributes
 * nothing for that request; the page is then served {@code Cache-Control: no-store} without {@code ETag} or
 * {@code Last-Modified}, so the degraded copy is never cached. {@code headInputs} returning {@code null} counts as a
 * failure. The failure is logged (with the stack the first time per contributor, then at most once a minute),
 * counted in {@code runway.plugin.contribution.failures} and shown on the plugins screen. A page body larger than
 * the host's head-injection buffer (8 MiB by default) streams through without any injection.
 *
 * <p><b>Threading.</b> The host calls this concurrently from many request threads; an implementation must be
 * stateless or internally synchronized.
 */
public interface HeadContributor {

    /**
     * This contributor's stable, unique key (used for diagnostics and collision detection).
     *
     * @return a non-{@code null}, non-empty identifier, stable across releases
     */
    String key();

    /**
     * Produces the {@code <head>} markup to inject for the given request context.
     *
     * @param context the immutable, host-materialized request context (never {@code null})
     * @return the markup to splice before {@code </head>}; an empty string contributes nothing.
     *         Never {@code null}.
     */
    String render(HeadContext context);

    /**
     * The capabilities this contributor declares, so the host can branch on data rather than
     * type. Defaults to {@link Capabilities#none()}.
     *
     * @return the declared capabilities (never {@code null})
     */
    default Capabilities capabilities() {
        return Capabilities.none();
    }

    /**
     * The <b>site-relative</b> files and folders whose change alters this contributor's markup, so the host
     * can fold them into a page's {@code ETag}.
     *
     * <h2>Why a contributor has to say this</h2>
     * A page's strong {@code ETag} is a promise of <b>byte identity</b>, and head markup is spliced in at the
     * response edge, after the validator has already been computed and possibly answered {@code 304}. So
     * anything read here that the host cannot see is invisible to the validator: a site manager edits their
     * organisation name, reloads, sees it correct on a fresh fetch — and every client and CDN holding the old
     * validator keeps the previous head <b>indefinitely</b>, until some unrelated input happens to change.
     * That is a wrong answer that looks right from the one place anybody checks.
     *
     * <p>The host cannot infer this. It does not know which file a plugin reads, and must not learn any
     * particular plugin's layout — so the dependency travels with the code that has it.
     *
     * <h2>What to return</h2>
     * Paths <b>relative to the site folder</b> of files you read yourself ({@code "myplugin/feed.yml"}, a
     * folder you list). A folder folds its listing, so adding or removing a file in it moves the ETag; a
     * regular file folds its content — so read it when you render, or cache it only while the file is unchanged.
     * A path that escapes the site folder is ignored, and an absent path folds a stable "absent" marker, so
     * creating it later changes the ETag.
     *
     * <p>Your own settings need no declaration: the host folds every running plugin's app-level settings and, for a
     * plugin that declares {@code siteSettings:}, its per-site settings ({@link mx.quest.runway.spi.service.PluginSettings})
     * as loaded. A declared file the host
     * loads itself — {@code plugins/<id>.yml}, {@code seo.yml}, {@code manifest.yml}, {@code icons} — is folded
     * as loaded too, not as bytes, so the ETag moves when a change applies (a save or a site reload), with the
     * markup, rather than on a hand edit the markup does not show yet.
     *
     * <p>List only what genuinely shapes the markup. These are read on <b>every</b> conditional request for
     * every page of the site, so a large or numerous set is a per-request cost paid forever.
     *
     * <p>Anything derived from the {@link HeadContext} — the page, the site record, the locale, the canonical
     * URL — is already folded by the host and must not be listed. A contributor whose output is a pure
     * function of its context declares nothing, which is why the default is empty and every contributor
     * written before this method existed keeps working.
     *
     * @param siteId the site being rendered, for a contributor whose inputs are named per site (rare — most
     *               return the same constant list for every site)
     * @return site-relative paths; never {@code null}
     */
    default java.util.List<String> headInputs(String siteId) {
        return java.util.List.of();
    }
}
