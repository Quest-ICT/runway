package mx.quest.runway.console;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import mx.quest.runway.icon.IconGenerationService;
import mx.quest.runway.icon.IconRasterizer.IconRenderException;
import mx.quest.runway.icon.SiteIcon;
import mx.quest.runway.icon.SiteIconService;
import mx.quest.runway.icon.SiteManifest;
import mx.quest.runway.icon.SiteManifestService;
import mx.quest.runway.site.SiteResolver;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The tenant console's icon &amp; manifest screens. {@code /__admin/icons} uploads/removes the fixed icon slots
 * (favicons, apple-touch, PWA icons); {@code /__admin/manifest} edits the Web App Manifest fields — two separate
 * menu entries over the same site config. A manager can upload each slot manually, or <b>generate the whole
 * raster set (apple-touch / 192 / 512 PNG + favicon.ico) plus the SVG favicon from one uploaded SVG</b> (see
 * {@link IconGenerationService}). Both screens gated by {@code CONFIGURE_SITE}; a slot is always resolved through
 * the closed {@link SiteIcon} enum, so no request ever names a file on disk.
 */
@Controller
public class IconsController {

    private final SiteResolver siteResolver;
    private final SiteIconService icons;
    private final SiteManifestService manifests;
    private final IconGenerationService generation;
    private final MessageSource messages;

    public IconsController(SiteResolver siteResolver, SiteIconService icons, SiteManifestService manifests,
                           IconGenerationService generation,
                           @Qualifier("operatorAwareMessageSource") MessageSource messages) {
        this.siteResolver = siteResolver;
        this.icons = icons;
        this.manifests = manifests;
        this.generation = generation;
        this.messages = messages;
    }

    /** A slot's state for the screen: its stable key, public URL, and whether the site has provided it. */
    public record IconSlotView(String key, String url, boolean present) {
    }

    @GetMapping("/__admin/icons")
    @PreAuthorize("@authz.may('CONFIGURE_SITE')")
    public String page(Model model) {
        String siteId = currentSiteId();
        Set<SiteIcon> present = icons.presentSlots(siteId);
        List<IconSlotView> slots = Arrays.stream(SiteIcon.values())
                .map(icon -> new IconSlotView(icon.key(), icon.url(), present.contains(icon)))
                .toList();
        model.addAttribute("slots", slots);
        model.addAttribute("maxKb", icons.maxBytes() / 1024);
        model.addAttribute("cb", System.currentTimeMillis()); // preview cache-buster so a re-upload shows at once
        model.addAttribute("contentTemplate", "operator/admin/icons");
        model.addAttribute("pageTitleKey", "op.icons.title");
        return "operator/op-layout";
    }

    @GetMapping("/__admin/manifest")
    @PreAuthorize("@authz.may('CONFIGURE_SITE')")
    public String manifestPage(Model model) {
        model.addAttribute("manifest", manifests.get(currentSiteId()));
        model.addAttribute("contentTemplate", "operator/admin/web-manifest");
        model.addAttribute("pageTitleKey", "op.manifest.title");
        return "operator/op-layout";
    }

    @PostMapping("/__admin/icons/{slot}/upload")
    @PreAuthorize("@authz.may('CONFIGURE_SITE')")
    public String upload(@PathVariable String slot, @RequestParam("file") MultipartFile file,
                         RedirectAttributes redirect) {
        SiteIcon icon = SiteIcon.fromKey(slot).orElse(null);
        if (icon == null) {
            redirect.addFlashAttribute("error", message("op.icons.unknownSlot", "Unknown icon."));
            return "redirect:/__admin/icons";
        }
        if (file == null || file.isEmpty()) {
            redirect.addFlashAttribute("error", message("op.icons.noFile", "Choose a file."));
            return "redirect:/__admin/icons";
        }
        if (file.getSize() > icons.maxBytes()) {
            redirect.addFlashAttribute("error", message("op.icons.tooLarge", "The icon is too large."));
            return "redirect:/__admin/icons";
        }
        try {
            icons.store(currentSiteId(), icon, file.getBytes());
            redirect.addFlashAttribute("flash", message("op.icons.uploaded", "Icon updated."));
        } catch (IOException | IllegalArgumentException e) {
            redirect.addFlashAttribute("error", message("op.icons.failed", "Could not save the icon."));
        }
        return "redirect:/__admin/icons";
    }

