package mx.quest.runway.site;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** The accepted form of a site's base URL: scheme, host and optional port; nothing else. */
class SiteBaseUrlTest {

    @Test
    void blankMeansDerived() {
        assertNull(SiteBaseUrl.normalize(null));
        assertNull(SiteBaseUrl.normalize("   "));
    }

    @Test
    void storedLowercasedWithoutTrailingSlashPortKept() {
        assertEquals("https://www.example.com", SiteBaseUrl.normalize(" HTTPS://WWW.Example.com/ "));
        assertEquals("http://example.test:8080", SiteBaseUrl.normalize("http://example.test:8080"));
        assertEquals("https://[::1]:8443", SiteBaseUrl.normalize("https://[::1]:8443"));
    }

    @Test
    void anythingButSchemeHostAndPortIsRefused() {
        for (String bad : new String[] {"example.com", "ftp://example.com", "https://", "https://example.com/blog",
                "https://example.com/?a=1", "https://example.com/#top", "https://user@example.com",
                "https://exa mple.com", "javascript:alert(1)", "https://" + "a".repeat(300) + ".com"}) {
            assertThrows(IllegalArgumentException.class, () -> SiteBaseUrl.normalize(bad), bad);
        }
    }
}
