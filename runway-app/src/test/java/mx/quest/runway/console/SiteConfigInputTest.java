package mx.quest.runway.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/** The shared site name + language validation used by both the tenant Site config and the app Sites screen. */
class SiteConfigInputTest {

    private static final Locale EN = Locale.ENGLISH;
    private static final Locale ES = Locale.forLanguageTag("es");
    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");

    @Test
    void aValidSubmissionIsTrimmedDeduplicatedAndListsTheDefaultFirst() {
        SiteConfigInput.Valid valid = SiteConfigInput.validate("  Acme ", " es ",
                List.of("en", "pt-BR", "es", "en"));
        assertEquals("Acme", valid.name());
        assertEquals(ES, valid.defaultLocale());
        assertEquals(List.of(ES, EN, PT_BR), valid.locales(), "default first, the rest in submitted order, no dupes");
    }

    @Test
    void blankAndMalformedTagsAreSkipped() {
        SiteConfigInput.Valid valid = SiteConfigInput.validate("A", "en", Arrays.asList("", null, "en", "!!", " "));
        assertEquals(List.of(EN), valid.locales());
    }

    @Test
    void aBlankOrNullNameIsRefused() {
        assertEquals("op.config.error.blankName", assertThrows(SiteConfigInput.Invalid.class,
                () -> SiteConfigInput.validate("  ", "en", List.of("en"))).messageKey());
        assertEquals("op.config.error.blankName", assertThrows(SiteConfigInput.Invalid.class,
                () -> SiteConfigInput.validate(null, "en", List.of("en"))).messageKey());
    }

    @Test
    void aMissingOrMalformedDefaultIsRefused() {
        assertEquals("op.config.error.noDefault", assertThrows(SiteConfigInput.Invalid.class,
                () -> SiteConfigInput.validate("A", "", List.of("en"))).messageKey());
        assertEquals("op.config.error.noDefault", assertThrows(SiteConfigInput.Invalid.class,
                () -> SiteConfigInput.validate("A", "!!", List.of("en"))).messageKey());
    }

    @Test
    void aDefaultOutsideTheAvailableSetIsRefusedNotSilentlyAdded() {
        assertEquals("op.config.error.defaultNotAvailable", assertThrows(SiteConfigInput.Invalid.class,
                () -> SiteConfigInput.validate("A", "fr", List.of("en", "es"))).messageKey());
        assertEquals("op.config.error.defaultNotAvailable", assertThrows(SiteConfigInput.Invalid.class,
                () -> SiteConfigInput.validate("A", "en", null)).messageKey());
    }

    @Test
    void splitTagsBreaksACommaListAndDropsBlanks() {
        assertEquals(List.of("en", "es", "pt-BR"), SiteConfigInput.splitTags(" en, ,es ,pt-BR,"));
        assertEquals(List.of(), SiteConfigInput.splitTags(null));
    }
}
