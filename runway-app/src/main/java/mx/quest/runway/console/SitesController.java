package mx.quest.runway.console;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import mx.quest.runway.authz.AuthorizationService;
import mx.quest.runway.authz.Permissions;
import mx.quest.runway.bandwidth.BandwidthService;
import mx.quest.runway.bandwidth.SiteBandwidthQuotas;
import mx.quest.runway.demo.DemoSiteInstaller;
import mx.quest.runway.log.AppActivityLog;
import mx.quest.runway.platform.PlatformSettingsStore;
import mx.quest.runway.security.RunwayUserDetails;
import mx.quest.runway.security.TenantMasterStore;
import mx.quest.runway.security.TenantProvisioning;
import mx.quest.runway.security.User;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteLifecycleService;
import mx.quest.runway.site.SiteStorageCeilings;
import mx.quest.runway.site.SiteStore;
import mx.quest.runway.spi.SiteLoginProtectionControl;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The application-realm <b>Sites</b> screen ({@code /__app/sites}): list the tenancies and their lifecycle
 * state, create a new one (which also provisions its tenant superadmin), edit a site's name, languages and monthly
 * bandwidth cap, and suspend / resume / delete. A thin
 * wiring of the already-tested {@link SiteLifecycleService} + {@link TenantProvisioning}; the mutations are a
 * platform operation gated to the master/wildcard admin ({@link PlatformAuthority}). The read-only list needs
 * {@code VIEW_TENANCIES} — or {@code MANAGE_SITE_HOSTS}, because a delegated host editor edits hosts from this
 * list; neither permission unlocks a lifecycle action.
 */
@Controller
@RequestMapping("/__app/sites")
public class SitesController {

    private final SiteStore sites;
    private final SiteLifecycleService lifecycle;
    private final TenantProvisioning provisioning;
    private final TenantMasterStore masters;
    private final PasswordEncoder passwordEncoder;
    private final PlatformAuthority platformAuthority;
    private final PlatformSettingsStore platform;
    private final mx.quest.runway.site.SeedTemplateStore seedTemplates;
    private final AppActivityLog activity;
    private final SiteLoginProtectionControl siteProtection;
    private final DemoSiteInstaller demoInstaller;
    private final AuthorizationService authz;
    private final MessageSource messages;
    private final SiteBandwidthQuotas quotas;
    private final BandwidthService bandwidth;
    private final SiteStorageCeilings ceilings;

    public SitesController(SiteStore sites, SiteLifecycleService lifecycle, TenantProvisioning provisioning,
                           TenantMasterStore masters, PasswordEncoder passwordEncoder,
                           PlatformAuthority platformAuthority, PlatformSettingsStore platform,
                           mx.quest.runway.site.SeedTemplateStore seedTemplates, AppActivityLog activity,
                           SiteLoginProtectionControl siteProtection, DemoSiteInstaller demoInstaller,
                           AuthorizationService authz,
                           @Qualifier("operatorAwareMessageSource") MessageSource messages,
                           SiteBandwidthQuotas quotas, BandwidthService bandwidth, SiteStorageCeilings ceilings) {
        this.quotas = quotas;
        this.ceilings = ceilings;
        this.bandwidth = bandwidth;
        this.sites = sites;
        this.lifecycle = lifecycle;
        this.provisioning = provisioning;
        this.masters = masters;
        this.passwordEncoder = passwordEncoder;
        this.platformAuthority = platformAuthority;
        this.platform = platform;
        this.seedTemplates = seedTemplates;
        this.activity = activity;
        this.siteProtection = siteProtection;
        this.demoInstaller = demoInstaller;
        this.authz = authz;
        this.messages = messages;
    }

