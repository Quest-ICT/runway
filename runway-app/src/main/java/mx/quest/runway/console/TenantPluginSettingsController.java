package mx.quest.runway.console;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import mx.quest.runway.authz.AuthorizationService;
import mx.quest.runway.authz.Permission;
import mx.quest.runway.authz.PermissionCatalog;
import mx.quest.runway.log.SiteLogService;
import mx.quest.runway.plugin.PluginAdminService;
import mx.quest.runway.plugin.PluginDescriptor;
import mx.quest.runway.plugin.PluginSettingDef;
import mx.quest.runway.plugin.SettingValidationException;
import mx.quest.runway.plugin.SitePluginConfigService;
import mx.quest.runway.security.RunwayUserDetails;
import mx.quest.runway.site.SiteResolver;
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
 * The <b>per-tenant</b> plugin settings form ({@code /__admin/plugins/{id}/settings}, tenant realm) — the kernel
 * renders the plugin's declared {@code siteSettings:} schema generically over the bound tenancy's
 * {@code sites/<id>/plugins/<plugin>.yml} (via {@link SitePluginConfigService}); changes apply <b>immediately</b>
 * (cache evicted + change event). Gated by the plugin's {@code <id>|settings} permission when it declares one,
 * else to the tenancy's administrators ({@link TenantAuthority}) — see {@code requireSettingsAccess}; the tenancy
 * is the request's resolved site, never a path argument, so a tenant manager only ever configures the tenancy
 * they are signed into. Reuses the same shared form template as the app-level settings.
 */
@Controller
public class TenantPluginSettingsController {

    private final SettingRefusals refusals;
    private final PluginAdminService plugins;
    private final SitePluginConfigService config;
    private final TenantAuthority tenantAuthority;
    private final SiteResolver siteResolver;
    private final Environment environment;
    private final SiteLogService siteLog;
    private final PermissionCatalog catalog;
    private final AuthorizationService authorization;

    public TenantPluginSettingsController(PluginAdminService plugins, SitePluginConfigService config,
                                          TenantAuthority tenantAuthority, SiteResolver siteResolver,
                                          Environment environment, SiteLogService siteLog,
                                          PermissionCatalog catalog, AuthorizationService authorization, SettingRefusals refusals) {
        this.refusals = refusals;
        this.siteLog = siteLog;
        this.plugins = plugins;
        this.config = config;
        this.tenantAuthority = tenantAuthority;
        this.siteResolver = siteResolver;
        this.environment = environment;
        this.catalog = catalog;
        this.authorization = authorization;
    }

    /**
     * The gate for one plugin's settings in the bound tenancy. When the plugin declares the reserved tenant-realm
     * {@code settings} permission point ({@code <id>|settings}), that permission is what opens the form — the same
     * rule the console's Settings menu already used to show the link, so a user granted it no longer meets a 403
     * behind a visible entry. The tenancy's master and wildcard admins hold every permission, so they keep access.
     * A plugin that declares no such point keeps the historical gate: the tenancy's administrators only.
     */
    private void requireSettingsAccess(Authentication authentication, String tenancyId, String pluginId) {
        if (!PathSafety.isSafeId(pluginId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such plugin");
        }
        Permission declared = catalog.find(PathSafety.compoundKey(pluginId, "settings"))
                .filter(permission -> permission.realm() == Realm.TENANT)
                .orElse(null);
        if (declared == null) {
            tenantAuthority.require(authentication, tenancyId);
            return;
        }
        boolean allowed = authentication != null
                && authentication.getPrincipal() instanceof RunwayUserDetails principal
                && principal.realm() == Realm.TENANT
                && authorization.mayAccess(principal, declared, tenancyId);
        if (!allowed) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "this operation needs the '" + declared.key() + "' permission in this site");
        }
    }

    @GetMapping("/__admin/plugins/{id}/settings")
    public String form(@PathVariable String id, Authentication authentication, Model model) {
        String tenancyId = tenancy();
        requireSettingsAccess(authentication, tenancyId, id);
        PluginDescriptor descriptor = descriptorOrThrow(id);
        List<PluginSettingDef> schema = descriptor.siteSettings();

        model.addAttribute("pluginName", descriptor.name() != null ? descriptor.name() : id);
        var rendered = config.render(tenancyId, id, schema);
        model.addAttribute("fields", rendered.stream().map(SettingField::of).toList());
        // Sections apply the descriptor's presentation keys; "inherit" and platform switches read the plugin's
        // RUNNING app-level values, because that is what this tenant's site is actually getting.
        model.addAttribute("sections", SettingsForm.build(rendered, descriptor,
                SettingsForm.appValues(descriptor, environment)));
        model.addAttribute("pendingKeys", List.of());  // per-tenant config applies immediately — no restart drift
        model.addAttribute("strayKeys", List.of());     // unknown keys are preserved, not flagged
        model.addAttribute("settingsAction", "/__admin/plugins/" + id + "/settings");
        // No standalone Site-settings landing any more — plugin settings hang off the console's Settings dropdown;
        // Back/Cancel returns to the tenant dashboard.
        model.addAttribute("settingsBack", "/__admin");
        model.addAttribute("settingsScope", "tenant");
        // A plugin's optional status/actions panel, rendered below the form from its own namespace.
        if (plugins.hasAdminFragment(id)) {
            model.addAttribute("adminFragment", "__plugin__/" + id + "/admin");
        }
        model.addAttribute("contentTemplate", "operator/plugin-settings");
        model.addAttribute("pageTitleKey", "op.settings.title");
        return "operator/op-layout";
    }

    @PostMapping("/__admin/plugins/{id}/settings")
    public String save(@PathVariable String id, HttpServletRequest request, Authentication authentication,
                       RedirectAttributes redirect) {
        String tenancyId = tenancy();
        requireSettingsAccess(authentication, tenancyId, id);
        List<PluginSettingDef> schema = descriptorOrThrow(id).siteSettings();
        try {
            List<String> dropped = config.save(tenancyId, id, schema,
                    PluginSettingsController.submittedValues(request, schema));
            // Only WHICH plugin changed is recorded — never the values (a setting may be a secret). A secret the
            // save removed (a setting it is bound to changed) is named by key, which reveals nothing.
            siteLog.audit(tenancyId, authentication.getName(), "plugin.settings-changed",
                    dropped.isEmpty() ? id : id + " (cleared " + String.join(", ", dropped) + ")");
            redirect.addFlashAttribute("flash", "Settings saved for '" + id + "' — applied immediately.");
            if (!dropped.isEmpty()) {
                redirect.addFlashAttribute("secretsDropped", String.join(", ", dropped));
            }
        } catch (SettingValidationException invalid) {
            redirect.addFlashAttribute("error", refusals.describe(invalid));
        } catch (RuntimeException e) {
            redirect.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/__admin/plugins/" + id + "/settings";
    }

    private String tenancy() {
        return siteResolver.current()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "no bound tenancy"))
                .id();
    }

    private PluginDescriptor descriptorOrThrow(String id) {
        return plugins.descriptor(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such plugin: " + id));
    }
}
