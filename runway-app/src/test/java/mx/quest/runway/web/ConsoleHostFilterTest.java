package mx.quest.runway.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import mx.quest.runway.platform.PlatformSettingsStore;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.site.SiteStore;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** The pieces of {@link ConsoleHostFilter} the end-to-end tests cannot reach cheaply: host names and the log cap. */
@ExtendWith(OutputCaptureExtension.class)
class ConsoleHostFilterTest {

    @Test
    void hostNamesCompareWithoutCaseOrPortAndIpv6KeepsItsAddress() {
        assertEquals("ops.example", ConsoleHostFilter.normalize(" Ops.Example:8443 "));
        assertEquals("ops.example", ConsoleHostFilter.normalize("ops.example"));
        assertEquals("10.0.0.5", ConsoleHostFilter.normalize("10.0.0.5:80"));
        assertEquals("::1", ConsoleHostFilter.normalize("[::1]:8443"));
        assertEquals("::1", ConsoleHostFilter.normalize("[::1]"));
        assertEquals("::1", ConsoleHostFilter.normalize("::1"));
        assertEquals("fe80::1", ConsoleHostFilter.normalize("FE80::1"));
        assertEquals("", ConsoleHostFilter.normalize(null));
        // A list entry written as a URL, or with the root label's trailing dot, is the same host name.
        assertEquals("ops.example", ConsoleHostFilter.normalize("https://Ops.Example/"));
        assertEquals("ops.example", ConsoleHostFilter.normalize("http://ops.example:8443/__app"));
        assertEquals("ops.example", ConsoleHostFilter.normalize("ops.example."));
    }

    @Test
    void theConsolePathsAreExactlyTheConsolesRoutes() {
        for (String path : List.of("/__app", "/__app/", "/__app/sites", "/app/login", "/app/logout",
                "/app/forgot-password", "/app/reset")) {
            assertTrue(AppRealmPaths.isOperatorConsole(path), path);
        }
        for (String path : List.of("/__apple", "/app/login2", "/app", "/app/", "/__admin", "/login",
                "/forgot-password", "/actuator/health", "/", "")) {
            assertFalse(AppRealmPaths.isOperatorConsole(path), path);
        }
        assertFalse(AppRealmPaths.isOperatorConsole(null));
    }

    @Test
    void manyMadeUpHostsAreRefusedButOnlyTheFirstFewAreLogged(@TempDir Path tmp, CapturedOutput output)
            throws Exception {
        StorageProperties props = new StorageProperties();
        props.setExternal(tmp);
        StorageLayout layout = StorageLayout.from(props);
        PlatformSettingsStore platform = new PlatformSettingsStore(layout, new YamlStore());
        SiteResolver resolver = new SiteResolver(new SiteStore(layout, new YamlStore()), platform);
        ConsoleHostFilter filter = new ConsoleHostFilter(resolver, platform, List.of("ops.example"), true);

        int from = output.getAll().length();
        int total = ConsoleHostFilter.MAX_LOGGED_HOSTS + 50;
        for (int i = 0; i < total; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/app/login");
            request.setServletPath("/app/login");
            request.setServerName("scan" + i + ".example");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, new MockFilterChain());
            assertEquals(404, response.getStatus());
        }
        String log = output.getAll().substring(from);
        int lines = log.split("Operator console refused on host", -1).length - 1;
        assertEquals(ConsoleHostFilter.MAX_LOGGED_HOSTS, lines);

        MockHttpServletRequest listed = new MockHttpServletRequest("GET", "/app/login");
        listed.setServletPath("/app/login");
        listed.setServerName("OPS.example");
        MockHttpServletResponse ok = new MockHttpServletResponse();
        filter.doFilter(listed, ok, new MockFilterChain());
        assertEquals(200, ok.getStatus());
    }
}
