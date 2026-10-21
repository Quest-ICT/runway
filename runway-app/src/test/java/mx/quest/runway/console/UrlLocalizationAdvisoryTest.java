package mx.quest.runway.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import mx.quest.runway.page.Page;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteStatus;
import org.junit.jupiter.api.Test;

/**
 * The editor's advisory that flags a multilingual page whose URL path is the same for every locale — the
 * condition that leaves a page's slug in the wrong language after the site's default locale is changed.
 */
class UrlLocalizationAdvisoryTest {

    private static final Locale EN = Locale.ENGLISH;
    private static final Locale ES = Locale.forLanguageTag("es");

    private static Site site(Locale defaultLocale, List<Locale> locales) {
        return new Site("s", "S", List.of(), defaultLocale, locales, null, SiteStatus.ACTIVE);
    }

    private static Page page(Map<String, String> localizedPaths, List<Locale> locales) {
        return page(localizedPaths, locales, "/about");
    }

    private static Page page(Map<String, String> localizedPaths, List<Locale> locales, String path) {
        return new Page("about", path, localizedPaths, locales, Map.of(), Map.of(), "", "_master",
                false, false, null, null);
    }

    @Test
    void singleLocaleSiteIsNeverFlagged() {
        assertFalse(TenantPageEditorController.urlsNotLocalized(
                page(Map.of(), List.of(EN)), site(EN, List.of(EN))));
    }

    @Test
    void multilingualPageWithNoPerLocalePathsIsFlagged() {
        // No localizedPaths → every locale falls back to "/about"; the ES URL is /es/about (English slug).
        assertTrue(TenantPageEditorController.urlsNotLocalized(
                page(Map.of(), List.of(EN, ES)), site(EN, List.of(EN, ES))));
    }

    @Test
    void aSingleLocalePageOnAMultilingualSiteIsNotFlagged() {
        // Scoped to the PAGE's locales: a page offered in only one language has no other-locale URL to
        // mismatch, even though the site itself offers more languages.
        assertFalse(TenantPageEditorController.urlsNotLocalized(
                page(Map.of(), List.of(EN)), site(EN, List.of(EN, ES))));
    }

    @Test
    void aPerLocaleSlugOverrideClearsTheFlag() {
        // es → /acerca-de is a real localized slug, so the page is (at least partly) localized: not flagged.
        assertFalse(TenantPageEditorController.urlsNotLocalized(
                page(Map.of("es", "/acerca-de"), List.of(EN, ES)), site(EN, List.of(EN, ES))));
    }

    @Test
    void theFlagIsIndependentOfWhichLocaleIsDefault() {
        // Same un-localized page, default now ES: still flagged (the shared "/about" slug now serves Spanish).
        assertTrue(TenantPageEditorController.urlsNotLocalized(
                page(Map.of(), List.of(ES, EN)), site(ES, List.of(ES, EN))));
    }

    @Test
    void anUnderscoreInTheDefaultPathIsFlagged() {
        assertEquals("/about_us",
                TenantPageEditorController.underscoreInAnyPath(page(Map.of(), List.of(EN), "/about_us")));
    }

    @Test
    void anUnderscoreInAnyLocalizedPathIsFlagged() {
        assertEquals("/acerca_de", TenantPageEditorController.underscoreInAnyPath(
                page(Map.of("es", "/acerca_de"), List.of(EN, ES), "/about-us")));
    }

    @Test
    void aFullyHyphenatedPathIsNotFlagged() {
        assertNull(TenantPageEditorController.underscoreInAnyPath(
                page(Map.of("es", "/acerca-de"), List.of(EN, ES), "/about-us")));
    }

    // --- Improper characters (spaces / symbols; letters of ANY script are exempt) ---

    @Test
    void aSpaceInTheDefaultPathIsFlaggedAsImproper() {
        assertEquals("/about us",
                TenantPageEditorController.improperCharsInAnyPath(page(Map.of(), List.of(EN), "/about us")));
    }

    @Test
    void aSymbolInAnyLocalizedPathIsFlaggedAsImproper() {
        // '%' passes PathSafety (it is not a control/traversal char) but has to be percent-encoded in a URL.
        assertEquals("/precios%", TenantPageEditorController.improperCharsInAnyPath(
                page(Map.of("es", "/precios%"), List.of(EN, ES), "/pricing")));
    }