    /**
     * The sites list. Open to {@code VIEW_TENANCIES} (read-only) and {@code MANAGE_SITE_HOSTS} (the per-row host
     * editor lives here); the application superadmin and {@code SYSTEMADMIN} pass both. The lifecycle affordances
     * render only for a platform admin and every POST re-checks.
     */
    @GetMapping
    @PreAuthorize("@authz.may('VIEW_TENANCIES') or @authz.may('MANAGE_SITE_HOSTS')")
    public String list(Model model, Authentication authentication) {
        List<Site> all = sites.findAll();
        // Surface each site's superadmin(s), so the always-present-superadmin invariant is visible at a glance.
        Map<String, String> mastersBySite = new LinkedHashMap<>();
        // Whether each site has login CAPTCHA switched on — drives the per-row "Disable CAPTCHA" rescue affordance
        // (via the SiteLoginProtectionControl SPI; false everywhere when no protection plugin is installed).
        Map<String, Boolean> captchaBySite = new LinkedHashMap<>();
        // Each site's monthly bandwidth cap and where it comes from (its own, the server's, or the platform default).
        Map<String, SiteBandwidthQuotas.Quota> quotaBySite = new LinkedHashMap<>();
        Map<String, String> quotaInputBySite = new LinkedHashMap<>();
        // Each site's storage ceiling for its files (its own, or the platform default).
        Map<String, SiteStorageCeilings.Ceiling> ceilingBySite = new LinkedHashMap<>();
        Map<String, String> ceilingInputBySite = new LinkedHashMap<>();
        Map<String, String> ceilingTextBySite = new LinkedHashMap<>();
        for (Site site : all) {
            SiteStorageCeilings.Ceiling ceiling = ceilings.of(site.id());
            ceilingBySite.put(site.id(), ceiling);
            ceilingInputBySite.put(site.id(), ceiling.source() == SiteStorageCeilings.Source.DEFAULT ? ""
                    : formatQuota(ceiling.bytes()));
            ceilingTextBySite.put(site.id(), mx.quest.runway.bandwidth.Bytes.human(ceiling.bytes()));
            SiteBandwidthQuotas.Quota quota = quotas.of(site.id());
            quotaBySite.put(site.id(), quota);
            quotaInputBySite.put(site.id(), quota.source() == SiteBandwidthQuotas.Source.DEFAULT ? ""
                    : formatQuota(quota.bytes()));
            mastersBySite.put(site.id(), masters.assignedTo(site.id()).stream()
                    .map(User::username).collect(Collectors.joining(", ")));
            captchaBySite.put(site.id(), siteProtection.captchaEnabled(site.id()));
        }
        model.addAttribute("sites", all);
        model.addAttribute("mastersBySite", mastersBySite);
        model.addAttribute("captchaBySite", captchaBySite);
        model.addAttribute("quotaBySite", quotaBySite);
        model.addAttribute("quotaInputBySite", quotaInputBySite);
        long defaultQuota = quotas.defaultBytes();
        model.addAttribute("defaultQuotaText", defaultQuota > 0 ? mx.quest.runway.bandwidth.Bytes.human(defaultQuota)
                : messages.getMessage("op.sites.quota.unlimited", null, "no cap", LocaleContextHolder.getLocale()));
        model.addAttribute("ceilingBySite", ceilingBySite);
        model.addAttribute("ceilingInputBySite", ceilingInputBySite);
        model.addAttribute("ceilingTextBySite", ceilingTextBySite);
        long defaultCeiling = ceilings.defaultBytes();
        model.addAttribute("defaultCeilingText", defaultCeiling > 0
                ? mx.quest.runway.bandwidth.Bytes.human(defaultCeiling)
                : messages.getMessage("op.sites.storage.unlimited", null, "no limit", LocaleContextHolder.getLocale()));
        model.addAttribute("defaultSiteId", platform.get().defaultSite().orElse(""));
        // Whether THIS operator may edit hosts — drives the per-row "Edit hosts" affordance (UX only; the
        // /__app/sites/*/hosts request gate is the real enforcement). The app master passes automatically; a
        // delegated user/role passes via the grantable MANAGE_SITE_HOSTS permission.
        model.addAttribute("canEditHosts",
                authentication != null
                        && authentication.getPrincipal() instanceof RunwayUserDetails principal
                        && authz.mayAccess(principal, Permissions.MANAGE_SITE_HOSTS, null));
        // Whether THIS operator may edit a site's name/languages — the master/wildcard admin, like the other
        // lifecycle actions (UX only; the POST re-checks).
        model.addAttribute("canEditSites", platformAuthority.isPlatformAdmin(authentication));
        model.addAttribute("templateCatalogs", seedTemplates.templates());
        // Bundled demo sites not yet installed — offered for one-click opt-in install below the table.
        model.addAttribute("availableDemos", demoInstaller.available().stream()
                .filter(demo -> !demoInstaller.isInstalled(demo.id())).toList());
        model.addAttribute("contentTemplate", "operator/app/sites");
        model.addAttribute("pageTitleKey", "op.sites.title");
        return "operator/op-layout";
    }

