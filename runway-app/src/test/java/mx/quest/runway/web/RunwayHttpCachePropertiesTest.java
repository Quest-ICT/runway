package mx.quest.runway.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.mock.env.MockEnvironment;

/**
 * Binding + fallback behaviour of {@link RunwayHttpCacheProperties}: the documented defaults hold with no
 * config, {@code runway.http-cache.*} keys bind, and a blank value falls back to the default rather than
 * emitting an empty {@code Cache-Control} header.
 */
class RunwayHttpCachePropertiesTest {

    @Test
    void defaultsMatchTheDocumentedParityValues() {
        RunwayHttpCacheProperties props = new RunwayHttpCacheProperties();

        assertEquals("public, max-age=300, stale-while-revalidate=60", props.getPublicPages());
        assertEquals("public, max-age=86400", props.getAssets());
        assertEquals("public, max-age=31536000, immutable", props.getImmutableAssets());
        // The *OrDefault accessors return the same values when nothing is overridden.
        assertEquals("public, max-age=300, stale-while-revalidate=60", props.publicPagesOrDefault());
        assertEquals("public, max-age=86400", props.assetsOrDefault());
        assertEquals("public, max-age=31536000, immutable", props.immutableAssetsOrDefault());
        // The default immutable set is the web-font types.
        assertEquals(java.util.Set.of("woff2", "woff", "ttf", "otf", "eot"), props.getImmutableExtensions());
    }

    @Test
    void cacheControlForPicksImmutableOnlyForConfiguredExtensions() {
        RunwayHttpCacheProperties props = new RunwayHttpCacheProperties();

        // Fonts (default immutable set) → immutable; case-insensitive on the extension.
        assertEquals("public, max-age=31536000, immutable", props.cacheControlFor("css/fonts/x.woff2"));
        assertEquals("public, max-age=31536000, immutable", props.cacheControlFor("css/fonts/X.WOFF2"));
        // Mutable / unlisted / no-extension → the 1-day default.
        assertEquals("public, max-age=86400", props.cacheControlFor("css/site.css"));
        assertEquals("public, max-age=86400", props.cacheControlFor("js/app.js"));
        assertEquals("public, max-age=86400", props.cacheControlFor("README"));            // no extension
        assertEquals("public, max-age=86400", props.cacheControlFor("archive.tar/x"));      // dot before the last '/'
    }

    @Test
    void immutableExtensionsAreConfigurableAndNormalized() {
        // An operator whose build fingerprints CSS adds "css"; leading dots / case / blanks are tolerated.
        RunwayHttpCacheProperties props = new RunwayHttpCacheProperties();
        props.setImmutableExtensions(java.util.Set.of(".CSS", " Woff2 ", ""));
        assertEquals(java.util.Set.of("css", "woff2"), props.getImmutableExtensions());
        assertEquals("public, max-age=31536000, immutable", props.cacheControlFor("css/bundle.hash.css"));
        // A type no longer listed falls back to the default.
        assertEquals("public, max-age=86400", props.cacheControlFor("fonts/x.ttf"));
    }

    @Test
    void bindsFromTheRunwayHttpCachePrefix() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("runway.http-cache.public-pages", "public, max-age=60")
                .withProperty("runway.http-cache.assets", "public, max-age=3600")
                // Removed before 1.0 (never read): a file that still sets it must keep binding, not fail.
                .withProperty("runway.http-cache.no-store", "no-store, max-age=0");

        RunwayHttpCacheProperties props = bind(env);

        assertEquals("public, max-age=60", props.getPublicPages());
        assertEquals("public, max-age=3600", props.getAssets());
    }

    @Test
    void aBlankOverrideFallsBackToTheDocumentedDefault() {
        RunwayHttpCacheProperties props = new RunwayHttpCacheProperties();
        props.setPublicPages("   ");
        props.setAssets("");

        assertEquals("public, max-age=300, stale-while-revalidate=60", props.publicPagesOrDefault());
        assertEquals("public, max-age=86400", props.assetsOrDefault());
    }

    private static RunwayHttpCacheProperties bind(MockEnvironment env) {
        return new Binder(ConfigurationPropertySources.get(env))
                .bind("runway.http-cache", RunwayHttpCacheProperties.class)
                .orElseGet(RunwayHttpCacheProperties::new);
    }
}
