package mx.quest.runway.console;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import mx.quest.runway.plugin.PluginSettingDef;
import mx.quest.runway.plugin.SettingValidationException;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.storage.PathSafety;
import mx.quest.runway.widget.WidgetInstanceConfigService;
import mx.quest.runway.widget.WidgetInstanceDiscovery;
import mx.quest.runway.widget.WidgetInstanceDiscovery.PlacedWidget;
import mx.quest.runway.widget.WidgetSettingsCatalog;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The tenant console's <b>Content widgets</b> screen ({@code /__admin/widgets}): a site manager configures each
 * content-widget <em>placement</em> a designer anchored into the site's templates. The screen discovers the
 * placements ({@link WidgetInstanceDiscovery}), and for each whose widget declares a per-instance schema
 * ({@link WidgetSettingsCatalog}) renders a typed editor bound to the tenant's {@code widget-instances.yml}
 * ({@link WidgetInstanceConfigService}); a placement whose widget declares no settings is shown as present but
 * not configurable. Changes apply immediately. Gated by the grantable {@code CONFIGURE_SITE} permission; the
 * tenancy is the request's resolved site, never a parameter — a manager only ever configures the site they are
 * signed into.
 */
@Controller
public class WidgetInstancesController {

    private final SettingRefusals refusals;
    private final SiteResolver siteResolver;
    private final WidgetInstanceDiscovery discovery;
    private final WidgetSettingsCatalog catalog;
    private final WidgetInstanceConfigService config;
    private final MessageSource messages;

    public WidgetInstancesController(SiteResolver siteResolver, WidgetInstanceDiscovery discovery,
                                     WidgetSettingsCatalog catalog, WidgetInstanceConfigService config,
                                     @Qualifier("operatorAwareMessageSource") MessageSource messages,
                                     SettingRefusals refusals) {
        this.refusals = refusals;
        this.siteResolver = siteResolver;
        this.discovery = discovery;
        this.catalog = catalog;
        this.config = config;
        this.messages = messages;
    }

    /**
     * A placement row for the screen: its identity and the template(s) it sits in, whether it is configurable,
     * and — when it is — the current form fields and the save action.
     */
    public record InstanceView(String type, String instance, String templates, boolean configurable,
                               List<SettingField> fields, String action) {
    }

    @GetMapping("/__admin/widgets")
    @PreAuthorize("@authz.may('CONFIGURE_SITE')")
    public String list(Model model) {
        String siteId = currentSiteId();
        List<PlacedWidget> placed = discovery.discover(siteId);
        List<InstanceView> instances = new ArrayList<>(placed.size());
        for (PlacedWidget widget : placed) {
            List<PluginSettingDef> schema = catalog.schemaFor(widget.type());
            List<SettingField> fields = schema.isEmpty()
                    ? List.of()
                    : config.render(siteId, widget.type(), widget.instance(), schema).stream()
                            .map(SettingField::of).toList();
            instances.add(new InstanceView(widget.type(), widget.instance(),
                    String.join(", ", widget.templates()), !schema.isEmpty(), fields,
                    "/__admin/widgets/" + widget.type() + "/" + widget.instance()));
        }
        model.addAttribute("instances", instances);
        model.addAttribute("contentTemplate", "operator/admin/widget-instances");
        model.addAttribute("pageTitleKey", "op.widgets.title");
        return "operator/op-layout";
    }

    @PostMapping("/__admin/widgets/{type}/{instance}")
    @PreAuthorize("@authz.may('CONFIGURE_SITE')")
    public String save(@PathVariable String type, @PathVariable String instance, HttpServletRequest request,
                       RedirectAttributes redirect) {
        String siteId = currentSiteId();
        if (!PathSafety.isSafeId(type) || !PathSafety.isSafeId(instance)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid widget instance");
        }
        List<PluginSettingDef> schema = catalog.schemaFor(type);
        if (schema.isEmpty()) {
            // No such configurable widget type — either the widget declares no settings or its plugin is absent.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "widget type '" + type + "' is not configurable");
        }
        try {
            config.save(siteId, type, instance, schema,
                    PluginSettingsController.submittedValues(request, schema));
            redirect.addFlashAttribute("flash",
                    message("op.widgets.saved", "Widget settings saved — applied immediately."));
        } catch (SettingValidationException invalid) {
            redirect.addFlashAttribute("error", refusals.describe(invalid));
        } catch (RuntimeException e) {
            redirect.addFlashAttribute("error", message("op.widgets.failed", "Could not save widget settings."));
        }
        return "redirect:/__admin/widgets";
    }

    private String currentSiteId() {
        return siteResolver.current()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "no bound tenancy"))
                .id();
    }

    private String message(String key, String fallback) {
        return messages.getMessage(key, null, fallback, LocaleContextHolder.getLocale());
    }
}
