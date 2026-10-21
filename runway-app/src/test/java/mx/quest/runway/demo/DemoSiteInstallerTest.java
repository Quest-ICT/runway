package mx.quest.runway.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import mx.quest.runway.platform.PlatformSettingsStore;
import mx.quest.runway.security.TenantMasterStore;
import mx.quest.runway.security.TenantProvisioning;
import mx.quest.runway.security.User;
import mx.quest.runway.security.UserStore;
import mx.quest.runway.site.SiteLifecycleService;
import mx.quest.runway.site.SiteStore;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The bundled-demo installer: materialises a classpath {@code seed/demo/<id>/**} tree into a real, independent
 * tenant.
 *
 * <p>Exercised against a <b>synthetic</b> demo ({@code kettle}) that lives only in test resources. It used to
 * run against a demo that shipped with the product, which coupled a mechanism test to product content — so
 * retiring the bundled demos broke this suite, and it broke only under {@code mvn clean}, because stale
 * resources left in {@code target/classes} had been masking it on incremental runs. The mechanism is the thing
 * under test; what the distribution chooses to bundle is not.
 */
class DemoSiteInstallerTest {

    /** A cheap, deterministic encoder — the real Argon2 is verified elsewhere and is needlessly slow here. */
    private static final PasswordEncoder ENC = new PasswordEncoder() {
        @Override public String encode(CharSequence raw) { return "ENC(" + raw + ")"; }
        @Override public boolean matches(CharSequence raw, String enc) { return enc.equals(encode(raw)); }
    };

    private record Fixture(DemoSiteInstaller installer, SiteStore sites, PlatformSettingsStore platform,
                           TenantMasterStore masters, StorageLayout layout) {
    }

    private static Fixture wire(Path tmp) {
        StorageProperties props = new StorageProperties();
        props.setExternal(tmp);
        StorageLayout layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        SiteStore sites = new SiteStore(layout, yaml);
        ApplicationEventPublisher noEvents = event -> { };
        SiteLifecycleService lifecycle = new SiteLifecycleService(sites, layout, noEvents, new mx.quest.runway.i18n.SiteMessagesStore(layout));
        TenantMasterStore masters = new TenantMasterStore(layout, new UserStore(yaml));
        TenantProvisioning provisioning = new TenantProvisioning(masters);
        PlatformSettingsStore platform = new PlatformSettingsStore(layout, yaml);
        DemoSiteInstaller installer = new DemoSiteInstaller(lifecycle, provisioning, layout, sites, platform, ENC);
        return new Fixture(installer, sites, platform, masters, layout);
    }

    @Test
    void aDemoTreeOnTheClasspathIsDiscovered(@TempDir Path tmp) {
        List<DemoDescriptor> demos = wire(tmp).installer().available();
        assertTrue(demos.stream().anyMatch(d -> d.id().equals("kettle")),
                () -> "the kettle demo tree must be discovered on the classpath; got " + demos.stream().map(DemoDescriptor::id).toList());
    }

    @Test
    void installsAsAnIndependentTenantWithContentMasterAndDefault(@TempDir Path tmp) throws java.io.IOException {
        Fixture f = wire(tmp);

        DemoSiteInstaller.Outcome outcome = f.installer().install("kettle", null, "kettle-admin", "s3cret-demo", false);

        assertEquals(DemoSiteInstaller.Outcome.INSTALLED, outcome);
        assertTrue(f.sites().exists("kettle"), "site record created");

        Path site = f.layout().siteDir("kettle");
        assertTrue(Files.exists(site.resolve("templates/_master.html")), "master presentation copied");
        assertTrue(Files.exists(site.resolve("templates/index.meta.yml")), "home page copied (routable)");
        assertTrue(Files.exists(site.resolve("i18n/messages_es.properties")), "Spanish bundle copied");
        assertTrue(Files.exists(site.resolve("static/css/site.css")), "stylesheet copied");
        assertFalse(Files.exists(site.resolve("demo.yml")), "the descriptor is NOT copied into the tenant");

        // Self-containment survives the copy: whatever a demo ships must render without reaching off-site, so a
        // freshly-installed tenant is correct under a 'self'-only CSP and on an air-gapped host. Asserted as a
        // property of the INSTALL rather than of any particular demo's design.
        String masterHtml = Files.readString(site.resolve("templates/_master.html"));
        assertFalse(masterHtml.contains("fonts.googleapis.com") || masterHtml.contains("fonts.gstatic.com"),
                "an installed demo master must not load fonts from an external host");
        assertFalse(masterHtml.contains("http://") || masterHtml.contains("https://"),
                "an installed demo master must not reference any external URL");

        // SEO+/GEO seed config copies AND deserializes: seo.yml → SiteSeoConfig (the record round-trips), and
        // the discoverability per-site config carries GEO disabled (opt-in) with the representative org data.
        assertTrue(Files.exists(site.resolve("seo.yml")), "seo.yml copied");
        mx.quest.runway.seo.SiteSeoConfig seo =
                new mx.quest.runway.seo.SeoConfigService(f.layout(), new YamlStore()).get("kettle");
        assertEquals("@KettleCo", seo.twitterSite(), "the SEO record deserialized from the seed");
        assertEquals("website", seo.ogType());
        Path disc = site.resolve("plugins/discoverability.yml");
        assertTrue(Files.exists(disc), "discoverability config copied");
        String discYaml = Files.readString(disc);
        assertTrue(discYaml.contains("geo.enabled: false"), "GEO is opt-in (disabled) in the bundled demo");
        assertTrue(discYaml.contains("Kettle & Co"), "org.name carries the site's own data");

        // descriptor default:true → the demo becomes the platform catch-all default
        assertEquals("kettle", f.platform().get().defaultSite().orElse(null));

        // the tenant superadmin was provisioned (global master store), assigned to this tenancy
        // The OPERATOR's chosen username, never the one in the demo descriptor — a demo must ship no credential.
        User master = f.masters().list().stream().filter(u -> u.assignedTo("kettle")).findFirst().orElseThrow();
        assertEquals("kettle-admin", master.username());
    }

    @Test
    void installIsIdempotent(@TempDir Path tmp) {
        Fixture f = wire(tmp);
        assertEquals(DemoSiteInstaller.Outcome.INSTALLED, f.installer().install("kettle", null, "demo", "pw12345", false));
        assertEquals(DemoSiteInstaller.Outcome.ALREADY_EXISTS, f.installer().install("kettle", null, "demo", "pw12345", false));
    }

    @Test
    void firstRunInstallerHonoursConfigIdempotently(@TempDir Path tmp) {
        Fixture f = wire(tmp);
        // Unconfigured (blank id) installs nothing.
        new FirstRunDemoInstaller(f.installer(), f.sites(), "", "demo", "pw", "").installConfiguredDemo();
        assertFalse(f.sites().exists("kettle"), "blank first-run id installs nothing");
        // Configured id installs the demo (and, per its descriptor default:true, makes it the platform default).
        new FirstRunDemoInstaller(f.installer(), f.sites(), "kettle", "demo", "first-run-pw", "kettle.test")
                .installConfiguredDemo();
        assertTrue(f.sites().exists("kettle"), "configured demo installed on first run");
        assertEquals("kettle", f.platform().get().defaultSite().orElse(null));
        // Re-running is a safe no-op (already installed).
        new FirstRunDemoInstaller(f.installer(), f.sites(), "kettle", "demo", "first-run-pw", "kettle.test")
                .installConfiguredDemo();
        assertTrue(f.sites().exists("kettle"));
    }
}
