package mx.quest.runway.page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteStatus;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PageRoutingTest {

    private static final Locale EN = Locale.ENGLISH;
    private static final Site SITE =
            new Site("acme", "Acme", List.of("acme.test"), EN, List.of(EN), null, SiteStatus.ACTIVE);

    private static PageRouting routingIn(Path external) {
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        return new PageRouting(new PageStore(StorageLayout.from(props), new YamlStore()));
    }

    private static void writeMeta(Path external, String slug, String yaml) throws IOException {
        Path file = external.resolve("sites").resolve("acme").resolve("templates").resolve(slug + ".meta.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, yaml, StandardCharsets.UTF_8);
    }

    @Test
    void findsPublishedPageByPath(@TempDir Path tmp) throws IOException {
        writeMeta(tmp, "about", "path: /about\n");
        assertEquals("about", routingIn(tmp).find(SITE, "/about").orElseThrow().slug());
    }

    @Test
    void findsRootPage(@TempDir Path tmp) throws IOException {
        writeMeta(tmp, "home", "path: /\n");
        assertEquals("home", routingIn(tmp).find(SITE, "/").orElseThrow().slug());
    }

    @Test
    void missingPathIsEmpty(@TempDir Path tmp) throws IOException {
        writeMeta(tmp, "about", "path: /about\n");
        assertTrue(routingIn(tmp).find(SITE, "/nope").isEmpty());
    }

    @Test
    void draftIsNotFound(@TempDir Path tmp) throws IOException {
        writeMeta(tmp, "secret", "path: /secret\ndraft: true\n");
        assertTrue(routingIn(tmp).find(SITE, "/secret").isEmpty());
    }

    // --- urlMap (by-id link helper) ----------------------------------------------------------

    private static final Locale ES = Locale.of("es");
    private static final Site BILINGUAL =
            new Site("acme", "Acme", List.of("acme.test"), EN, List.of(EN, ES), null, SiteStatus.ACTIVE);

    @Test
    void urlMapLinksByIdToTheLocaleCorrectUrl(@TempDir Path tmp) throws IOException {
        writeMeta(tmp, "about", "path: /about\nlocales: [en, es]\nlocalizedPaths:\n  es: /acerca-de\n");
        PageRouting routing = routingIn(tmp);

        assertEquals("/about", routing.urlMap(BILINGUAL, EN).get("about"));
        assertEquals("/es/acerca-de", routing.urlMap(BILINGUAL, ES).get("about"), "links follow localized slugs");
    }

    @Test
    void urlMapFallsBackToPrimaryWhenTheTargetLacksTheLocale(@TempDir Path tmp) throws IOException {
        writeMeta(tmp, "enonly", "path: /enonly\nlocales: [en]\n");
        // Linking from a Spanish render to an English-only page resolves to its primary (en) URL, never a 404.
        assertEquals("/enonly", routingIn(tmp).urlMap(BILINGUAL, ES).get("enonly"));
    }

    @Test
    void urlMapExcludesDrafts(@TempDir Path tmp) throws IOException {
        writeMeta(tmp, "secret", "path: /secret\ndraft: true\n");
        assertTrue(routingIn(tmp).urlMap(BILINGUAL, EN).isEmpty());
    }

    // ---- 410 Gone --------------------------------------------------------------------------------

    /** The whole point: an unpublished page the operator marked as removed is reported as gone. */
    @Test
    void anUnpublishedPageMarkedRemovedIsGone(@TempDir Path tmp) throws IOException {
        writeMeta(tmp, "old", "path: /old\ndraft: true\ngone: true\n");
        assertTrue(routingIn(tmp).isGone(SITE, "/old", EN));
    }

    /**
     * An ordinary unpublish stays a {@code 404}.
     *
     * <p>This is the case that keeps the feature honest: pulling a page to correct it is the common reason to
     * unpublish, and answering {@code 410} for that would tell search engines to drop a URL the site fully
     * intends to bring back.
     */
    @Test
    void anOrdinaryUnpublishIsNotGone(@TempDir Path tmp) throws IOException {
        writeMeta(tmp, "old", "path: /old\ndraft: true\n");
        assertFalse(routingIn(tmp).isGone(SITE, "/old", EN));
    }

    /**
     * A <b>published</b> page is never gone, even with the flag set.
     *
     * <p>The flag is a choice about what the unpublished state answers, not a switch that takes a live page
     * off the air — leaving it set on a page that is republished must be harmless, because that is exactly
     * what an operator who used it once will do.
     */
    @Test
    void aLivePageIsNeverGoneEvenWithTheFlagSet(@TempDir Path tmp) throws IOException {
        writeMeta(tmp, "old", "path: /old\ngone: true\n");
        assertFalse(routingIn(tmp).isGone(SITE, "/old", EN));
        assertTrue(routingIn(tmp).find(SITE, "/old").isPresent(), "and it still serves");
    }

    /** A tombstone answers for its own address only — a near-miss is still a 404, not a guess. */
    @Test
    void aTombstoneDoesNotAnswerForNeighbouringPaths(@TempDir Path tmp) throws IOException {
        writeMeta(tmp, "old", "path: /old\ndraft: true\ngone: true\n");
        PageRouting routing = routingIn(tmp);
        assertFalse(routing.isGone(SITE, "/old/", EN));
        assertFalse(routing.isGone(SITE, "/older", EN));
        assertFalse(routing.isGone(SITE, "", EN));
    }
}
