package mx.quest.runway.demo;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import mx.quest.runway.platform.PlatformSettingsStore;
import mx.quest.runway.security.TenantProvisioning;
import mx.quest.runway.site.SiteLifecycleService;
import mx.quest.runway.site.SiteStore;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.Yaml;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Installs a <b>bundled demo site</b> into a running Runway instance, through the same public seams an
 * operator create uses — so a demo tenant is a first-class, fully independent site (its own folder,
 * identity, hosts and superadmin), not an opaque clone.
 *
 * <p>Each demo ships as a reviewable, versioned classpath resource tree {@code seed/demo/<id>/**}: a
 * {@code demo.yml} descriptor plus a verbatim mirror of a tenant folder ({@code templates/}, {@code i18n/},
 * {@code static/}, optional {@code plugins/}). Installing one:
 * <ol>
 *   <li>{@link SiteLifecycleService#create creates} the site (which fires the {@code TenantSeeder}, writing
 *       the 5 starter files);</li>
 *   <li>{@linkplain #copyTree copies the demo tree over them}, so the demo's own presentation/content wins;</li>
 *   <li>{@linkplain TenantProvisioning#provisionMaster provisions the tenant superadmin} with an
 *       operator-supplied password (the demo ships no credential);</li>
 *   <li>optionally points the platform {@linkplain PlatformSettingsStore catch-all default} at it.</li>
 * </ol>
 *
 * <p>The operation is <b>idempotent</b>: a demo whose id already exists is left untouched (re-running is
 * safe). It is deliberately <em>not</em> routed through the {@code SeedTemplateStore} born-from-template
 * catalog, whose closed 5-file allowlist cannot express a multi-page demo.
 */
@Service
public class DemoSiteInstaller {

    private static final Logger log = LoggerFactory.getLogger(DemoSiteInstaller.class);

    /** Classpath root of the bundled demo trees. */
    private static final String ROOT = "seed/demo";
    /** The descriptor file — parsed, never copied into the tenant folder. */
    private static final String DESCRIPTOR = "demo.yml";

    private final SiteLifecycleService lifecycle;
    private final TenantProvisioning provisioning;
    private final StorageLayout layout;
    private final SiteStore sites;
    private final PlatformSettingsStore platform;
    private final PasswordEncoder passwordEncoder;
    private final ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    public DemoSiteInstaller(SiteLifecycleService lifecycle, TenantProvisioning provisioning,
                             StorageLayout layout, SiteStore sites, PlatformSettingsStore platform,
                             PasswordEncoder passwordEncoder) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.provisioning = Objects.requireNonNull(provisioning, "provisioning");
        this.layout = Objects.requireNonNull(layout, "layout");
        this.sites = Objects.requireNonNull(sites, "sites");
        this.platform = Objects.requireNonNull(platform, "platform");
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder, "passwordEncoder");
    }

    /** The outcome of an {@link #install} call. */
    public enum Outcome {
        /** The demo was created (and, if requested, made the platform default). */
        INSTALLED,
        /** A site with the demo's id already existed; nothing was changed (idempotent no-op). */
        ALREADY_EXISTS
    }

    /**
     * The bundled demos discovered on the classpath, ordered by id. Never {@code null}. A malformed
     * descriptor is logged and skipped (one bad demo never hides the rest).
     */
    public List<DemoDescriptor> available() {
        List<DemoDescriptor> found = new ArrayList<>();
        Resource[] descriptors;
        try {
            descriptors = resolver.getResources("classpath*:" + ROOT + "/*/" + DESCRIPTOR);
        } catch (IOException e) {
            log.warn("could not scan bundled demo sites", e);
            return List.of();
        }
        for (Resource descriptor : descriptors) {
            try (InputStream in = descriptor.getInputStream()) {
                DemoDescriptor demo = Yaml.read(in, DemoDescriptor.class);
                if (demo != null && demo.id() != null && !demo.id().isBlank()) {
                    found.add(demo);
                }
            } catch (RuntimeException | IOException e) {
                log.warn("skipping malformed demo descriptor {}", safeUri(descriptor), e);
            }
        }
        found.sort((a, b) -> a.id().compareToIgnoreCase(b.id()));
        return List.copyOf(found);
    }

    /** The bundled demo with this id, if any. */
    public Optional<DemoDescriptor> find(String demoId) {
        return available().stream().filter(d -> d.id().equals(demoId)).findFirst();
    }

    /** Whether a site with this id already exists (installed or otherwise). */
    public boolean isInstalled(String demoId) {
        return sites.exists(demoId);
    }

    /**
     * Installs the named demo. Idempotent: returns {@link Outcome#ALREADY_EXISTS} without touching disk
     * when a site with the demo's id already exists.
     *
     * @param demoId         the bundled demo to install
     * @param hostsOverride  the hosts to create it with; {@code null}/empty ⇒ the descriptor's default hosts
     * @param masterUsername the tenant superadmin login; {@code null}/blank ⇒ the descriptor's default
     * @param masterPassword the tenant superadmin password (required, non-blank; hashed here, never stored plain)
     * @param setAsDefault   force pointing the platform catch-all default at this site (the descriptor's
     *                       {@code default: true} also triggers it)
     * @return {@link Outcome#INSTALLED} or {@link Outcome#ALREADY_EXISTS}
     * @throws IllegalArgumentException if the demo id is unknown or the password is blank
     */
    public synchronized Outcome install(String demoId, List<String> hostsOverride, String masterUsername,
                                        String masterPassword, boolean setAsDefault) {
        DemoDescriptor demo = find(demoId)
                .orElseThrow(() -> new IllegalArgumentException("no such bundled demo: " + demoId));
        if (masterPassword == null || masterPassword.isBlank()) {
            throw new IllegalArgumentException("a tenant admin password is required to install a demo");
        }
        if (sites.exists(demo.id())) {
            log.info("demo '{}' already installed — skipping (idempotent)", demo.id());
            return Outcome.ALREADY_EXISTS;
        }

        List<String> hosts = (hostsOverride == null || hostsOverride.isEmpty()) ? demo.hostList() : hostsOverride;
        // 1. create the site (fires TenantSeeder → the 5 starter files) ...
        lifecycle.create(demo.id(), demo.name(), hosts, demo.defaultLocaleOrEnglish(), demo.localeList());
        // 2. ... then overwrite them with the demo's own presentation + content.
        copyTree(demo.id());
        // 3. provision the tenant superadmin (operator password; the demo ships none).
        String username = (masterUsername == null || masterUsername.isBlank())
                ? demo.masterUsername() : masterUsername.trim();
        provisioning.provisionMaster(UUID.randomUUID().toString(), username,
                passwordEncoder.encode(masterPassword), demo.id(), demo.masterDisplayName(), "");
        // 4. optionally make it the platform catch-all default.
        if (setAsDefault || demo.defaultSite()) {
            platform.update(current -> current.withDefaultSiteId(demo.id()));
            log.info("demo '{}' set as the platform default (catch-all) site", demo.id());
        }
        log.info("installed demo site '{}' ({} host(s), master '{}')", demo.id(), hosts.size(), username);
        return Outcome.INSTALLED;
    }

    /**
     * Copies every file of the demo's classpath tree {@code seed/demo/<id>/**} into the tenant folder,
     * overwriting the just-seeded starter files. The {@code demo.yml} descriptor and directory entries are
     * skipped; each target is confined under the site folder (defense in depth, though the source is trusted).
     */
    private void copyTree(String demoId) {
        Path siteDir = layout.siteDir(demoId); // traversal-safe (validates demoId)
        String marker = ROOT + "/" + demoId + "/";
        Resource[] resources;
        try {
            resources = resolver.getResources("classpath*:" + marker + "**");
        } catch (IOException e) {
            throw new UncheckedIOException("scanning demo tree for " + demoId, e);
        }
        int copied = 0;
        for (Resource resource : resources) {
            if (!resource.isReadable()) {
                continue; // directory entries / non-file resources
            }
            String rel = relativePath(resource, marker);
            if (rel == null || rel.isEmpty() || rel.endsWith("/") || rel.equals(DESCRIPTOR)) {
                continue;
            }
            Path target = siteDir.resolve(rel).normalize();
            if (!target.startsWith(siteDir)) {
                log.warn("demo '{}' entry '{}' escapes the site folder — skipped", demoId, rel);
                continue;
            }
            try {
                Files.createDirectories(target.getParent());
                try (InputStream in = resource.getInputStream()) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
                copied++;
            } catch (IOException e) {
                throw new UncheckedIOException("copying demo entry " + rel + " for " + demoId, e);
            }
        }
        if (copied == 0) {
            throw new IllegalStateException("demo '" + demoId + "' has no bundled files on the classpath");
        }
        log.debug("copied {} file(s) for demo '{}'", copied, demoId);
    }

    /** The path of {@code resource} relative to the demo's classpath root, or {@code null} if it can't be derived. */
    private static String relativePath(Resource resource, String marker) {
        try {
            String url = resource.getURL().toString();
            int idx = url.lastIndexOf(marker);
            return idx < 0 ? null : url.substring(idx + marker.length());
        } catch (IOException e) {
            return null;
        }
    }

    private static String safeUri(Resource resource) {
        try {
            return resource.getURL().toString();
        } catch (IOException e) {
            return resource.getDescription();
        }
    }
}
