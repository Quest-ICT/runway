package mx.quest.runway.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.io.FileSystemResource;

/** The plugin-asset {@code Cache-Control} parser and the content ETag. */
class PluginAssetCacheControlTest {

    private static String header(String configured) {
        return PluginStaticResourceConfig.cacheControl(configured).getHeaderValue();
    }

    @Test
    void theDefaultAndCommonValuesAreSentAsWritten() {
        assertEquals("max-age=3600, public", header("public, max-age=3600"));
        assertEquals("max-age=300, public, stale-while-revalidate=60",
                header("public, max-age=300, stale-while-revalidate=60"));
        assertEquals("max-age=31536000, public, immutable", header("public, max-age=31536000, immutable"));
        assertEquals("no-cache", header("no-cache"));
        assertEquals("no-store", header("no-store"));
        assertEquals("max-age=60, must-revalidate, private", header("Private, MAX-AGE=60, must-revalidate"));
        assertEquals("max-age=0, s-maxage=120, stale-if-error=30", header("max-age=0, s-maxage=120, stale-if-error=30"));
    }

    @Test
    void aValueSpringCannotExpressFallsBackToTheDefaultRatherThanNoStore() {
        String fallback = "max-age=3600, public";
        assertEquals(fallback, header("public, max-age=soon"), "a bad number");
        assertEquals(fallback, header("public, max-age=-5"), "a negative number");
        assertEquals(fallback, header("public, max-age"), "a missing number");
        assertEquals(fallback, header("public, max-age=60, bogus"), "an unknown directive");
        assertEquals(fallback, header("public"), "no freshness directive");
        assertEquals(fallback, header("no-cache, max-age=60"), "no-cache cannot be combined with max-age");
        assertEquals(fallback, header(""), "blank");
    }

    @Test
    void theEtagFollowsTheContentNotTheFileDate(@TempDir Path dir) throws Exception {
        PluginStaticResourceConfig config = new PluginStaticResourceConfig(
                new StaticListableBeanFactory().getBeanProvider(PluginRuntime.class),
                new StaticListableBeanFactory().getBeanProvider(mx.quest.runway.web.RunwayHttpCacheProperties.class));
        Path file = dir.resolve("app.js");
        Files.writeString(file, "one();");
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(1_760_000_000_000L));
        String first = config.etag(new FileSystemResource(file));
        assertNotNull(first);
        assertEquals(32, first.length(), "128 bits, hex");
        assertEquals(first, config.etag(new FileSystemResource(file)), "stable while the file is unchanged");

        // A plugin upgrade: new bytes of the same length under the same fixed (reproducible-build) date.
        Files.writeString(file, "two();");
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(1_760_000_000_000L));
        PluginStaticResourceConfig afterRestart = new PluginStaticResourceConfig(
                new StaticListableBeanFactory().getBeanProvider(PluginRuntime.class),
                new StaticListableBeanFactory().getBeanProvider(mx.quest.runway.web.RunwayHttpCacheProperties.class));
        assertNotEquals(first, afterRestart.etag(new FileSystemResource(file)),
                "an upgrade (a restart) gets a new ETag even when size and date are unchanged");

        // An operator edit in place moves the file date, so a running node notices it too.
        Files.writeString(file, "three();");
        assertNotEquals(first, config.etag(new FileSystemResource(file)), "an in-place edit changes the ETag");
    }
}
