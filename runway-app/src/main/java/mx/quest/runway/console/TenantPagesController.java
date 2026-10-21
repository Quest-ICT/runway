package mx.quest.runway.console;

import java.util.List;
import mx.quest.runway.authz.AuthzExpressions;
import mx.quest.runway.content.PathOwnership;
import mx.quest.runway.editorial.EditorialService;
import mx.quest.runway.page.Page;
import mx.quest.runway.page.PageStore;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.site.SiteViews;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.server.ResponseStatusException;

/**
 * The tenant console's <b>Pages</b> listing ({@code /__admin/pages}): the grid of the bound tenancy's pages
 * (slug, path, locales, editorial status) over {@link PageStore}, and the entry point to the editor (its Edit /
 * New / disable-enable actions).
 *
 * <p>Gated by the <b>grantable</b> page permissions — {@code EDIT_PAGES} <em>or</em> {@code DISABLE_PAGES} —
 * not the tenant master/wildcard gate: this is the editor's home and the destination the disable/enable
 * actions redirect back to, so any user actually granted a page permission (not only a superadmin) must be
 * able to reach and return to it. The mutating routes each re-enforce their own fine permission.
 *
 * <p>The status column shows where each page stands in the editorial workflow ({@link EditorialService#statuses}):
 * draft, in review, returned, published, or published with a pending revision. The Enable (publish) button is
 * offered only to a user who may publish at the site's level — on a reviewed site that takes {@code
 * APPROVE_PAGES}; everyone else submits from the editor.
 */
@Controller
public class TenantPagesController {

    private final PageStore pages;
    private final SiteResolver siteResolver;
    private final PathOwnership pathOwnership;
    private final EditorialService editorial;
    private final AuthzExpressions authz;

    public TenantPagesController(PageStore pages, SiteResolver siteResolver, PathOwnership pathOwnership,
                                 EditorialService editorial, AuthzExpressions authz) {
        this.pages = pages;
        this.siteResolver = siteResolver;
        this.pathOwnership = pathOwnership;
        this.editorial = editorial;
        this.authz = authz;
    }

    @GetMapping("/__admin/pages")
    @PreAuthorize("@authz.may('EDIT_PAGES') or @authz.may('DISABLE_PAGES')")
    public String list(Model model) {
        Site site = tenancy();
        List<Page> all = pages.findAll(site.id());
        model.addAttribute("pages", all);
        model.addAttribute("statuses", editorial.statuses(site.id(), all));
        model.addAttribute("canPublish", editorial.mayPublish(site, authz.may("APPROVE_PAGES")));
        model.addAttribute("editorialLevel", site.editorial().key());
        // The conflicts panel (the visibility triple's site-wide leg): every URL contested between a page and
        // plugin content (page wins — the content is shadowed) or between two plugins (a loud 500 until an
        // operator resolves it). Empty on almost every site; the template shows the panel only when non-empty.
        model.addAttribute("conflicts", pathOwnership.conflicts(SiteViews.toView(site)));
        model.addAttribute("contentTemplate", "operator/admin/pages");
        model.addAttribute("pageTitleKey", "op.pages.title");
        return "operator/op-layout";
    }

    private Site tenancy() {
        return siteResolver.current()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "no bound tenancy"));
    }
}
