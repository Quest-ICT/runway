package mx.quest.runway.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import mx.quest.runway.bandwidth.BandwidthService;
import mx.quest.runway.bandwidth.SiteBandwidthQuotas;
import mx.quest.runway.platform.PlatformSettingsStore;
import mx.quest.runway.security.PasswordEncoders;
import mx.quest.runway.security.PasswordStrength;
import mx.quest.runway.security.Roles;
import mx.quest.runway.security.User;
import mx.quest.runway.security.UserStore;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteStatus;
import mx.quest.runway.site.SiteStore;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * A site's own monthly bandwidth cap, set in its Edit dialog on the Sites screen:
 * stored in {@code platform.yml}, applied at once (no restart), locked when the server sets it, shown on the row and
 * read-only on the site's own Bandwidth screen. Each test uses its own site, so they are order-independent.
 */
@SpringBootTest
class AppSiteQuotaTest {

    private static final Locale EN = Locale.ENGLISH;
    private static final String PW = "Site-Quota-Secret-9";

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-sitequota");
        var enc = PasswordEncoders.argon2id(PasswordStrength.STANDARD);
        Path appUsers = external.resolve("app-users.yml");
        new UserStore(new YamlStore()).save(appUsers, new User("app-master", "admin", enc.encode(PW), "Admin", "a@x",
                true, true, Realm.APPLICATION, Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        SiteStore sites = new SiteStore(StorageLayout.from(props), new YamlStore());
        for (String id : new String[] {"alpha", "bravo", "charlie", "delta", "echo"}) {
            sites.save(new Site(id, "Site " + id, List.of(id + ".test"), EN, List.of(EN), null, SiteStatus.ACTIVE));
        }
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
        // The server sets charlie's cap itself: the console must show it locked and never overwrite it.
        registry.add("runway.bandwidth.quota.per-site.charlie", () -> "2048");
    }

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private PlatformSettingsStore platform;
    @Autowired
    private SiteBandwidthQuotas quotas;
    @Autowired
    private BandwidthService bandwidth;
    @Autowired
    private SiteStore siteStore;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private MockHttpSession login() throws Exception {
        MvcResult result = mvc.perform(formLogin("/app/login").user("admin").password(PW)).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    private void edit(MockHttpSession session, String id, String quota, String expectFlashKey) throws Exception {
        mvc.perform(post("/__app/sites/" + id + "/edit").session(session).with(csrf())
                        .param("name", "Site " + id).param("defaultLocale", "en").param("locales", "en")
                        .param("quota", quota))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists(expectFlashKey));
    }

    private String sitesPage(MockHttpSession session) throws Exception {
        return mvc.perform(get("/__app/sites").session(session))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void theMasterSetsACapThatIsStoredShownAndPrefilled() throws Exception {
        MockHttpSession admin = login();
        edit(admin, "alpha", "10 GB", "flash");
        assertEquals(10L << 30, platform.get().siteQuotas().get("alpha"), "stored in bytes in platform.yml");
        assertEquals(SiteBandwidthQuotas.Source.SITE, quotas.of("alpha").source());
        String html = sitesPage(admin);
        assertTrue(html.contains("Cap 10.0 GB per 30 days (this site)"), "the row shows the cap and its source");
        assertTrue(html.contains("value=\"10GB\""), "the field shows the cap so an unchanged save keeps it");
    }

    @Test
    void blankReturnsToThePlatformDefaultAndZeroMeansNoCap() throws Exception {
        MockHttpSession admin = login();
        edit(admin, "bravo", "0", "flash");
        assertEquals(0L, platform.get().siteQuotas().get("bravo"));
        assertTrue(sitesPage(admin).contains("No bandwidth cap (this site)"));
        edit(admin, "bravo", "", "flash");
        assertFalse(platform.get().siteQuotas().containsKey("bravo"), "blank removes the site's own cap");
        assertEquals(SiteBandwidthQuotas.Source.DEFAULT, quotas.of("bravo").source());
    }

    @Test
    void anInvalidCapIsRefusedAndNothingChanges() throws Exception {
        MockHttpSession admin = login();
        mvc.perform(post("/__app/sites/echo/edit").session(admin).with(csrf())
                        .param("name", "Renamed").param("defaultLocale", "en").param("locales", "en")
                        .param("quota", "ten gigs"))
                .andExpect(flash().attribute("error",
                        "The bandwidth cap must be a size such as 50GB or 500MB, or 0 for no cap."));
        assertFalse(platform.get().siteQuotas().containsKey("echo"));
        assertEquals("Site echo", siteStore.find("echo").orElseThrow().name(), "not even the name is saved");
    }

    @Test
    void aCapTheServerSetsIsLockedAndNeverOverwritten() throws Exception {
        MockHttpSession admin = login();
        SiteBandwidthQuotas.Quota quota = quotas.of("charlie");
        assertEquals(SiteBandwidthQuotas.Source.HOST, quota.source());
        assertEquals(2048L, quota.bytes());
        String html = sitesPage(admin);
        assertTrue(html.contains("Cap 2.0 KB per 30 days (set on the server)"));
        assertTrue(html.contains("Set on the server in"), "the dialog says where it is set");
        edit(admin, "charlie", "5GB", "flash");
        assertFalse(platform.get().siteQuotas().containsKey("charlie"), "a host-set cap is never stored");
        assertEquals(2048L, quotas.bytesFor("charlie"));
    }

    @Test
    void aChangedCapAppliesAtOnceWithoutARestart() throws Exception {
        MockHttpSession admin = login();
        bandwidth.record("delta", 0, 5000);
        edit(admin, "delta", "1KB", "flash");
        assertTrue(bandwidth.isOverQuota("delta"), "lowering the cap blocks the site's public pages now");
        edit(admin, "delta", "", "flash");
        assertFalse(bandwidth.isOverQuota("delta"), "and raising it reopens them now");
    }

    @Test
    void parseAndFormatRoundTrip() {
        assertNull(SitesController.parseQuota("  "));
        assertEquals(0L, SitesController.parseQuota("0"));
        assertEquals(500L << 20, SitesController.parseQuota("500mb"));
        assertEquals(2L << 30, SitesController.parseQuota("2 GiB"));
        assertEquals(1234L, SitesController.parseQuota("1234"));
        assertThrows(SiteConfigInput.Invalid.class, () -> SitesController.parseQuota("-5GB"));
        assertThrows(SiteConfigInput.Invalid.class, () -> SitesController.parseQuota("1.5GB"));
        for (long bytes : new long[] {0, 1234, 500L << 20, 2L << 30, 3L << 40}) {
            assertEquals(bytes == 0 ? 0L : bytes, SitesController.parseQuota(SitesController.formatQuota(bytes)));
        }
    }
}
