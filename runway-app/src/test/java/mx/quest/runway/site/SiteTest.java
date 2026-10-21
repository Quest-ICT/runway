package mx.quest.runway.site;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class SiteTest {

    private static final Locale EN = Locale.ENGLISH;

    @Test
    void normalizesNullsAndDefaultsStatus() {
        Site s = new Site("s", "n", null, EN, null, null, null);
        assertTrue(s.hosts().isEmpty());
        assertTrue(s.locales().isEmpty());
        assertNull(s.baseUrl());
        assertEquals(SiteStatus.ACTIVE, s.status());
        assertTrue(s.active());
        assertFalse(s.suspended());
    }

    @Test
    void normalizesLocalesSoTheDefaultIsAlwaysFirst() {
        Locale es = Locale.of("es");
        Locale fr = Locale.FRENCH;
        // The nocturne case: defaultLocale changed to en but the locales list still has es first (a hand edit).
        Site handEdited = new Site("nocturne", "N", List.of(), EN, List.of(es, EN, fr), null, null);
        assertEquals(EN, handEdited.locales().get(0), "the default leads regardless of the stored order");
        assertEquals(List.of(EN, es, fr), handEdited.locales(), "the remaining locales keep their order");

        // Already default-first: unchanged.
        Site ok = new Site("x", "X", List.of(), es, List.of(es, EN), null, null);
        assertEquals(List.of(es, EN), ok.locales());

        // The default missing from the offered list is repaired (prepended) — default is always offered.
        Site missing = new Site("y", "Y", List.of(), EN, List.of(es, fr), null, null);
        assertEquals(List.of(EN, es, fr), missing.locales(), "a default absent from the list is added, first");

        // Empty stays empty (a site offering no locales).
        assertTrue(new Site("z", "Z", List.of(), EN, List.of(), null, null).locales().isEmpty());
    }

    @Test
    void requiresSafeId() {
        assertThrows(IllegalArgumentException.class, () -> new Site("..", "n", List.of(), EN, List.of(), null, null));
        assertThrows(IllegalArgumentException.class, () -> new Site("a/b", "n", List.of(), EN, List.of(), null, null));
        assertThrows(IllegalArgumentException.class, () -> new Site(null, "n", List.of(), EN, List.of(), null, null));
    }

    @Test
    void requiresNameAndDefaultLocale() {
        assertThrows(NullPointerException.class, () -> new Site("s", null, List.of(), EN, List.of(), null, null));
        assertThrows(NullPointerException.class, () -> new Site("s", "n", List.of(), null, List.of(), null, null));
    }

    @Test
    void collectionsAreCopiedAndUnmodifiable() {
        List<String> hosts = new ArrayList<>(List.of("a.test"));
        Site s = new Site("s", "n", hosts, EN, List.of(EN), null, null);
        hosts.add("b.test");
        assertEquals(1, s.hosts().size());
        assertThrows(UnsupportedOperationException.class, () -> s.hosts().add("c.test"));
        assertThrows(UnsupportedOperationException.class, () -> s.locales().add(Locale.FRENCH));
    }

    @Test
    void withStatusReturnsUpdatedCopy() {
        Site s = new Site("s", "n", List.of("a.test"), EN, List.of(EN), null, SiteStatus.ACTIVE);
        Site suspended = s.withStatus(SiteStatus.SUSPENDED);
        assertTrue(suspended.suspended());
        assertTrue(s.active(), "original is unchanged");
        assertEquals(s.hosts(), suspended.hosts());
    }
}
