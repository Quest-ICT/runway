package mx.quest.runway.web;

import java.util.Locale;
import java.util.Map;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.convert.support.DefaultConversionService;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.expression.ThymeleafEvaluationContext;

/**
 * Renders a template <b>for one site</b> — the single render service behind every tenant-facing output: the public
 * page and content-source renders, the custom error pages, the draft/editor preview and the quality gate. Nothing
 * else in the kernel hands a {@code __site__/…} name to the template engine.
 *
 * <p><b>Tenant isolation is enforced here, not trusted to the template.</b> A site's templates are authored by its
 * site managers ({@code .th.xml} logic is full Thymeleaf), so a template is untrusted input with respect to every
 * other site and to the platform. Two guarantees follow:
 * <ol>
 *   <li><b>The rendering site travels with the render, not with the template name.</b> The render is started from
 *       {@link SiteTemplateResolver#renderSpec(String, String)}, whose template-resolution attribute names the
 *       site; Thymeleaf hands the same attributes to every nested resolution (a {@code th:insert}/{@code th:replace},
 *       a computed or preprocessed fragment name, a pulled master), and {@link SiteTemplateResolver} resolves only
 *       {@code __site__/<thatSite>/…}. Another site's name fails exactly like a missing template. The attributes are
 *       also part of Thymeleaf's template-cache key, so a cached parse made for one site is never a cache hit for a
 *       render of another — the resolver is not consulted on a hit, so the key is what keeps a hit honest.</li>
 *   <li><b>No Spring beans.</b> The SpEL evaluation context is built over an <em>empty</em> application context:
 *       a {@code ${@anyBean…}} expression in a tenant template (or in any template it pulls) finds no bean and the
 *       render fails, instead of letting a site manager call into the kernel's stores — every site's records, users
 *       and settings would otherwise be one expression away. The conversion service is kept, so {@code ${{…}}}
 *       still converts.</li>
 * </ol>
 */
@Service
public class PageRenderer {

    /**
     * The bean-less application context tenant renders evaluate against. Refreshed once, holds nothing, never
     * closed; it exists only because {@link ThymeleafEvaluationContext} requires a context to resolve
     * {@code @bean} references against — this one resolves none.
     */
    private static final GenericApplicationContext NO_BEANS = emptyContext();

    private final TemplateEngine engine;

    public PageRenderer(TemplateEngine engine) {
        this.engine = engine;
    }

    /**
     * Renders {@code templateName} for site {@code siteId} in {@code locale}, with {@code model} as the template
     * variables.
     *
     * @param siteId       the site this render is <b>for</b> — taken from the resolved request / page, never from a
     *                     template; it bounds which {@code __site__/…} templates the whole render can reach
     * @param templateName the entry template: {@code __site__/<siteId>/<name>}, or a content source's
     *                     {@code __plugin__/<id>/<name>} body template
     * @throws org.thymeleaf.exceptions.TemplateInputException when the template, or anything it pulls, cannot be
     *         resolved — including a template of another site, which is indistinguishable from a missing one
     */
    public String render(String siteId, String templateName, Locale locale, Map<String, Object> model) {
        Context context = new Context(locale);
        model.forEach(context::setVariable);
        // A fresh evaluation context per render: Thymeleaf mutates it while evaluating (expression objects, the
        // restricted-variable flag), so it must never be shared between concurrent renders.
        context.setVariable(ThymeleafEvaluationContext.THYMELEAF_EVALUATION_CONTEXT_CONTEXT_VARIABLE_NAME,
                new ThymeleafEvaluationContext(NO_BEANS, DefaultConversionService.getSharedInstance()));
        return engine.process(SiteTemplateResolver.renderSpec(siteId, templateName), context);
    }

    private static GenericApplicationContext emptyContext() {
        GenericApplicationContext context = new GenericApplicationContext();
        context.refresh();
        return context;
    }
}
