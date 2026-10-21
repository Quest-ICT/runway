package mx.quest.runway.icon;

import java.io.IOException;
import java.io.InputStream;
import mx.quest.runway.event.KernelEventOrder;
import mx.quest.runway.site.SiteChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Seeds a newly created site with the platform <b>default</b> favicon set, so {@code /favicon.ico} (and the
 * SVG / apple-touch / PNG icon slots) no longer {@code 404} on a site that never uploaded one. The per-tenant
 * favicon subsystem — serving ({@link SiteIconEndpoints}), override + generate-from-SVG ({@code IconsController}
 * / {@link IconGenerationService}) and {@code <head>} injection ({@link IconHeadContributor}) — already exists;
 * the only gap was the <em>default</em>, and this closes it the same way {@code TenantSeeder} handles templates:
 * copy a platform default <b>into</b> the tenant at creation, after which the tenant owns its copy (self-contained
 * backup, no live app-resource dependency, and an operator upload/delete on the Icons screen still overrides).
 *
 * <p>Driven off {@link SiteChangedEvent} {@code CREATED} so every create path seeds (operator create + first-run
 * default site). <b>Idempotent</b> — it skips a site that already owns any icon, so it never clobbers a tenant's
 * own upload — and <b>best-effort</b>: a missing default or a render failure is logged, never fatal (the site
 * simply keeps the pre-feature {@code 404} until an icon is uploaded). The default SVG is a neutral generic mark,
 * deliberately NOT the platform's own logo, so tenant sites don't all default to the Runway brand.
 */
@Component
public class TenantIconSeeder {

    private static final Logger log = LoggerFactory.getLogger(TenantIconSeeder.class);
    private static final String DEFAULT_SVG = "seed/site-icons/favicon.svg";

    private final IconGenerationService generator;
    private final SiteIconService icons;

    public TenantIconSeeder(IconGenerationService generator, SiteIconService icons) {
        this.generator = generator;
        this.icons = icons;
    }

    @EventListener
    @Order(KernelEventOrder.SEED)
    public void onSiteChanged(SiteChangedEvent event) {
        // Seed a fresh create only — NOT a backup import (that must stay a faithful copy of its backup, never
        // gaining a default favicon the source lacked). A RESTORE over an existing site fires RESTORED, not
        // CREATED, so it never reaches here either.
        if (event.kind() == SiteChangedEvent.Kind.CREATED && !event.fromBackup()) {
            seedIfAbsent(event.siteId());
        }
    }

    /** Generates the full icon set from the platform default SVG, unless the site already owns an icon. */
    void seedIfAbsent(String siteId) {
        if (!icons.presentSlots(siteId).isEmpty()) {
            return; // the site already owns at least one icon — never clobber a tenant's own upload
        }
        byte[] svg = defaultSvg();
        if (svg == null) {
            return; // no default available — degrade to the pre-feature behaviour (a 404) rather than fail
        }
        try {
            generator.generateFrom(siteId, svg);
            log.debug("seeded the platform default favicon for site '{}'", siteId);
        } catch (Exception e) {
            log.warn("could not seed the default favicon for site '{}' — it will 404 until one is uploaded: {}",
                    siteId, e.toString());
        }
    }

    private byte[] defaultSvg() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(DEFAULT_SVG)) {
            if (in == null) {
                log.warn("platform default favicon '{}' is missing from the classpath", DEFAULT_SVG);
                return null;
            }
            return in.readAllBytes();
        } catch (IOException e) {
            log.warn("could not read the platform default favicon: {}", e.toString());
            return null;
        }
    }
}