    @Test
    void anAccentedLetterIsNotImproper() {
        // An accented letter is a LETTER, so it is not "improper" (spaces/symbols are) — it is reported by the
        // separate, softer non-ASCII advisory below, which can explain the trade-off instead of "don't use it".
        assertNull(TenantPageEditorController.improperCharsInAnyPath(
                page(Map.of("es", "/cafe-y-te"), List.of(EN, ES), "/café")));
    }

    @Test
    void dotSlashDigitsAndHyphenAreNotImproper() {
        assertNull(TenantPageEditorController.improperCharsInAnyPath(
                page(Map.of(), List.of(EN), "/blog/2024/v2.0-notes")));
    }

    // --- Non-ASCII LETTERS (accented / other-script): flagged by their own softer advisory ---

    @Test
    void anAccentedLetterIsFlaggedAsNonAscii() {
        assertEquals("/café",
                TenantPageEditorController.nonAsciiLetterInAnyPath(page(Map.of(), List.of(EN), "/café")));
    }

    @Test
    void anAccentedLocalizedPathIsFlaggedAsNonAscii() {
        assertEquals("/niños", TenantPageEditorController.nonAsciiLetterInAnyPath(
                page(Map.of("es", "/niños"), List.of(EN, ES), "/kids")));
    }

    @Test
    void anAsciiOnlyPathHasNoNonAsciiLetter() {
        assertNull(TenantPageEditorController.nonAsciiLetterInAnyPath(
                page(Map.of(), List.of(EN), "/about-us")));
    }

    @Test
    void aNonAsciiSymbolIsImproperNotANonAsciiLetter() {
        // The euro sign is non-ASCII but NOT a letter: it must be caught by the improper advisory (it has to be
        // encoded), never by the non-ASCII-LETTER advisory — the two never double-report the same character.
        Page p = page(Map.of(), List.of(EN), "/€");
        assertEquals("/€", TenantPageEditorController.improperCharsInAnyPath(p));
        assertNull(TenantPageEditorController.nonAsciiLetterInAnyPath(p));
    }

    // --- Uppercase (case-sensitive routing → duplicate-URL / broken-link risk) ---

    @Test
    void anUppercaseLetterInTheDefaultPathIsFlagged() {
        assertEquals("/About",
                TenantPageEditorController.uppercaseInAnyPath(page(Map.of(), List.of(EN), "/About")));
    }

    @Test
    void anUppercaseLetterInAnyLocalizedPathIsFlagged() {
        assertEquals("/Acerca-De", TenantPageEditorController.uppercaseInAnyPath(
                page(Map.of("es", "/Acerca-De"), List.of(EN, ES), "/about")));
    }

    @Test
    void anAllLowercaseHyphenatedPathHasNoUppercase() {
        assertNull(TenantPageEditorController.uppercaseInAnyPath(
                page(Map.of(), List.of(EN), "/about-us-2024")));
    }

    @Test
    void anAccentedLowercaseLetterIsNotUppercase() {
        // A non-ASCII LOWERCASE letter must never register as uppercase.
        assertNull(TenantPageEditorController.uppercaseInAnyPath(
                page(Map.of(), List.of(EN), "/café")));
    }

    @Test
    void aNonAsciiUppercaseLetterIsFlagged() {
        // The check is uppercase-of-any-script (Character.isUpperCase), not ASCII A–Z: a capitalized accented
        // slug carries the same case-sensitivity/duplicate-URL risk and is flagged.
        assertEquals("/Éxito",
                TenantPageEditorController.uppercaseInAnyPath(page(Map.of(), List.of(EN), "/Éxito")));
    }

    // --- The improper-char check must NOT double-flag what the sibling advisories own (underscore, uppercase) ---

    @Test
    void anUnderscorePathIsNotImproper() {
        assertNull(TenantPageEditorController.improperCharsInAnyPath(page(Map.of(), List.of(EN), "/about_us")));
    }

    @Test
    void anUppercasePathIsNotImproper() {
        assertNull(TenantPageEditorController.improperCharsInAnyPath(page(Map.of(), List.of(EN), "/About")));
    }

    @Test
    void aNullDefaultPathIsHandledByAllPathChecks() {
        Page p = page(Map.of(), List.of(EN), null);
        assertNull(TenantPageEditorController.improperCharsInAnyPath(p));
        assertNull(TenantPageEditorController.nonAsciiLetterInAnyPath(p));
        assertNull(TenantPageEditorController.uppercaseInAnyPath(p));
        assertNull(TenantPageEditorController.underscoreInAnyPath(p));
    }
}