    @PostMapping("/__admin/icons/generate")
    @PreAuthorize("@authz.may('CONFIGURE_SITE')")
    public String generate(@RequestParam("svg") MultipartFile svg, RedirectAttributes redirect) {
        if (svg == null || svg.isEmpty()) {
            redirect.addFlashAttribute("error", message("op.icons.noFile", "Choose an SVG file."));
            return "redirect:/__admin/icons";
        }
        if (svg.getSize() > icons.maxBytes()) {
            redirect.addFlashAttribute("error", message("op.icons.tooLarge", "The SVG is too large."));
            return "redirect:/__admin/icons";
        }
        try {
            Set<SiteIcon> written = generation.generateFrom(currentSiteId(), svg.getBytes());
            redirect.addFlashAttribute("flash", messages.getMessage("op.icons.generated",
                    new Object[]{written.size()}, "Generated " + written.size() + " icons from the SVG.",
                    LocaleContextHolder.getLocale()));
        } catch (IconRenderException e) {
            // Content-based rejection: the file isn't a usable SVG (parse/render failed) — nothing was written.
            redirect.addFlashAttribute("error",
                    message("op.icons.badSvg", "That file could not be read as an SVG image."));
        } catch (IOException | IllegalArgumentException e) {
            redirect.addFlashAttribute("error", message("op.icons.failed", "Could not save the icons."));
        }
        return "redirect:/__admin/icons";
    }

    @PostMapping("/__admin/icons/{slot}/delete")
    @PreAuthorize("@authz.may('CONFIGURE_SITE')")
    public String delete(@PathVariable String slot, RedirectAttributes redirect) {
        SiteIcon icon = SiteIcon.fromKey(slot).orElse(null);
        if (icon != null) {
            try {
                icons.remove(currentSiteId(), icon);
                redirect.addFlashAttribute("flash", message("op.icons.removed", "Icon removed."));
            } catch (IOException e) {
                redirect.addFlashAttribute("error", message("op.icons.failed", "Could not remove the icon."));
            }
        }
        return "redirect:/__admin/icons";
    }

    @PostMapping("/__admin/manifest")
    @PreAuthorize("@authz.may('CONFIGURE_SITE')")
    public String saveManifest(@RequestParam(required = false) String name,
                               @RequestParam(required = false) String shortName,
                               @RequestParam(required = false) String description,
                               @RequestParam(required = false) String themeColor,
                               @RequestParam(required = false) String backgroundColor,
                               @RequestParam(required = false) String display,
                               @RequestParam(required = false) String lang,
                               RedirectAttributes redirect) {
        try {
            manifests.save(currentSiteId(), new SiteManifest(blankToNull(name), blankToNull(shortName),
                    blankToNull(description), HeadFieldRules.colour(themeColor), HeadFieldRules.colour(backgroundColor),
                    HeadFieldRules.display(display), HeadFieldRules.language(lang)));
            redirect.addFlashAttribute("flash", message("op.icons.manifestSaved", "Manifest saved."));
        } catch (SiteConfigInput.Invalid invalid) {
            redirect.addFlashAttribute("error", message(invalid.messageKey(), invalid.getMessage()));
        } catch (IOException e) {
            redirect.addFlashAttribute("error", message("op.icons.failed", "Could not save the manifest."));
        }
        return "redirect:/__admin/manifest";
    }

    private String currentSiteId() {
        return siteResolver.current()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "no bound tenancy"))
                .id();
    }

    private String message(String key, String fallback) {
        return messages.getMessage(key, null, fallback, LocaleContextHolder.getLocale());
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.strip();
    }
}