    @PostMapping("/create")
    public String create(@RequestParam String siteId, @RequestParam String name,
                         @RequestParam(required = false) String hosts, @RequestParam String defaultLocale,
                         @RequestParam(required = false) String locales, @RequestParam String masterUsername,
                         @RequestParam String masterPassword, @RequestParam(required = false) String masterEmail,
                         @RequestParam(required = false) String templateId,
                         Authentication authentication, RedirectAttributes redirect) {
        platformAuthority.require(authentication);
        String id = siteId.trim();
        String username = masterUsername.trim();
        try {
            // Reject a duplicate superadmin username up front: the store refuses it too (UsernameTakenException),
            // but only after the site was created, which the rollback below would then have to undo.
            if (masters.findByUsername(username).isPresent()) {
                throw new IllegalArgumentException("'" + username + "' is already a tenant superadmin username");
            }
            Locale primary = Locale.forLanguageTag(defaultLocale.trim());
            List<Locale> localeList = new ArrayList<>();
            localeList.add(primary);
            for (String tag : split(locales)) {
                Locale extra = Locale.forLanguageTag(tag);
                if (!localeList.contains(extra)) {
                    localeList.add(extra);
                }
            }
            lifecycle.create(id, name.trim(), split(hosts), primary, localeList, normalizeTemplate(templateId));
            try {
                provisioning.provisionMaster(UUID.randomUUID().toString(), username,
                        passwordEncoder.encode(masterPassword), id, name.trim(),
                        masterEmail == null ? "" : masterEmail.trim());
            } catch (RuntimeException provisioningFailed) {
                // A tenancy must never be left without a superadmin — roll the just-created site back.
                lifecycle.delete(id);
                throw provisioningFailed;
            }
            activity.record(authentication.getName(), "site.created", id);
            redirect.addFlashAttribute("flash", "Created site '" + id + "' with its superadmin.");
        } catch (RuntimeException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/__app/sites";
    }

    /**
     * Installs a bundled demo site (opt-in): creates it as an independent tenant, copies its content and
     * presentation, provisions its superadmin with the operator-supplied password, and — when the demo declares
     * it {@code default} or the operator ticks the box — points the platform catch-all default at it. Gated to the
     * master/wildcard admin like the other mutations; idempotent (an already-installed id reports and no-ops).
     */
    @PostMapping("/install-demo")
    public String installDemo(@RequestParam String demoId, @RequestParam(required = false) String host,
                              @RequestParam String masterUsername, @RequestParam String masterPassword,
                              @RequestParam(required = false, defaultValue = "false") boolean setAsDefault,
                              Authentication authentication, RedirectAttributes redirect) {
        platformAuthority.require(authentication);
        try {
            String username = masterUsername.trim();
            if (masters.findByUsername(username).isPresent()) {
                throw new IllegalArgumentException("'" + username + "' is already a tenant superadmin username");
            }
            DemoSiteInstaller.Outcome outcome =
                    demoInstaller.install(demoId, split(host), username, masterPassword, setAsDefault);
            if (outcome == DemoSiteInstaller.Outcome.ALREADY_EXISTS) {
                redirect.addFlashAttribute("error", "A site '" + demoId + "' already exists.");
            } else {
                activity.record(authentication.getName(), "demo.installed", demoId);
                redirect.addFlashAttribute("flash", "Installed demo site '" + demoId + "' with its superadmin.");
            }
        } catch (RuntimeException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/__app/sites";
    }

    @PostMapping("/{id}/suspend")
    public String suspend(@PathVariable String id, Authentication authentication, RedirectAttributes redirect) {
        return lifecycleAction(authentication, redirect, () -> lifecycle.suspend(id), "Suspended '" + id + "'.",
                "site.suspended", id);
    }

    @PostMapping("/{id}/resume")
    public String resume(@PathVariable String id, Authentication authentication, RedirectAttributes redirect) {
        return lifecycleAction(authentication, redirect, () -> lifecycle.resume(id), "Resumed '" + id + "'.",
                "site.resumed", id);
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable String id, Authentication authentication, RedirectAttributes redirect) {
        return lifecycleAction(authentication, redirect, () -> lifecycle.delete(id), "Deleted '" + id + "'.",
                "site.deleted", id);
    }

    /**
     * Operator rescue: force a site's login CAPTCHA off (via the {@link SiteLoginProtectionControl} SPI) so a tenant
     * locked out by a misconfigured CAPTCHA can sign in and correct it. Non-destructive — the site's keys are kept.
     * Gated to the master/wildcard admin like the lifecycle actions and recorded in the app activity log.
     */
    @PostMapping("/{id}/disable-captcha")
    public String disableCaptcha(@PathVariable String id, Authentication authentication, RedirectAttributes redirect) {
        return lifecycleAction(authentication, redirect, () -> siteProtection.disableCaptcha(id),
                "Disabled login CAPTCHA for '" + id + "'.", "site.captcha-disabled", id);
    }

    /**
     * Edits a site's routing {@link Site#hosts} from the Sites screen. Gated to the grantable
     * {@code MANAGE_SITE_HOSTS} permission by the {@code /__app/sites/*}/{@code hosts} request gate — so it is
     * delegable to a role/user and the app master always passes — hence <b>no</b> in-controller
     * {@link PlatformAuthority} check, unlike the master-only lifecycle actions. The service normalizes and
     * validates the comma-separated hosts and rejects one already owned by another site; failures surface as a
     * flash error and the page is redisplayed. The same dialog sets the site's base URL ({@link
     * mx.quest.runway.site.SiteBaseUrl}), the origin of its absolute links: it is part of how the site is reached,
     * so the same permission covers it.
     */
    @PostMapping("/{id}/hosts")
    public String updateHosts(@PathVariable String id, @RequestParam(required = false) String hosts,
                             @RequestParam(required = false) String baseUrl,
                             Authentication authentication, RedirectAttributes redirect) {
        try {
            // The base URL rides in the same dialog: absent (an older form) keeps it, blank means derived.
            if (baseUrl == null) {
                lifecycle.updateHosts(id, split(hosts));
            } else {
                lifecycle.updateHosts(id, split(hosts), baseUrl);
            }
            activity.record(authentication.getName(), "site.hosts-updated", id);
            redirect.addFlashAttribute("flash", "Updated hosts for '" + id + "'.");
        } catch (RuntimeException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/__app/sites";
    }

    /**
     * Edits a site's display name and languages from the Sites screen — the same change a site manager makes on
     * their Site config page, so it goes through the same validation ({@link SiteConfigInput}) and the same service
     * call ({@link SiteLifecycleService#updateConfig}: a default-language change re-homes the message bundles and
     * every per-site cache is evicted, so the change is live on the next request). {@code locales} is the full
     * comma-separated list of available languages and must include {@code defaultLocale}. A tenancy lifecycle
     * operation, so gated like suspend/resume to the master/wildcard admin ({@link PlatformAuthority}).
     *
     * <p>{@code quota} is the site's own monthly bandwidth cap ({@link SiteBandwidthQuotas}): a size such as
     * {@code 50GB}, {@code 0} for no cap, blank for the platform default. Absent (the field is disabled because the
     * server sets this site's cap) leaves it alone. It is checked before anything is saved, and applies at once:
     * the over-quota set is recomputed on save, so raising a cap reopens a blocked site immediately.
     *
     * <p>{@code storage} is the site's own storage ceiling for its files ({@link SiteStorageCeilings}), in the same
     * form: a size, {@code 0} for no limit, blank for the platform default. It gates the next upload and never
     * deletes a file. Absent leaves it alone.
     */
    @PostMapping("/{id}/edit")
    public String edit(@PathVariable String id, @RequestParam(required = false) String name,
                       @RequestParam(required = false) String defaultLocale,
                       @RequestParam(required = false) String locales,
                       @RequestParam(required = false) String quota,
                       @RequestParam(required = false) String storage,
                       Authentication authentication, RedirectAttributes redirect) {
        platformAuthority.require(authentication);
        try {
            if (sites.find(id).isEmpty()) {
                throw new SiteConfigInput.Invalid("op.sites.edit.noSuchSite", "No such site: " + id);
            }
            SiteConfigInput.Valid valid = SiteConfigInput.validate(name, defaultLocale,
                    SiteConfigInput.splitTags(locales));
            boolean quotaChanges = quota != null && quotas.hostLock(id).isEmpty();
            Long quotaBytes = quotaChanges ? parseQuota(quota) : null;
            boolean storageChanges = storage != null;
            Long storageBytes = storageChanges ? parseCeiling(storage) : null;
            lifecycle.updateConfig(id, valid.name(), valid.defaultLocale(), valid.locales());
            // The comparison and the write are one atomic change, against the latest platform settings. A site deleted
            // meanwhile gets no cap: its deletion cleared it, and this change must not put it back.
            java.util.concurrent.atomic.AtomicBoolean quotaChanged = new java.util.concurrent.atomic.AtomicBoolean();
            if (quotaChanges) {
                platform.update(current -> {
                    if (java.util.Objects.equals(current.siteQuotas().get(id), quotaBytes) || !sites.exists(id)) {
                        return current;
                    }
                    quotaChanged.set(true);
                    return current.withSiteQuota(id, quotaBytes);
                });
            }
            java.util.concurrent.atomic.AtomicBoolean storageChanged = new java.util.concurrent.atomic.AtomicBoolean();
            if (storageChanges) {
                platform.update(current -> {
                    if (java.util.Objects.equals(current.siteStorageCeilings().get(id), storageBytes)) {
                        return current;
                    }
                    storageChanged.set(true);
                    return current.withSiteStorageCeiling(id, storageBytes);
                });
            }
            if (storageChanged.get()) {
                activity.record(authentication.getName(), "site.storage-ceiling-changed",
                        id + " (" + (storageBytes == null ? "default" : storageBytes + " bytes") + ")");
            }
            if (quotaChanged.get()) {
                bandwidth.refreshQuotas();
                activity.record(authentication.getName(), "site.quota-changed",
                        id + " (" + (quotaBytes == null ? "default" : quotaBytes + " bytes") + ")");
            }
            activity.record(authentication.getName(), "site.updated", id + " (" + valid.locales().stream()
                    .map(Locale::toLanguageTag).collect(Collectors.joining(",")) + ")");
            redirect.addFlashAttribute("flash", messages.getMessage("op.sites.edit.saved", new Object[] {id},
                    "Updated '" + id + "'.", LocaleContextHolder.getLocale()));
        } catch (SiteConfigInput.Invalid invalid) {
            redirect.addFlashAttribute("error", messages.getMessage(invalid.messageKey(), null,
                    invalid.getMessage(), LocaleContextHolder.getLocale()));
        } catch (RuntimeException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/__app/sites";
    }

    /**
     * Parses a cap typed on the Sites form: blank ⇒ {@code null} (the platform default), else a size such as
     * {@code 50GB}, {@code 500MB} or a plain byte count; {@code 0} = no cap. Units are base-1024, as on the Bandwidth
     * screen.
     *
     * @throws SiteConfigInput.Invalid when it is not a size, or is negative
     */
    static Long parseQuota(String text) {
        return parseSize(text, "op.sites.quota.invalid",
                "The bandwidth cap must be a size such as 50GB or 500MB, or 0 for no cap.");
    }

    /**
     * Parses a storage ceiling typed on the Sites form, exactly like {@link #parseQuota}: blank ⇒ {@code null} (the
     * platform default), else a size; {@code 0} = no limit.
     *
     * @throws SiteConfigInput.Invalid when it is not a size, or is negative
     */
    static Long parseCeiling(String text) {
        return parseSize(text, "op.sites.storage.invalid",
                "The storage limit must be a size such as 5GB or 500MB, or 0 for no limit.");
    }

    private static Long parseSize(String text, String invalidKey, String invalidMessage) {
        String compact = text == null ? "" : text.replace(" ", "").trim();
        if (compact.isEmpty()) {
            return null;
        }
        try {
            long bytes = DataSize.parse(compact.toUpperCase(Locale.ROOT).replace("IB", "B")).toBytes();
            if (bytes < 0) {
                throw new IllegalArgumentException("negative");
            }
            return bytes;
        } catch (IllegalArgumentException invalid) {
            throw new SiteConfigInput.Invalid(invalidKey, invalidMessage);
        }
    }

    /** A cap as the form shows it, so that saving the form unchanged stores the same number. */
    static String formatQuota(long bytes) {
        String[] units = {"TB", "GB", "MB", "KB"};
        long[] sizes = {1L << 40, 1L << 30, 1L << 20, 1L << 10};
        for (int i = 0; i < units.length; i++) {
            if (bytes > 0 && bytes % sizes[i] == 0) {
                return (bytes / sizes[i]) + units[i];
            }
        }
        return Long.toString(bytes);
    }

    private String lifecycleAction(Authentication authentication, RedirectAttributes redirect, Runnable action,
                                   String success, String auditAction, String target) {
        platformAuthority.require(authentication);
        try {
            action.run();
            activity.record(authentication.getName(), auditAction, target);
            redirect.addFlashAttribute("flash", success);
        } catch (RuntimeException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/__app/sites";
    }

    private static List<String> split(String csv) {
        List<String> out = new ArrayList<>();
        if (csv != null) {
            for (String token : csv.split(",")) {
                String trimmed = token.trim();
                if (!trimmed.isEmpty()) {
                    out.add(trimmed);
                }
            }
        }
        return out;
    }

    /**
     * Normalizes the picker value to a template id the seeder can trust, or {@code null} (⇒ the default
     * template). Blank, the built-in default, an unsafe id, or an unknown one all become {@code null} — an
     * unvalidated id must never reach the create event and leave a half-provisioned site.
     */
    private String normalizeTemplate(String templateId) {
        if (templateId == null || templateId.isBlank()) {
            return null;
        }
        String t = templateId.trim();
        return (!mx.quest.runway.site.SeedTemplateStore.DEFAULT_ID.equals(t)
                && mx.quest.runway.storage.PathSafety.isSafeId(t)
                && seedTemplates.templateExists(t)) ? t : null;
    }
}
