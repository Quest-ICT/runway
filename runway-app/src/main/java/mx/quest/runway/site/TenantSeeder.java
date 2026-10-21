package mx.quest.runway.site;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import mx.quest.runway.event.KernelEventOrder;
import mx.quest.runway.site.SeedTemplateStore.SeedFile;
import mx.quest.runway.storage.StorageLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Seeds a newly created tenant with a <b>starter presentation</b> — a styled master template and a published
 * home page — copied into the site's own {@code templates/} folder. This realizes the multi-tenant rule that
 * application-provided defaults are <em>seed templates only</em>: after creation the tenant <b>owns its copy</b>
 * (edits never propagate back, and the platform can never restyle it), so the site is self-contained and renders
 * on its own from the first moment.
 *
 * <p>The bytes come from {@link SeedTemplateStore}, so an operator's edits on the app-realm <b>Site templates</b>
 * screen ({@code external/seed/site/<name>}) are honored, falling back to the platform default baked into the
 * classpath. This affects only sites created <b>after</b> an edit — existing sites already own their copy.
 *
 * <p>Without this a fresh tenant has an empty folder — no home page and no master, so born-from-master is
 * unavailable and {@code /} 404s until an operator hand-authors a page. Seeding gives every new site a
 * working, editable starting point.
 *
 * <p>Driven off {@link SiteChangedEvent} {@code CREATED} (published synchronously by {@link
 * SiteLifecycleService#create}), so <b>every</b> path that creates a site seeds — the operator "create site"
 * action and the first-run default site alike — without widening the lifecycle service's contract. It is
 * <b>idempotent</b> (skips a site that already has a master, so it never clobbers a tenant's own work) and
 * <b>best-effort</b> (a seed failure is logged, never fatal — a site without its seed is still usable).
 */
@Component
public class TenantSeeder {

    private static final Logger log = LoggerFactory.getLogger(TenantSeeder.class);
    private static final String TEMPLATES_DIR = "templates";

    private final StorageLayout layout;
    private final SeedTemplateStore seeds;

    public TenantSeeder(StorageLayout layout, SeedTemplateStore seeds) {
        this.layout = layout;
        this.seeds = seeds;
    }

    @EventListener
    @Order(KernelEventOrder.SEED)
    public void onSiteChanged(SiteChangedEvent event) {
        if (event.kind() == SiteChangedEvent.Kind.CREATED) {
            seed(event.siteId(), event.templateId());
        }
    }

    /** Seeds from the built-in default template (kept for callers/tests that don't choose one). */
    public void seed(String siteId) {
        seed(siteId, SeedTemplateStore.DEFAULT_ID);
    }

    /**
     * Copies the chosen template's starter files into {@code siteId}'s templates folder, unless it already has a
     * master. A {@code null}/blank or unknown {@code templateId} falls back to the built-in default, so seeding is
     * never blocked by a since-deleted template.
     */
    public void seed(String siteId, String templateId) {
        Path templates = layout.siteDir(siteId).resolve(TEMPLATES_DIR);
        if (Files.exists(templates.resolve("_master.html"))) {
            return; // already has presentation — never overwrite the tenant's own copy
        }
        String template = (templateId == null || templateId.isBlank() || !seeds.templateExists(templateId))
                ? SeedTemplateStore.DEFAULT_ID : templateId;
        if (!template.equals(templateId) && templateId != null && !templateId.isBlank()) {
            log.warn("seed template '{}' for site '{}' is unavailable — using the default", templateId, siteId);
        }
        try {
            Files.createDirectories(templates);
            for (SeedFile file : SeedTemplateStore.FILES) {
                Files.write(templates.resolve(file.name()), seeds.read(template, file.name()));
            }
            log.info("seeded starter templates for site '{}' from template '{}'", siteId, template);
        } catch (IOException | UncheckedIOException e) {
            log.warn("could not seed starter templates for site '{}' (the site is still usable)", siteId, e);
        }
    }
}
