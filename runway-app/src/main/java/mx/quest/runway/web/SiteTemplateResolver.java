package mx.quest.runway.web;

import java.nio.file.Path;
import java.util.Map;
import mx.quest.runway.storage.PathSafety;
import mx.quest.runway.storage.StorageLayout;
import org.thymeleaf.IEngineConfiguration;
import org.thymeleaf.TemplateSpec;
import org.thymeleaf.cache.ICacheEntryValidity;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.FileTemplateResolver;
import org.thymeleaf.templateresource.FileTemplateResource;
import org.thymeleaf.templateresource.ITemplateResource;

/**
 * Resolves a tenant template named <code>__site__/&lt;siteId&gt;/&lt;name&gt;</code> to the file
 * <code>&lt;external&gt;/sites/&lt;siteId&gt;/templates/&lt;name&gt;.html</code>, with its paired
 * <code>.th.xml</code> decoupled-logic file alongside.
 *
 * <p>Three non-negotiables are baked in:
 * <ul>
 *   <li><b>A render reaches only its own site</b> — the name says which site's folder a template lives in, but
 *       never which site may read it. A name resolves only when its site is the <em>rendering</em> site, carried
 *       by the template-resolution attribute {@link #RENDER_SITE_ATTRIBUTE} that {@link #renderSpec} puts on the
 *       render's {@link TemplateSpec}. Thymeleaf hands the root spec's attributes to every nested resolution, so
 *       a site manager's {@code .th.xml} cannot widen it however the name is produced (a literal, a computed
 *       {@code ${…}}, a {@code __${…}__} preprocessing, a {@code th:with} override of {@code ${master}}, a page's
 *       {@code template:}). Another site's name — or any {@code __site__/…} name in a render that carries no
 *       rendering site, such as a console view — is <b>not resolvable</b>, so it fails exactly as a missing
 *       template does, without touching the disk: no oracle for whether another site's template exists. The
 *       attributes are part of Thymeleaf's template-cache key, and a cache hit never consults this resolver, so
 *       it is the key that keeps a parse cached for one site out of another site's render.</li>
 *   <li><b>Site-scoped names</b> — Thymeleaf caches parsed templates by <em>name</em>, so each
 *       tenant's templates must carry a per-site-unique name; otherwise one tenant's cached chrome
 *       renders for another (invisible with the cache off). The
 *       <code>__site__/&lt;siteId&gt;/…</code> prefix guarantees uniqueness.</li>
 *   <li><b>Traversal safety</b> — the site id is validated and the template name is confined under
 *       the site's own {@code templates/} directory; a name can never escape it.</li>
 * </ul>
 *
 * <p>Decoupled logic is on (no Thymeleaf in the {@code .html}); caching is on (never disabled in dev — the
 * dev/production-parity rule). A cached parse is valid only while its {@code .html} and {@code .th.xml} are unchanged
 * on disk ({@link FileStampValidity}), so a template edited outside the console renders on the next request under
 * the ETag that already folds its bytes; {@link TemplateCacheService} still evicts on console saves and site events.
 */
public class SiteTemplateResolver extends FileTemplateResolver {

    /** The reserved name prefix for tenant templates. */
    public static final String PREFIX = "__site__/";

    /**
     * The template-resolution attribute naming the site a render is <b>for</b>. Set only by kernel code through
     * {@link #renderSpec}; a template can neither read nor change it.
     */
    public static final String RENDER_SITE_ATTRIBUTE = "runway.renderSite";

    private static final String TEMPLATES_DIR = "templates";
    private static final String SUFFIX = ".html";

    private final StorageLayout layout;

    public SiteTemplateResolver(StorageLayout layout) {
        this.layout = layout;
        setName("siteTemplateResolver");
        setTemplateMode(TemplateMode.HTML);
        setUseDecoupledLogic(true);
        setCacheable(true);
        setCharacterEncoding("UTF-8");
        setCheckExistence(true);
        setOrder(1);
    }

    /**
     * The spec that starts a render of {@code template} <b>for</b> site {@code siteId}: every {@code __site__/…}
     * name the render reaches — the entry template and everything it pulls — must belong to {@code siteId}.
     *
     * @param siteId   the rendering site, from the resolved request or page (never from a template)
     * @param template the entry template name ({@code __site__/<siteId>/…} or a {@code __plugin__/…} body)
     */
    public static TemplateSpec renderSpec(String siteId, String template) {
        if (siteId == null || siteId.isBlank()) {
            throw new IllegalArgumentException("a site render needs its site id");
        }
        return new TemplateSpec(template, null, (TemplateMode) null, Map.of(RENDER_SITE_ATTRIBUTE, siteId));
    }

    /**
     * Resolvable only when the name lies in the <b>rendering</b> site's namespace,
     * {@code __site__/<renderSite>/…} — see the class contract. Owner template and name are both ignored as a
     * source of authority: only the resolution attribute set by {@link #renderSpec} counts.
     */
    @Override
    protected boolean computeResolvable(IEngineConfiguration configuration, String ownerTemplate,
            String template, Map<String, Object> templateResolutionAttributes) {
        if (template == null || !template.startsWith(PREFIX) || templateResolutionAttributes == null) {
            return false;
        }
        return templateResolutionAttributes.get(RENDER_SITE_ATTRIBUTE) instanceof String renderSite
                && !renderSite.isEmpty()
                && template.startsWith(PREFIX + renderSite + "/");
    }

    @Override
    protected ITemplateResource computeTemplateResource(IEngineConfiguration configuration, String ownerTemplate,
            String template, String resourceName, String characterEncoding,
            Map<String, Object> templateResolutionAttributes) {
        return new FileTemplateResource(resolveFile(template).toString(), characterEncoding);
    }

    /**
     * A cached parse of this template stays valid while its files are unchanged on disk — checked on every cache
     * hit, so an out-of-band edit is never served from the previous parse (see {@link FileStampValidity}).
     */
    @Override
    protected ICacheEntryValidity computeValidity(IEngineConfiguration configuration, String ownerTemplate,
            String template, Map<String, Object> templateResolutionAttributes) {
        return FileStampValidity.forTemplate(resolveFile(template));
    }

    /** Maps {@code __site__/<siteId>/<name>} to the absolute {@code .html} file, traversal-safe. */
    private Path resolveFile(String template) {
        String rest = template.substring(PREFIX.length());
        int slash = rest.indexOf('/');
        if (slash <= 0 || slash == rest.length() - 1) {
            throw new IllegalArgumentException("template name must be " + PREFIX + "<siteId>/<name>: " + template);
        }
        String siteId = rest.substring(0, slash);
        String name = rest.substring(slash + 1);
        Path templatesDir = layout.siteDir(siteId).resolve(TEMPLATES_DIR); // siteDir validates siteId
        return PathSafety.resolveConfined(templatesDir, name + SUFFIX);
    }
}
