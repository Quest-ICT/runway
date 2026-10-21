package mx.quest.runway.site;
import mx.quest.runway.platform.TestPlatformSettings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.FilterChain;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SiteContextFilterTest {

    private static final Locale EN = Locale.ENGLISH;

    private static SiteResolver resolverWithAcme(Path tmp) {
        StorageProperties props = new StorageProperties();
        props.setExternal(tmp);
        SiteStore store = new SiteStore(StorageLayout.from(props), new YamlStore());
        store.save(new Site("acme", "Acme", List.of("acme.test"), EN, List.of(EN), null, SiteStatus.ACTIVE));
        return new SiteResolver(store, TestPlatformSettings.empty());
    }

    @Test
    void bindsResolvedSiteDuringRequestThenClears(@TempDir Path tmp) throws Exception {
        SiteResolver resolver = resolverWithAcme(tmp);
        SiteContextFilter filter = new SiteContextFilter(resolver);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServerName("acme.test");
        String[] boundDuringRequest = new String[1];
        FilterChain chain = (req, resp) ->
                boundDuringRequest[0] = resolver.current().map(Site::id).orElse(null);

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertEquals("acme", boundDuringRequest[0], "site is bound while the chain runs");
        assertTrue(resolver.current().isEmpty(), "binding is cleared after the request");
    }

    @Test
    void unresolvedHostLeavesNoBinding(@TempDir Path tmp) throws Exception {
        SiteResolver resolver = resolverWithAcme(tmp);
        SiteContextFilter filter = new SiteContextFilter(resolver);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServerName("unknown.test");
        String[] boundDuringRequest = {"sentinel"};
        FilterChain chain = (req, resp) ->
                boundDuringRequest[0] = resolver.current().map(Site::id).orElse(null);

        filter.doFilter(request, new MockHttpServletResponse(), chain);
        assertNull(boundDuringRequest[0], "no site bound for an unknown host");
    }
}
