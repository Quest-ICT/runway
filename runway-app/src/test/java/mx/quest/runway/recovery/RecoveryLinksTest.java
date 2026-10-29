package mx.quest.runway.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import mx.quest.runway.console.AppRecoveryController;
import mx.quest.runway.console.TenantRecoveryController;
import mx.quest.runway.platform.PlatformSettingsStore;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.site.SiteStatus;
import mx.quest.runway.site.SiteStore;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import mx.quest.runway.web.ConsoleHostProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Where a password-reset link points. The request's own host never decides it: a request that names a made-up host
 * (directly, or as {@code X-Forwarded-Host} through a proxy) must not get the platform to mail a real user a link to
 * the sender's site. Checked on {@link RecoveryLinks} and through both recovery controllers, by capturing the link the
 * mail would carry.
 */
class RecoveryLinksTest {

    @TempDir
    Path external;
    private SiteStore sites;
    private PlatformSettingsStore platform;

    @BeforeEach
    void setUp() {
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        sites = new SiteStore(layout, yaml);
        platform = new PlatformSettingsStore(layout, yaml);
        sites.save(site("main", List.of("www.main.test", "main.test"), null));
        sites.save(site("plain", List.of("plain.test"), "http://plain.test:8080"));
        sites.save(site("hostless", List.of(), null));
    }

    private static Site site(String id, List<String> hosts, String baseUrl) {
        return new Site(id, id, hosts, Locale.ENGLISH, List.of(Locale.ENGLISH), baseUrl, SiteStatus.ACTIVE, false,
                null);
    }

    private RecoveryLinks links(String... consoleHosts) {
        ConsoleHostProperties console = new ConsoleHostProperties();
        console.setHosts(List.of(consoleHosts));
        return new RecoveryLinks(sites, platform, console);
    }

