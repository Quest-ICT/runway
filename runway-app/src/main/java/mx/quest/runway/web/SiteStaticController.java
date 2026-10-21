package mx.quest.runway.web;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.seo.SeoConfigService;
import mx.quest.runway.storage.SiteFileStore;
import org.springframework.core.io.PathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Serves the current host's site its own public static files from {@code sites/<id>/static/**} — the
 * stylesheets, scripts, images, fonts and documents a site references from its pages. This is the
 * per-tenant counterpart to the plugin static namespace ({@code /__plugin/<id>/static/**}); the app's
 * own console assets live elsewhere ({@code /__assets/**}).
 *
 * <h2>Why a controller, not a resource handler</h2>
 * A {@code /**} page controller sits at the lowest pattern specificity, so a {@code @GetMapping} for the
 * more specific {@code /static/{*resource}} is chosen ahead of it by the same handler mapping — no ordering
 * tricks needed. Spring Boot's default {@code /**} static handler is disabled
 * ({@code spring.web.resources.add-mappings: false}), so it cannot re-shadow either. A dedicated controller
 * (rather than a per-site resource handler) is used because the location is chosen <em>per request</em>
 * from the resolved host, and because the withheld-path {@linkplain SiteStaticGuard guards} need a say.
 *
 * <h2>Safety</h2>
 * Two confinement layers: this handler first pins the request to the site's own {@code static/} subtree
 * (a path that normalizes out of it — e.g. {@code ../users.yml}, which would still sit <em>inside</em> the
 * site folder and so pass the store's site-level check — is refused here, so serving never leans on the
 * edge HTTP firewall alone), and {@link SiteFileStore} then confines to the site folder and rejects
 * traversal and symlink escapes. The body is <b>streamed</b> from the resolved path (never buffered whole),
 * so no single public request can be forced to hold a large file in the heap. The {@code static/doc/}
 * subtree — operator-uploaded documents, which may be less trusted than the designer's own assets — is
 * always served as a {@code nosniff} attachment, so an uploaded {@code .html}/{@code .svg} can never render
 * (and run script) inline on the site's origin. The route is anonymous (public site pages are), and an
 * absent, withheld, directory, or out-of-folder target is an ordinary {@code 404} — never a {@code 500} or
 * a cross-tenant read.
 */
@Controller
public class SiteStaticController {

    /** The site subtree this route serves; every request is pinned inside it. */
    private static final Path STATIC_ROOT = Path.of("static");

    /** The documents subtree (relative to {@code static/}) — served as safe downloads, never inline. */
    private static final String DOC_PREFIX = "doc";

    private final SiteResolver siteResolver;
    private final SiteFileStore files;
    private final List<SiteStaticGuard> guards;
    private final RunwayHttpCacheProperties httpCache;
    private final SeoConfigService seo;

    public SiteStaticController(SiteResolver siteResolver, SiteFileStore files, List<SiteStaticGuard> guards,
                                RunwayHttpCacheProperties httpCache, SeoConfigService seo) {
        this.siteResolver = siteResolver;
        this.files = files;
        this.guards = List.copyOf(guards);
        this.httpCache = httpCache;
        this.seo = seo;
    }

    /**
     * Serves {@code sites/<current-site>/static/<resource>}. Returns {@code 404} when no site matches the
     * host, the path is empty/withheld, or the file is absent.
     *
     * @param resource the path captured after {@code /static} (leading-slashed, decoded by the path parser)
     */
    @GetMapping("/static/{*resource}")
    @ResponseBody
    public ResponseEntity<Resource> serve(@PathVariable String resource) {
        Site site = siteResolver.current().orElse(null);
        if (site == null) {
            return notFound();
        }
        String rel = relUnderStatic(resource);
        if (rel == null) {
            return notFound();
        }
        // Pin inside static/ (defence in depth beyond the edge firewall). Normalizing once means the guard
        // and the read see the same canonical path — no variant can be withheld yet still fetched.
        Path resolved = STATIC_ROOT.resolve(rel).normalize();
        if (!resolved.startsWith(STATIC_ROOT) || resolved.equals(STATIC_ROOT)) {
            return notFound();
        }
        String staticRelPath = STATIC_ROOT.relativize(resolved).toString();
        if (isHidden(staticRelPath)) {
            return notFound();
        }
        for (SiteStaticGuard guard : guards) {
            if (guard.isWithheld(site.id(), staticRelPath)) {
                return notFound();
            }
        }
        Optional<Path> file = files.resolveServable(site.id(), resolved.toString());
        if (file.isEmpty()) {
            return notFound();
        }
        Path path = file.get();
        // Public assets change rarely and are safe to cache; an operator edit lands under a new URL. The rule
        // is configurable (runway.http-cache.assets), defaulting to a day — except immutable asset types
        // (fonts by default: runway.http-cache.immutable-*) get a year + `immutable` since they are replaced
        // under a new name, never edited in place. Documents (doc/) are operator-uploaded and editable, so
        // they stay on the mutable default regardless of extension.
        boolean document = isDocument(staticRelPath);
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL,
                        document ? httpCache.assetsOrDefault() : httpCache.cacheControlFor(staticRelPath));
        if (document) {
            // A PDF has no <head>, so the ONLY indexing control a search engine will honour for it is this
            // response header. Off by default: turning it on for every site would silently de-index documents
            // that are legitimately ranking today (a published whitepaper is often the landing target), and a
            // default that removes content from search is not one to choose on a tenant's behalf. The site
            // config is cached per site, so this costs a map lookup on the asset path.
            if (seo.get(site.id()).documentsNoindex()) {
                builder.header("X-Robots-Tag", "noindex");
            }
            // Untrusted upload: force a download with no content-type sniffing, so it never runs inline.
            builder.header("X-Content-Type-Options", "nosniff")
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + downloadName(path) + "\"")
                    .contentType(MediaType.APPLICATION_OCTET_STREAM);
        } else {
            builder.contentType(MediaTypeFactory.getMediaType(staticRelPath).orElse(MediaType.APPLICATION_OCTET_STREAM));
            if (isScriptableDocument(staticRelPath)) {
                // An SVG or XML file opened directly is a document that can run script on the site's origin, and
                // the page CSP only covers HTML. Sandboxed, it cannot; as an <img>, <use> or CSS background it is
                // an image, to which a browser applies no policy, so it still shows. Not an attachment, for that.
                builder.header("Content-Security-Policy", "sandbox");
            }
        }
        return builder.body(new PathResource(path));
    }

    /**
     * Whether the asset is an SVG, XML, XSLT or XHTML file, which a browser opening it directly would render as a
     * document that can run script. Uploads accept only {@code .svg} and {@code .xml} of these, but a restored backup
     * or a file placed on disk can hold the others, and the page CSP (HTML only) covers none of them.
     */
    static boolean isScriptableDocument(String staticRelPath) {
        String name = staticRelPath.toLowerCase(Locale.ROOT);
        return SCRIPTABLE_DOCUMENTS.stream().anyMatch(name::endsWith);
    }

    private static final java.util.List<String> SCRIPTABLE_DOCUMENTS =
            java.util.List.of(".svg", ".svgz", ".xml", ".xsl", ".xslt", ".xhtml", ".xht");

    /**
     * Whether a part of the path starts with a dot ({@code .git/config}, {@code .htaccess}, {@code .env}): such files
     * are tool or server metadata, never a page asset, and are not served. {@code .well-known/} is the one dot folder
     * the web defines for public use (security.txt, app links, ACME), so it is served as usual. The Static files
     * screen still lists, opens and deletes them.
     */
    static boolean isHidden(String staticRelPath) {
        for (String part : staticRelPath.replace('\\', '/').split("/")) {
            if (part.startsWith(".") && !part.equals(".well-known")) {
                return true;
            }
        }
        return false;
    }

    /** Whether the requested asset is a document (the {@code doc/} subtree), which is served as a download. */
    private static boolean isDocument(String staticRelPath) {
        return staticRelPath.equals(DOC_PREFIX) || staticRelPath.startsWith(DOC_PREFIX + "/");
    }

    /** A header-safe filename for the attachment disposition — the resolved leaf, control chars and quotes stripped. */
    private static String downloadName(Path path) {
        String name = path.getFileName().toString();
        StringBuilder safe = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c >= 0x20 && c != 0x7f && c != '"' && c != '\\') {
                safe.append(c);
            }
        }
        return safe.isEmpty() ? "download" : safe.toString();
    }

    /** Strips the capture's leading slash; a blank capture (a bare {@code /static}) has no resource. */
    private static String relUnderStatic(String captured) {
        if (captured == null) {
            return null;
        }
        String rel = captured.startsWith("/") ? captured.substring(1) : captured;
        return rel.isBlank() ? null : rel;
    }

    private static ResponseEntity<Resource> notFound() {
        return ResponseEntity.notFound().build();
    }
}
