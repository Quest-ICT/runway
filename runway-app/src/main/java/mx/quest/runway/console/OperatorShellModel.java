package mx.quest.runway.console;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import mx.quest.runway.crypto.SecretCipher;
import mx.quest.runway.mail.PlatformMailer;
import mx.quest.runway.security.RunwayUserDetails;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.web.AppRealmPaths;
import mx.quest.runway.web.RequestPaths;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;

/**
 * Populates the model attributes the {@code op-layout} shell (topbar / nav / locale switcher / footer) needs on
 * <b>every</b> operator page, so a controller only has to set its {@code contentTemplate} + {@code pageTitleKey}.
 *
 * <p>This is the kernel seam for the operator chrome. Its callers:
 * <ul>
 *   <li>{@link OperatorModelAdvice} — a {@code @ControllerAdvice} scoped to {@code mx.quest.runway.console} that
 *       fires for every <b>kernel</b> console controller and delegates here.</li>
 *   <li>{@link ConsoleScreenAdvice} — fires for every controller annotated with the SPI's
 *       {@link mx.quest.runway.spi.service.ConsoleScreen}: how a <b>plugin</b> renders a full operator screen inside
 *       {@code op-layout} (SPI 0.6.0). The plugin returns {@code "operator/op-layout"} with its own
 *       {@code contentTemplate} (a {@code __plugin__/<id>/<name>} template — {@link PluginTemplateResolver}) and
 *       needs no host class.</li>
 * </ul>
 * This class is kernel-internal: no plugin may inject it (a plugin that links a host class is refused from SPI
 * 1.0.0). The essential plugin, too, reaches the shell through {@code @ConsoleScreen}.
 *
 * <p>Extracting this from {@code OperatorModelAdvice} is behaviour-preserving: the advice now delegates and the
 * exact same attributes are set, so kernel screens are unaffected.
 */
@Component
public class OperatorShellModel {

    private final MenuRegistry menuRegistry;
    private final SiteResolver siteResolver;
    private final OperatorProperties operator;
    private final SecretCipher cipher;
    private final PlatformMailer mailer;

    /**
     * The cache-busting token appended to every {@code /__assets/**} reference ({@code ?v=…}) so a redeploy is
     * served from a fresh URL and the 1-day asset cache is bypassed — computed ONCE per process, never bumped by
     * hand, and identical to the {@link AssetVersion} bean's (both come from {@link AssetVersion#of}): the build
     * timestamp when build-info is present (stable across restarts of one jar, distinct per release), else the
     * process start time (dev / no build-info: distinct per restart).
     */
    private final String assetVersion;
    /** The running instance version for the console footer — from build-info, else {@code "dev"} (matches the
     *  About screen's {@code RuntimeInfoService.version()} primary path; a dev/IDE run with no build-info shows
     *  {@code "dev"} in both). */
    private final String appVersion;

    /** Which kernel menu entries the operator may see; {@code null} (the legacy constructor) shows them all. */
    private final ConsoleNavigation navigation;

