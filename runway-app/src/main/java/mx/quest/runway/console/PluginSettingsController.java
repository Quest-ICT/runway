package mx.quest.runway.console;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mx.quest.runway.authz.AuthorizationService;
import mx.quest.runway.authz.Permission;
import mx.quest.runway.authz.PermissionCatalog;
import mx.quest.runway.log.AppActivityLog;
import mx.quest.runway.plugin.PluginAdminService;
import mx.quest.runway.plugin.PluginDescriptor;
import mx.quest.runway.plugin.PluginSettingDef;
import mx.quest.runway.plugin.PluginSettingsService;
import mx.quest.runway.plugin.SettingValidationException;
import mx.quest.runway.plugin.SitePluginConfigService;
import mx.quest.runway.site.SiteStore;
import mx.quest.runway.security.RunwayUserDetails;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.storage.PathSafety;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The <b>app-level</b> plugin settings form ({@code /__app/plugins/{id}/settings}) — the kernel renders,
 * validates and persists a plugin's declared {@code settings:} schema generically through
 * {@link PluginSettingsService}; the plugin never sees the form. A <b>platform</b> operation (app-level
 * settings are platform config): gated by the plugin's app-realm {@code <id>|settings} permission when it declares
 * one, else to the master/wildcard admin via {@link PlatformAuthority} — see {@code requireSettingsAccess}.
 *
 * <p>Checkbox semantics are translated here (the form layer): an unchecked boolean sends no parameter, so the
 * controller writes an explicit {@code false} for every boolean key, keeping the service's coercion generic. A
 * bad value comes back as a field error (never a 500). Changes apply on the next restart;
 * {@link PluginSettingsService#pendingRestartKeys} and {@code strayKeys} feed the drift warnings.
 */
@Controller
public class PluginSettingsController {

    private final SettingRefusals refusals;
    private final PluginAdminService plugins;
    private final PluginSettingsService settings;
    private final PlatformAuthority platformAuthority;
    private final Environment environment;
    private final AppActivityLog activity;
    private final PermissionCatalog catalog;
    private final AuthorizationService authorization;
    private final SiteStore sites;
    private final SitePluginConfigService sitePluginConfig;

    public PluginSettingsController(PluginAdminService plugins, PluginSettingsService settings,
                                    PlatformAuthority platformAuthority, Environment environment,
                                    AppActivityLog activity, PermissionCatalog catalog,
                                    AuthorizationService authorization, SiteStore sites,
                                    SitePluginConfigService sitePluginConfig, SettingRefusals refusals) {
        this.refusals = refusals;
        this.sites = sites;
        this.sitePluginConfig = sitePluginConfig;
        this.catalog = catalog;
        this.authorization = authorization;
        this.plugins = plugins;
        this.settings = settings;
        this.platformAuthority = platformAuthority;
        this.environment = environment;
        this.activity = activity;
    }

    /**
     * The gate for one plugin's app-level settings. When the plugin declares the app-realm {@code settings}
     * permission point ({@code <id>|settings}), holding it opens the form, as it already opens the plugin's own
     * screens and its Settings-menu entry; the master and wildcard admins hold every permission. Until 1.0.0 the
     * form was master/wildcard-only regardless, so an operator granted {@code protection|settings} could save
     * protection's login panel but not open the page it sits on. A plugin that declares no such point keeps the
     * master/wildcard gate.
     */
    private void requireSettingsAccess(Authentication authentication, String pluginId) {
        if (!PathSafety.isSafeId(pluginId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such plugin");
        }
        Permission declared = catalog.find(PathSafety.compoundKey(pluginId, "settings"))
                .filter(permission -> permission.realm() == Realm.APPLICATION)
                .orElse(null);
        if (declared == null) {
            platformAuthority.require(authentication);
            return;
        }
        boolean allowed = authentication != null
                && authentication.getPrincipal() instanceof RunwayUserDetails principal
                && principal.realm() == Realm.APPLICATION
                && authorization.mayAccess(principal, declared, null);
        if (!allowed) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "this operation needs the '" + declared.key() + "' permission");
        }
    }

    @GetMapping("/__app/plugins/{id}/settings")
    public String form(@PathVariable String id, Authentication authentication, Model model) {
        requireSettingsAccess(authentication, id);
        PluginDescriptor descriptor = descriptorOrThrow(id);
        populate(model, id, descriptor);
        // A plugin's optional APP-realm panel, rendered below the generic form from its own namespace. Realm-scoped
        // (admin-app.html, not the tenant-oriented admin.html) so a per-tenant panel never leaks into this console —
        // the reference is the protection plugin's login-protection panel. Reference: TenantPluginSettingsController.
        if (plugins.hasAppAdminFragment(id)) {
            model.addAttribute("adminFragment", "__plugin__/" + id + "/admin-app");
        }
        model.addAttribute("settingsAction", "/__app/plugins/" + id + "/settings");
        model.addAttribute("settingsBack", "/__app/plugins");
        model.addAttribute("settingsScope", "app");
        model.addAttribute("contentTemplate", "operator/plugin-settings");
        model.addAttribute("pageTitleKey", "op.settings.title");
        return "operator/op-layout";
    }

    @PostMapping("/__app/plugins/{id}/settings")
    public String save(@PathVariable String id, HttpServletRequest request, Authentication authentication,
                       RedirectAttributes redirect) {
        requireSettingsAccess(authentication, id);
        PluginDescriptor descriptor = descriptorOrThrow(id);
        List<PluginSettingDef> schema = descriptor.settings();
        try {
            List<String> dropped = settings.save(id, schema, submittedValues(request, schema));
            activity.record(authentication.getName(), "plugin.settings-changed",
                    dropped.isEmpty() ? id : id + " (cleared " + String.join(", ", dropped) + ")");
            redirect.addFlashAttribute("flash", "Settings saved for '" + id + "' — restart to apply.");
            if (!dropped.isEmpty()) {
                // A secret removed because a setting it is bound to changed (PluginSettingDef.secretBoundTo).
                redirect.addFlashAttribute("secretsDropped", String.join(", ", dropped));
            }
        } catch (SettingValidationException invalid) {
            redirect.addFlashAttribute("error", refusals.describe(invalid));
        } catch (RuntimeException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/__app/plugins/" + id + "/settings";
    }

    /** Builds the submitted map; an unchecked boolean (no param) becomes an explicit {@code false}. */
    static Map<String, String> submittedValues(HttpServletRequest request, List<PluginSettingDef> schema) {
        Map<String, String> submitted = new LinkedHashMap<>();
        for (PluginSettingDef def : schema) {
            String[] values = request.getParameterValues(def.key());
            boolean present = values != null && values.length > 0;
            if ("boolean".equalsIgnoreCase(def.type())) {
                submitted.put(def.key(), present && !"false".equalsIgnoreCase(values[values.length - 1]) ? "true" : "false");
            } else if (present) {
                submitted.put(def.key(), values[values.length - 1]);
            }
        }
        return submitted;
    }

    private void populate(Model model, String id, PluginDescriptor descriptor) {
        List<PluginSettingDef> schema = descriptor.settings();
        model.addAttribute("pluginId", id);
        model.addAttribute("pluginName", descriptor.name() != null ? descriptor.name() : id);
        var rendered = settings.render(id, schema);
        model.addAttribute("fields", rendered.stream().map(SettingField::of).toList());
        model.addAttribute("sections", SettingsForm.build(rendered, descriptor,
                SettingsForm.appValues(descriptor, environment)));
        model.addAttribute("strayKeys", settings.strayKeys(id, schema));
        model.addAttribute("pendingKeys", settings.pendingRestartKeys(id, schema, environment::getProperty));
        model.addAttribute("siteOverrides", siteOverrides(id, descriptor));
    }

    /**
     * One line per per-site setting that inherits an app-level one ({@code inheritsFrom: app:<key>}) and that some
     * sites set to their own value: those sites keep it when the app-level default changes (an
     * informational note, nothing is changed for them).
     *
     * @param label the per-site setting's label key (the plugin's message)
     * @param count how many sites set their own value
     */
    public record SiteOverride(String label, long count) {
    }

    private List<SiteOverride> siteOverrides(String pluginId, PluginDescriptor descriptor) {
        List<SiteOverride> out = new java.util.ArrayList<>();
        for (PluginSettingDef siteDef : descriptor.siteSettings()) {
            String from = siteDef.inheritsFrom();
            if (from == null || !from.startsWith("app:")) {
                continue;
            }
            long count = sites.findAll().stream()
                    .map(site -> sitePluginConfig.config(site.id(), pluginId).get(siteDef.key()))
                    .filter(value -> value != null && !String.valueOf(value).isBlank()
                            && !"inherit".equals(String.valueOf(value)))
                    .count();
            if (count > 0) {
                out.add(new SiteOverride(siteDef.label() != null ? siteDef.label() : siteDef.key(), count));
            }
        }
        return out;
    }

    private PluginDescriptor descriptorOrThrow(String id) {
        return plugins.descriptor(id).orElseThrow(() ->
                new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "no such plugin: " + id));
    }
}
