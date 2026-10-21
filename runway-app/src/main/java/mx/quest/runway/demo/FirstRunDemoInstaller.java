package mx.quest.runway.demo;

import java.util.List;
import java.util.Objects;
import mx.quest.runway.site.SiteStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Installs a bundled demo site <b>once, at startup</b>, when the deployment is configured to — so a fresh install
 * can come up with a ready-made default site (e.g. a demo/eval deployment) without any operator click. This is the
 * unattended counterpart to the operator-opt-in "Install demo site" button; a production install simply leaves it
 * unset.
 *
 * <p>Driven by config (all under {@code runway.demo.first-run}):
 * <ul>
 *   <li>{@code id} — the bundled demo to install; blank ⇒ this component does nothing (the default);</li>
 *   <li>{@code master-username} — the tenant superadmin login (default {@code demo});</li>
 *   <li>{@code master-password} — its password (required when {@code id} is set; the app's Argon2 encoder hashes
 *       it, so no pre-computed hash is needed);</li>
 *   <li>{@code host} — the host to create the site with; blank ⇒ the demo descriptor's default host.</li>
 * </ul>
 *
 * <p><b>Idempotent and safe:</b> it runs on {@link ApplicationReadyEvent} (the context is fully up, so the
 * installer, stores and event listeners are all live), skips when the site already exists (so a restart or upgrade
 * never reinstalls), and a failure is logged without aborting startup. Whether the site becomes the platform
 * default is left to the demo's own {@code default} flag (the installer honours it), not forced here.
 */
@Component
public class FirstRunDemoInstaller {

    private static final Logger log = LoggerFactory.getLogger(FirstRunDemoInstaller.class);

    private final DemoSiteInstaller installer;
    private final SiteStore sites;
    private final String demoId;
    private final String masterUsername;
    private final String masterPassword;
    private final String host;

    public FirstRunDemoInstaller(DemoSiteInstaller installer, SiteStore sites,
                                 @Value("${runway.demo.first-run.id:}") String demoId,
                                 @Value("${runway.demo.first-run.master-username:demo}") String masterUsername,
                                 @Value("${runway.demo.first-run.master-password:}") String masterPassword,
                                 @Value("${runway.demo.first-run.host:}") String host) {
        this.installer = Objects.requireNonNull(installer, "installer");
        this.sites = Objects.requireNonNull(sites, "sites");
        this.demoId = demoId;
        this.masterUsername = masterUsername;
        this.masterPassword = masterPassword;
        this.host = host;
    }

    /** Installs the configured demo once the application is ready; a no-op when unconfigured or already installed. */
    @EventListener(ApplicationReadyEvent.class)
    public void installConfiguredDemo() {
        if (demoId == null || demoId.isBlank()) {
            return; // not configured — the normal case
        }
        String id = demoId.trim();
        if (sites.exists(id)) {
            log.info("first-run demo '{}' already present — skipping", id);
            return;
        }
        if (masterPassword == null || masterPassword.isBlank()) {
            log.warn("runway.demo.first-run.id='{}' is set but no master-password is configured — not installing", id);
            return;
        }
        try {
            List<String> hosts = (host == null || host.isBlank()) ? List.of() : List.of(host.trim());
            installer.install(id, hosts, masterUsername, masterPassword, false);
        } catch (RuntimeException e) {
            // Never let a demo-install problem abort startup — the platform must still come up.
            log.error("first-run install of demo '{}' failed", id, e);
        }
    }
}