    /** Without kernel-menu filtering (every kernel entry shown) — kept for callers built before it existed. */
    public OperatorShellModel(MenuRegistry menuRegistry, SiteResolver siteResolver, OperatorProperties operator,
                              SecretCipher cipher, PlatformMailer mailer,
                              ObjectProvider<BuildProperties> buildProperties) {
        this(menuRegistry, siteResolver, operator, cipher, mailer, buildProperties, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public OperatorShellModel(MenuRegistry menuRegistry, SiteResolver siteResolver, OperatorProperties operator,
                              SecretCipher cipher, PlatformMailer mailer,
                              ObjectProvider<BuildProperties> buildProperties, ConsoleNavigation navigation) {
        this.navigation = navigation;
        this.menuRegistry = menuRegistry;
        this.siteResolver = siteResolver;
        this.operator = operator;
        this.cipher = cipher;
        this.mailer = mailer;
        BuildProperties bp = buildProperties.getIfAvailable();
        this.assetVersion = AssetVersion.of(bp); // the same token the AssetVersion bean holds
        this.appVersion = (bp != null && bp.getVersion() != null && !bp.getVersion().isBlank())
                ? bp.getVersion()
                : "dev";
    }

    /** The asset cache-busting token this shell puts in the model as {@code assetVersion}. */
    public String assetVersion() {
        return assetVersion;
    }

    /**
     * Adds every {@code op-layout} shell attribute to {@code model}, choosing the realm chrome from the request
     * path ({@link AppRealmPaths}: the application realm gets the app topbar/menu, everything else — the
     * {@code /__admin} tenant console — the admin topbar/menu). Idempotent and side-effect-free beyond the model;
     * safe to call from either advice.
     *
     * <p>The set is exactly what {@code op-layout.th.xml} reads: {@code currentPath}, {@code appPath}/
     * {@code adminPath} (the realm-prefix-stripped path used for active-nav, kept split to dodge the
     * {@code __…__} preprocessing trap), {@code operatorRealm}, {@code topbarFragment},
     * {@code principalName}, {@code appName}, {@code appVersion}, {@code tenantSiteId}, {@code titleSuffix}
     * (the browser-tab title suffix: the site id on the tenant console, the platform name on the app realm),
     * {@code assetVersion}, {@code operatorLanguages}, {@code uiLocale}, {@code pluginMenu}, {@code pluginSettings},
     * {@code pluginSettingsMenu}, {@code pluginActivityMenu}, and {@code nav} — which kernel menu entries and
     * dropdown groups the operator may see ({@link ConsoleNavigation}).
     *
     * @param model          the view model to populate (never {@code null})
     * @param request        the current request, used to classify the realm and the active-nav path
     * @param authentication the signed-in operator (may be {@code null} on an unauthenticated shell render)
     */
    public void populate(Model model, HttpServletRequest request, Authentication authentication) {
        // The decoded path the page was routed on (RequestPaths): /%5F%5Fapp/sites is the application console, with
        // its own topbar and active entry, exactly like /__app/sites.
        String path = RequestPaths.normalized(request);
        boolean appRealm = AppRealmPaths.isAppRealm(path);
        Realm realm = appRealm ? Realm.APPLICATION : Realm.TENANT;
        String tenancyId = appRealm ? null : siteResolver.current().map(site -> site.id()).orElse(null);
        RunwayUserDetails principal = authentication != null
                && authentication.getPrincipal() instanceof RunwayUserDetails details ? details : null;

        // The language switcher returns to the page as the browser addressed it (still encoded), not the decoded path.
        model.addAttribute("currentPath", request.getRequestURI());
        // The tenant path with the "/__admin" prefix stripped (e.g. "/pages", "" for the dashboard). The grouped
        // admin topbar tests active-nav against THIS, never the full path: an expression that names two or more
        // "/__admin/…" literals would otherwise pair their leading "__" as Thymeleaf's __…__ preprocessing
        // delimiters and fail to parse. Null on the application realm.
        model.addAttribute("adminPath",
                (!appRealm && path.startsWith("/__admin")) ? path.substring("/__admin".length()) : path);
        // The mirror for the application realm's grouped topbar: the path with "/__app" stripped (e.g. "/sites",
        // "" for the dashboard). Same reason as adminPath — two "/__app/…" literals in one active-nav expression
        // would otherwise pair their leading "__" as Thymeleaf __…__ preprocessing.
        model.addAttribute("appPath",
                (appRealm && path.startsWith("/__app")) ? path.substring("/__app".length()) : path);
        model.addAttribute("operatorRealm", appRealm ? "application" : "tenant");
        model.addAttribute("topbarFragment",
                appRealm ? "operator/fragments/app-topbar" : "operator/fragments/admin-topbar");
        model.addAttribute("principalName", authentication == null ? "" : authentication.getName());
        String appName = "Runway";
        model.addAttribute("appName", appName);
        model.addAttribute("appVersion", appVersion);
        // The bound tenant's id, for the admin topbar's "<id> site management" badge (null on the app realm).
        model.addAttribute("tenantSiteId", tenancyId);
        // The browser-tab title suffix (op-layout renders "<page> · <suffix>"). On the TENANT console it is the
        // bound site id, so an operator with several sites' consoles open in separate tabs can tell them apart at
        // a glance ("Backups · northbeam" vs "Backups · nocturne"); on the APPLICATION realm it stays the platform
        // name. Falls back to the platform name if a tenant page ever renders without a resolved site.
        model.addAttribute("titleSuffix", (!appRealm && tenancyId != null) ? tenancyId : appName);
        model.addAttribute("assetVersion", assetVersion);
        // The topbar language switcher: the offered languages and the one currently in force (the language code
        // only, e.g. "en"/"es", so it keys the op.lang.* labels and the active highlight regardless of country).
        model.addAttribute("operatorLanguages", operator.getUiLanguages());
        model.addAttribute("uiLocale", LocaleContextHolder.getLocale().getLanguage());
        // Plugin-contributed TOP-LEVEL menu links for this realm/principal (region: menu; empty when none).
        model.addAttribute("pluginMenu", menuRegistry.menuFor(realm, principal, tenancyId));
        // Plugin settings entries for the console's Settings dropdown, generated from each plugin's settings SCHEMA
        // and linking to the kernel's generic settings form (empty when no plugin declares a schema).
        List<?> pluginSettings = menuRegistry.settingsFor(realm, principal, tenancyId);
        model.addAttribute("pluginSettings", pluginSettings);
        // Plugin screens a plugin targets at a topbar GROUP via region targeting — its OWN console
        // screen rendered under Settings or Activity instead of a top-level item (empty unless a plugin declares
        // one). Distinct from pluginSettings: those are the auto-generated generic-form links; these are the
        // plugin's own custom screens (e.g. the essential protection plugin's login-protection + DoS screens).
        List<?> pluginSettingsMenu = menuRegistry.menuFor(realm, "settings", principal, tenancyId);
        List<?> pluginActivityMenu = menuRegistry.menuFor(realm, "activity", principal, tenancyId);
        model.addAttribute("pluginSettingsMenu", pluginSettingsMenu);
        model.addAttribute("pluginActivityMenu", pluginActivityMenu);
        // Kernel menu entries the operator may open (the same gate as each route), plus whether each dropdown
        // group has anything left to show once the plugin entries targeted at it are counted. The topbar reads
        // nav['<key>'] / nav['grp.<group>']; absent (legacy constructor) means "show everything".
        if (navigation != null) {
            Set<String> pluginGroups = new HashSet<>();
            if (!pluginSettings.isEmpty() || !pluginSettingsMenu.isEmpty()) {
                pluginGroups.add("settings");
            }
            if (!pluginActivityMenu.isEmpty()) {
                pluginGroups.add("activity");
            }
            model.addAttribute("nav", navigation.visibility(realm, authentication, tenancyId, pluginGroups));
        }
        // Persistent platform-misconfiguration warnings, surfaced on EVERY application-realm console page (the
        // designated messages area) so the application operator — the one who can fix them — notices promptly:
        //   • no secret-encryption key ⇒ NO secret (SMTP/CAPTCHA/plugin) setting can be stored at all;
        //   • no platform mail account ⇒ password recovery cannot send a link.
        // App realm only: a tenant manager can act on neither (both are platform config the operator owns).
        model.addAttribute("secretKeyMissing", appRealm && !cipher.isAvailable());
        model.addAttribute("smtpMissing", appRealm && !mailer.isConfigured());
    }
}
