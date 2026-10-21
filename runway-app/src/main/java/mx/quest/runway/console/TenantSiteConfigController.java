package mx.quest.runway.console;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import mx.quest.runway.authz.AuthzExpressions;
import mx.quest.runway.log.SiteLogService;
import mx.quest.runway.page.Page;
import mx.quest.runway.page.PageStore;
import mx.quest.runway.site.EditorialLevel;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteContact;
import mx.quest.runway.site.SiteContactService;
import mx.quest.runway.site.SiteLifecycleService;
import mx.quest.runway.site.SiteParamsService;
import mx.quest.runway.site.SiteResolver;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The tenant console's <b>Site config</b> screen ({@code /__admin/config}): a site manager edits the site's
 * settings in Runway's kernel field set —
 *
 * <ul>
 *   <li><b>display name</b> — the site's human-facing name;</li>
 *   <li><b>languages</b> — the available locales and which one is the default (the default must be among the
 *       available; validated server-side);</li>
 *   <li><b>contact</b> — the site's public contact information ({@link SiteContact}: email, phone, address);</li>
 *   <li><b>template parameters</b> — the site-level {@code params.yml} key/value editor deferred here from the
 *       page editor, reusing the page editor's exact parallel-array param rows, scoped to the site
 *       (a page's own params overlay these at render time);</li>
 *   <li><b>editorial level</b> — {@link EditorialLevel advisory, enforced or reviewed}: how strictly publishing
 *       is governed. Changing it additionally requires {@code APPROVE_PAGES}: the level is what stands between an
 *       editor and the live site, so a user who could otherwise only configure the site must not be able to
 *       switch review off and publish. Without that permission the choice is shown read-only and a posted value
 *       is ignored. A missing value keeps the current level (an older form cannot reset it to advisory).</li>
 * </ul>
 *
 * <p><b>What stays app-controlled, and why.</b> A tenant may <em>not</em> edit its {@code hosts} (host→site
 * routing is a platform concern — a tenant could otherwise hijack another site's host or the app realm) nor the
 * site {@code id} (its folder name and stable identity), nor its lifecycle ({@code status}/maintenance are
 * operator/tenant-maintenance surfaces of their own). Those fields are simply absent from this form and
 * preserved by {@link Site#withConfig}, so a crafted POST cannot change them.
 *
 * <p><b>Isolation.</b> The edited tenancy is <em>always</em> the bound one ({@link SiteResolver#current()}),
 * never a path/param id — so this screen can only ever read and write the current site's own files, and a
 * second site is provably untouched.
 *
 * <p><b>Immediate apply.</b> A name/locale change is persisted via {@link SiteLifecycleService#updateConfig}
 * which publishes a {@code SiteChangedEvent(UPDATED)}; every per-site cache (resolver index, params, SEO, i18n,
 * manifest…) drops the site's entry, so the change is live on the next request. Contact and params evict their
 * own caches on save.
 *
 * <p><b>Audit.</b> A save that changed anything is recorded in the site's audit log as
 * {@code site-config.changed}, naming which parts changed ({@code name}, {@code languages}, {@code contact},
 * {@code params}, and {@code editorial=<level>}) — never the other values, since a template parameter may hold
 * something a site manager would not want copied into a journal; the editorial level is not sensitive and the
 * journal is where a reviewer looks to see when review was switched on or off.
 *
 * <p>Gated by the grantable {@code CONFIGURE_SITE} permission — the same gate as SEO/Icons.
 */
@Controller
public class TenantSiteConfigController {

    private final SiteResolver siteResolver;
    private final SiteLifecycleService lifecycle;
    private final SiteContactService contacts;
    private final SiteParamsService siteParams;
    private final PageStore pages;
    private final MessageSource messages;
    private final SiteLogService siteLog;
    private final AuthzExpressions authz;

    private final mx.quest.runway.platform.EmbedHostService embedHosts;
    private final mx.quest.runway.site.SiteCspService siteCsp;

    public TenantSiteConfigController(SiteResolver siteResolver, SiteLifecycleService lifecycle,
                                      SiteContactService contacts, SiteParamsService siteParams, PageStore pages,
                                      @Qualifier("operatorAwareMessageSource") MessageSource messages,
                                      SiteLogService siteLog, AuthzExpressions authz,
                                      mx.quest.runway.platform.EmbedHostService embedHosts,
                                      mx.quest.runway.site.SiteCspService siteCsp) {
        this.embedHosts = embedHosts;
        this.siteCsp = siteCsp;
        this.siteLog = siteLog;
        this.authz = authz;
        this.siteResolver = siteResolver;
        this.lifecycle = lifecycle;
        this.contacts = contacts;
        this.siteParams = siteParams;
        this.pages = pages;
        this.messages = messages;
    }

    @GetMapping("/__admin/config")
    @PreAuthorize("@authz.may('CONFIGURE_SITE')")
    public String form(Model model) {
        Site site = boundTenancy();
        model.addAttribute("site", site);
        model.addAttribute("effectiveBaseUrl", mx.quest.runway.site.SiteViews.toView(site).baseUrl());
        model.addAttribute("embedHosts", embedHosts.list());
        model.addAttribute("embedChosen", siteCsp.chosen(site.id()));
        model.addAttribute("siteName", site.name());
        model.addAttribute("defaultLocale", site.defaultLocale().toLanguageTag());
        model.addAttribute("locales", site.locales());
        model.addAttribute("contact", contacts.get(site.id()));
        // Site-level template parameters as an ordered entry list, exactly like the page editor's paramEntries
        // (the .th.xml iterates a row per entry, plus a trailing blank row to add one).
        model.addAttribute("paramEntries", List.copyOf(siteParams.get(site.id()).entrySet()));
        model.addAttribute("editorialLevel", site.editorial().key());
        model.addAttribute("editorialLevels", java.util.Arrays.stream(EditorialLevel.values())
                .map(EditorialLevel::key).toList());
        model.addAttribute("mayChangeEditorial", authz.may("APPROVE_PAGES"));
        model.addAttribute("pageTitleKey", "op.config.title");
        model.addAttribute("contentTemplate", "operator/admin/site-config");
        return "operator/op-layout";
    }

    /**
     * Switches the operator's approved embed hosts on or off for this site's public pages. Only approved ids are kept
     * ({@link mx.quest.runway.site.SiteCspService#save}); the change applies on the next page.
     */
    @PostMapping("/__admin/config/embeds")
    @PreAuthorize("@authz.may('CONFIGURE_SITE')")
    public String saveEmbeds(@RequestParam(name = "hosts", required = false) List<String> hosts,
                             org.springframework.security.core.Authentication authentication,
                             RedirectAttributes redirect) {
        Site site = boundTenancy();
        siteCsp.save(site.id(), hosts);
        siteLog.audit(site.id(), authentication == null ? "unknown" : authentication.getName(),
                "site.embeds-changed", String.join(",", siteCsp.chosen(site.id())));
        redirect.addFlashAttribute("flash", messages.getMessage("op.config.embeds.saved", null,
                "Embedded content saved.", LocaleContextHolder.getLocale()));
        return "redirect:/__admin/config";
    }

    @PostMapping("/__admin/config")
    @PreAuthorize("@authz.may('CONFIGURE_SITE')")
    public String save(@RequestParam(required = false) String name,
                       @RequestParam(required = false) String defaultLocale,
                       @RequestParam(name = "locale", required = false) List<String> localeTags,
                       @RequestParam(required = false) String contactEmail,
                       @RequestParam(required = false) String contactPhone,
                       @RequestParam(required = false) String contactAddress,
                       @RequestParam(name = "paramKey", required = false) List<String> paramKeys,
                       @RequestParam(name = "paramValue", required = false) List<String> paramValues,
                       @RequestParam(required = false) String editorial,
                       Authentication authentication, RedirectAttributes redirect) {
        Site site = boundTenancy();
        String actor = authentication == null ? "" : authentication.getName();

        // --- Validate the name + language set BEFORE writing anything (an invalid submit changes nothing). The
        //     rules are shared with the operator's Sites screen (SiteConfigInput), so the two can never disagree.
        //     The default is NOT force-added to the available set — a default outside it is an error to fix. ---
        SiteConfigInput.Valid valid;
        try {
            valid = SiteConfigInput.validate(name, defaultLocale, localeTags);
        } catch (SiteConfigInput.Invalid invalid) {
            return reject(redirect, invalid.messageKey(), invalid.getMessage());
        }
        Locale defaultLoc = valid.defaultLocale();
        List<Locale> available = valid.locales(); // default first, the rest in submitted order
        // The editorial level: only an APPROVE_PAGES holder may change it; a missing value keeps the current one;
        // an unknown value is rejected before anything is written.
        EditorialLevel level = site.editorial();
        if (editorial != null && !editorial.isBlank() && !editorial.equals(site.editorial().key())) {
            EditorialLevel requested = EditorialLevel.parseStrict(editorial).orElse(null);
            if (requested == null) {
                return reject(redirect, "op.config.error.editorial", "Choose a valid editorial level.");
            }
            if (!authz.may("APPROVE_PAGES")) {
                return reject(redirect, "op.config.error.editorialPermission",
                        "Changing the editorial level requires the Approve pages permission.");
            }
            level = requested;
        }

        // --- Persist name + languages (publishes UPDATED → caches evict → live next request). ---
        lifecycle.updateConfig(site.id(), valid.name(), defaultLoc, available);
        List<String> changed = new ArrayList<>();
        if (!valid.name().equals(site.name())) {
            changed.add("name");
        }
        if (!available.equals(site.locales()) || !defaultLoc.equals(site.defaultLocale())) {
            changed.add("languages");
        }
        if (level != site.editorial()) {
            lifecycle.updateEditorial(site.id(), level);
            changed.add("editorial=" + level.key());
        }

        // --- Persist contact + site params (each evicts its own cache). ---
        SiteContact contactBefore = contacts.get(site.id());
        Map<String, String> paramsBefore = siteParams.get(site.id());
        try {
            SiteContact contactAfter = new SiteContact(blankToNull(contactEmail), blankToNull(contactPhone),
                    blankToNull(contactAddress));
            contacts.save(site.id(), contactAfter);
            if (!contactAfter.equals(contactBefore)) {
                changed.add("contact");
            }
            siteParams.save(site.id(), zipParams(paramKeys, paramValues));
            if (!siteParams.get(site.id()).equals(paramsBefore)) {
                changed.add("params");
            }
        } catch (IOException | RuntimeException e) {
            // A params key with a nested value throws IllegalArgumentException from the service; surface it and
            // keep the (already-saved) name/languages. The form re-renders with the flash error.
            auditChanges(site.id(), actor, changed);
            redirect.addFlashAttribute("error", message("op.config.error.save", "Could not save site settings."));
            return "redirect:/__admin/config";
        }
        auditChanges(site.id(), actor, changed);

        // --- Advisory warnings (routing/URLs degrade gracefully, never break). `site` is the pre-save snapshot,
        //     so site.defaultLocale() is the OLD default. ---
        boolean defaultChanged = !defaultLoc.equals(site.defaultLocale());
        List<String> orphaned = pagesLosingLocale(site, available);
        String flash = orphaned.isEmpty()
                ? message("op.config.saved", "Site settings saved.")
                : message("op.config.saved.langWarn", "Saved. Some pages still declare a language you removed: ")
                        + String.join(", ", orphaned);
        if (defaultChanged) {
            // Existing pages keep their URL paths (authored for the previous default), so a slug may no longer
            // match its content language — nudge the operator to review page paths (we never rewrite them).
            flash += " " + message("op.config.saved.defaultLocaleWarn",
                    "You changed the default language — existing pages keep their URL paths, so a page's URL may "
                            + "no longer match its content language. Review each page's Path in the page editor.");
        }
        redirect.addFlashAttribute("flash", flash);
        return "redirect:/__admin/config";
    }

    /**
     * Pages that declare a locale no longer in the site's available set. Such a page is not broken — it falls
     * back to its {@linkplain Page#primaryLocale(Locale) primary locale} — but the operator should know the
     * removed language will no longer route, so we warn (never silently drop, never hard-block). Read-only.
     */
    private List<String> pagesLosingLocale(Site site, List<Locale> available) {
        List<String> orphaned = new ArrayList<>();
        for (Page page : pages.findAll(site.id())) {
            boolean loses = page.locales().stream().anyMatch(loc -> !available.contains(loc));
            if (loses) {
                orphaned.add(page.slug());
            }
        }
        return orphaned;
    }

    /**
     * Pairs the {@code paramKey[]}/{@code paramValue[]} arrays positionally into an ordered map, mirroring the
     * page editor's {@code PageForm.zipParams}: a blank/null key is dropped, a missing value becomes empty, and
     * the last value wins on a duplicate key. {@link SiteParamsService#save} re-sanitizes (trims keys, rejects
     * nested values), so this stays deliberately thin.
     */
    private static Map<String, String> zipParams(List<String> keys, List<String> values) {
        Map<String, String> params = new LinkedHashMap<>();
        if (keys == null) {
            return params;
        }
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            if (key == null || key.isBlank()) {
                continue;
            }
            String value = (values != null && i < values.size() && values.get(i) != null) ? values.get(i) : "";
            params.put(key.trim(), value);
        }
        return params;
    }

    /** Records which parts of the site config a save changed (nothing when it changed nothing). */
    private void auditChanges(String siteId, String actor, List<String> changed) {
        if (!changed.isEmpty()) {
            siteLog.audit(siteId, actor, "site-config.changed", String.join(",", changed));
        }
    }

    private String reject(RedirectAttributes redirect, String key, String fallback) {
        redirect.addFlashAttribute("error", message(key, fallback));
        return "redirect:/__admin/config";
    }

    private Site boundTenancy() {
        return siteResolver.current()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "no bound tenancy"));
    }

    private String message(String key, String fallback) {
        return messages.getMessage(key, null, fallback, LocaleContextHolder.getLocale());
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.strip();
    }
}