    private static MockHttpServletRequest request(String scheme, String host, int port) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/app/forgot-password");
        request.setScheme(scheme);
        request.setServerName(host);
        request.setServerPort(port);
        request.setSecure("https".equals(scheme));
        return request;
    }

    @Test
    void aSiteLinkIsTheSitesOwnAddressAndNeverTheRequests() {
        RecoveryLinks links = links();
        assertThat(links.siteBase(sites.find("main").orElseThrow())).contains("https://www.main.test");
        assertThat(links.siteBase(sites.find("plain").orElseThrow())).contains("http://plain.test:8080");
        assertThat(links.siteBase(sites.find("hostless").orElseThrow())).isEmpty();
    }

    @Test
    void withoutConsoleHostsTheConsoleLinkIsTheDefaultSitesAddress() {
        assertThat(links().appBase(request("https", "evil.example", 443))).isEmpty(); // no default site yet
        platform.update(current -> current.withDefaultSiteId("main"));
        assertThat(links().appBase(request("https", "evil.example", 443))).contains("https://www.main.test");
        assertThat(links().appBase(request("http", "10.0.0.5", 8080))).contains("https://www.main.test");
        platform.update(current -> current.withDefaultSiteId("hostless"));
        assertThat(links().appBase(request("https", "evil.example", 443))).isEmpty();
    }

    @Test
    void withConsoleHostsOnlyAListedHostMakesTheLink() {
        RecoveryLinks links = links("https://Ops.Example.com/", "10.0.0.5");
        assertThat(links.appBase(request("https", "ops.example.com", 443))).contains("https://ops.example.com");
        assertThat(links.appBase(request("http", "10.0.0.5", 8080))).contains("http://10.0.0.5:8080");
        assertThat(links.appBase(request("https", "evil.example", 443))).isEmpty();
        platform.update(current -> current.withDefaultSiteId("main"));
        assertThat(links.appBase(request("https", "evil.example", 443))).isEmpty(); // the list decides alone
    }

    @Test
    void aLoopbackAddressIsNoAddressUnlessABaseUrlSaysSo() {
        sites.save(site("default", List.of("localhost"), null)); // what the setup wizard leaves
        sites.save(site("lab", List.of("127.0.0.1"), null));
        sites.save(site("dev", List.of("localhost"), "http://localhost:8080")); // the operator's own word
        RecoveryLinks links = links();
        assertThat(links.siteBase(sites.find("default").orElseThrow())).isEmpty();
        assertThat(links.siteBase(sites.find("lab").orElseThrow())).isEmpty();
        assertThat(links.siteBase(sites.find("dev").orElseThrow())).contains("http://localhost:8080");
        platform.update(current -> current.withDefaultSiteId("default"));
        assertThat(links.appBase(request("https", "runway.example.com", 443))).isEmpty();
        assertThat(RecoveryLinks.isLoopback("app.localhost")).isTrue();
        assertThat(RecoveryLinks.isLoopback("localhost.example.com")).isFalse();
        assertThat(RecoveryLinks.isLoopback("::1")).isTrue();
    }

    @Test
    void aListedConsoleHostDecidesTheLinksSpellingAndAListedPortItsPort() {
        RecoveryLinks links = links("ops.example.com", "admin.example.com:443", "[2001:db8::5]:8443");
        assertThat(links.appBase(request("https", "Ops.Example.COM.", 443))).contains("https://ops.example.com");
        // Without a listed port the request's stands (the client's Host header, or the proxy's).
        assertThat(links.appBase(request("https", "ops.example.com", 8080))).contains("https://ops.example.com:8080");
        assertThat(links.appBase(request("https", "admin.example.com", 8080))).contains("https://admin.example.com");
        assertThat(links.appBase(request("https", "2001:db8::5", 9999))).contains("https://[2001:db8::5]:8443");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theApplicationConsoleNeverMailsALinkToTheHostTheRequestNamed() {
        platform.update(current -> current.withDefaultSiteId("main"));
        PasswordRecoveryService recovery = Mockito.mock(PasswordRecoveryService.class);
        AppRecoveryController controller = new AppRecoveryController(recovery, links());

        controller.requestLink("root@example.com", Locale.ENGLISH, request("https", "evil.example", 443));

        ArgumentCaptor<Function<String, String>> link = ArgumentCaptor.forClass(Function.class);
        verify(recovery).requestApp(eq("root@example.com"), anyString(), link.capture(), any());
        assertThat(link.getValue().apply("tok")).isEqualTo("https://www.main.test/app/reset?token=tok");
    }

    @Test
    void withNoKnownAddressTheApplicationConsoleSendsNothing() {
        PasswordRecoveryService recovery = Mockito.mock(PasswordRecoveryService.class);
        AppRecoveryController controller = new AppRecoveryController(recovery, links("ops.example.com"));

        String view = controller.requestLink("root@example.com", Locale.ENGLISH,
                request("https", "evil.example", 443));

        assertThat(view).isEqualTo("redirect:/app/forgot-password?sent"); // the same neutral answer
        verify(recovery, never()).requestApp(anyString(), anyString(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aSiteConsoleLinkPointsAtTheSiteItIsFor() {
        PasswordRecoveryService recovery = Mockito.mock(PasswordRecoveryService.class);
        SiteResolver resolver = new SiteResolver(sites, platform);
        TenantRecoveryController controller = new TenantRecoveryController(recovery, resolver, links());
        resolver.bind(sites.find("main").orElseThrow()); // an unknown host falls back to the default site
        try {
            controller.requestLink("editor@example.com", Locale.ENGLISH);
        } finally {
            resolver.clear();
        }
        ArgumentCaptor<Function<String, String>> link = ArgumentCaptor.forClass(Function.class);
        verify(recovery).requestTenant(eq("main"), eq("editor@example.com"), anyString(), link.capture(), any());
        assertThat(link.getValue().apply("tok")).isEqualTo("https://www.main.test/reset?token=tok");

        resolver.bind(sites.find("hostless").orElseThrow());
        try {
            controller.requestLink("editor@example.com", Locale.ENGLISH);
        } finally {
            resolver.clear();
        }
        verify(recovery, never()).requestTenant(eq("hostless"), anyString(), anyString(), any(), any());
    }
}
