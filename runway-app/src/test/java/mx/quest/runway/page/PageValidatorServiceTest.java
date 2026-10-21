package mx.quest.runway.page;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteStatus;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The validator folds every render input, so a change to any of them changes the ETag — and only then. */
class PageValidatorServiceTest {

    private static final Locale EN = Locale.ENGLISH;
    private static final Locale ES = Locale.of("es");

    private static final Site SITE =
            new Site("acme", "Acme", List.of("acme.test"), EN, List.of(EN), "https://acme.test", SiteStatus.ACTIVE);
    private static final Page PAGE =
            new Page("home", "/home", Map.of(), List.of(EN), Map.of(), Map.of(), "", "_master", false, false, null, null);

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    private static StorageLayout seed(Path tmp) throws IOException {
        StorageProperties props = new StorageProperties();
        props.setExternal(tmp);
        StorageLayout layout = StorageLayout.from(props);
        Path site = layout.siteDir("acme");
        write(site.resolve("templates").resolve("home.html"), "<html><body>home</body></html>");
        write(site.resolve("templates").resolve("home.th.xml"), "<?xml version=\"1.0\"?><thlogic></thlogic>");
        write(site.resolve("templates").resolve("home.meta.yml"), "path: /home\nlocales: [en]\n");
        write(site.resolve("templates").resolve("_master.html"), "<html><head></head><body>m</body></html>");
        write(site.resolve("i18n").resolve("messages.properties"), "a=1\n");
        write(site.resolve("i18n").resolve("messages_en.properties"), "a=one\n");
        write(site.resolve("site.yml"), "id: acme\nname: Acme\n");
        return layout;
    }

    @Test
    void stableForUnchangedInputs(@TempDir Path tmp) throws IOException {
        PageValidatorService service = new PageValidatorService(seed(tmp));
        assertEquals(service.compute(SITE, PAGE, EN).etag(), service.compute(SITE, PAGE, EN).etag());
    }

    @Test
    void theQueryStringFoldsIntoTheEtag(@TempDir Path tmp) throws IOException {
        PageValidatorService service = new PageValidatorService(seed(tmp));
        String noQuery = service.compute(SITE, PAGE, EN).etag();
        // Blank/null query = the stable no-query ETag (ordinary pages keep full caching — no dependence added).
        assertEquals(noQuery, service.compute(SITE, PAGE, EN, null).etag());
        assertEquals(noQuery, service.compute(SITE, PAGE, EN, "").etag());
        // Distinct queries → distinct ETags, so a query-aware widget's output can't be served from another
        // query's cache (a ?q=cats results page must not 304 to a ?q=dogs render).
        String cats = service.compute(SITE, PAGE, EN, "q=cats").etag();
        String dogs = service.compute(SITE, PAGE, EN, "q=dogs").etag();
        assertNotEquals(noQuery, cats, "a query changes the ETag");
        assertNotEquals(cats, dogs, "distinct queries get distinct ETags");
    }

    @Test
    void etagChangesWhenThePageContentChanges(@TempDir Path tmp) throws IOException {
        StorageLayout layout = seed(tmp);
        PageValidatorService service = new PageValidatorService(layout);
        String before = service.compute(SITE, PAGE, EN).etag();
        write(layout.siteDir("acme").resolve("templates").resolve("home.html"), "<html><body>EDITED</body></html>");
        assertNotEquals(before, service.compute(SITE, PAGE, EN).etag());
    }

    @Test
    void etagChangesWhenTheBundleChanges(@TempDir Path tmp) throws IOException {
        StorageLayout layout = seed(tmp);
        PageValidatorService service = new PageValidatorService(layout);
        String before = service.compute(SITE, PAGE, EN).etag();
        write(layout.siteDir("acme").resolve("i18n").resolve("messages_en.properties"), "a=changed\n");
        assertNotEquals(before, service.compute(SITE, PAGE, EN).etag());
    }

    /**
     * The site record is folded as loaded — not {@code site.yml}'s bytes. The render reads the loaded
     * record, which a hand edit changes only at the next site reload; folding the file moved the ETag at once and
     * put the old body under the new ETag until then.
     */
    @Test
    void theSiteRecordIsFoldedNotTheSiteYmlFile(@TempDir Path tmp) throws IOException {
        StorageLayout layout = seed(tmp);
        PageValidatorService service = new PageValidatorService(layout);
        String before = service.compute(SITE, PAGE, EN).etag();

        write(layout.siteDir("acme").resolve("site.yml"), "id: acme\nname: Renamed\n");
        assertEquals(before, service.compute(SITE, PAGE, EN).etag(), "a file edit alone changes nothing served");

        Site renamed = SITE.withConfig("Renamed", EN, List.of(EN));
        assertNotEquals(before, service.compute(renamed, PAGE, EN).etag(), "the applied record moves it");
        Site rehosted = new Site("acme", "Acme", List.of("acme.test"), EN, List.of(EN), "https://www.acme.test",
                SiteStatus.ACTIVE);
        assertNotEquals(before, service.compute(rehosted, PAGE, EN).etag(), "every field counts (base URL)");
        assertEquals(before, service.compute(new Site("acme", "Acme", List.of("acme.test"), EN, List.of(EN),
                "https://acme.test", SiteStatus.ACTIVE), PAGE, EN).etag(), "an equal record folds equally");
    }

    // ---- 1.0.0-rc1: the site's chrome templates — every template that is not a page ----------------------------

    /** A template a master or page may pull in by name: the ETag of every page of the site must move with it. */
    @Test
    void everyNonPageTemplateIsAnInput(@TempDir Path tmp) throws IOException {
        StorageLayout layout = seed(tmp);
        Path t = layout.siteDir("acme").resolve("templates");
        PageValidatorService service = new PageValidatorService(layout);
        java.util.Set<String> seen = new java.util.HashSet<>();
        String etag = service.compute(SITE, PAGE, EN).etag();
        seen.add(etag);
        // Each step changes one chrome input; each must yield an ETag never seen before.
        for (String[] step : new String[][] {
                {"_nav.html", "<nav>1</nav>"},               // a fragment appears
                {"_nav.html", "<nav>2</nav>"},               // and is edited (same size, same tick)
                {"_nav.th.xml", "<thlogic></thlogic>"},      // its decoupled logic
                {"footer.html", "<footer>f</footer>"},       // a partial without the _ prefix (no meta: not a page)
                {"_404.html", "<p>gone</p>"},                // an error page (a template like any other)
                {"_master.th.xml", "<thlogic></thlogic>"}}) { // a second master's logic
            write(t.resolve(step[0]), step[1]);
            etag = service.compute(SITE, PAGE, EN).etag();
            assertTrue(seen.add(etag), "changing " + step[0] + " must move the ETag");
        }
        Files.delete(t.resolve("footer.html"));
        assertTrue(seen.add(service.compute(SITE, PAGE, EN).etag()), "deleting a partial moves it too");
    }

    /** Another page's files, preview scratch files, a sub-folder and non-template files are not chrome. */
    @Test
    void anotherPagesFilesAndNonTemplatesAreNotChrome(@TempDir Path tmp) throws IOException {
        StorageLayout layout = seed(tmp);
        Path t = layout.siteDir("acme").resolve("templates");
        write(t.resolve("about.html"), "<html>about</html>");
        write(t.resolve("about.meta.yml"), "path: /about\n");
        PageValidatorService service = new PageValidatorService(layout);
        String before = service.compute(SITE, PAGE, EN).etag();

        write(t.resolve("about.html"), "<html>about EDITED</html>");
        write(t.resolve("about.th.xml"), "<thlogic></thlogic>");
        write(t.resolve("rw-preview-0123abcd.html"), "<html>scratch</html>");
        write(t.resolve(".pending").resolve("about").resolve("revision.html"), "<html>pending</html>");
        write(t.resolve("notes.txt"), "not a template");
        write(t.resolve("_nav.html.bak"), "an editor backup");
        assertEquals(before, service.compute(SITE, PAGE, EN).etag(),
                "none of these can be pulled into this page's render as chrome");
    }

    @Test
    void chromeIsFoldedByContentAndPerSite(@TempDir Path tmp) throws IOException {
        StorageLayout layout = seed(tmp);
        Path t = layout.siteDir("acme").resolve("templates");
        write(t.resolve("_nav.html"), "<nav>A</nav>");
        Site beta = new Site("beta", "Beta", List.of("beta.test"), EN, List.of(EN), "https://beta.test",
                SiteStatus.ACTIVE);
        write(layout.siteDir("beta").resolve("templates").resolve("home.html"), "<html><body>home</body></html>");
        PageValidatorService service = new PageValidatorService(layout);
        String acme = service.compute(SITE, PAGE, EN).etag();
        String betaBefore = service.compute(beta, PAGE, EN).etag();

        write(t.resolve("_nav.html"), "<nav>B</nav>");
        String edited = service.compute(SITE, PAGE, EN).etag();
        assertNotEquals(acme, edited);
        assertEquals(betaBefore, service.compute(beta, PAGE, EN).etag(), "another site's chrome is not an input");

        write(t.resolve("_nav.html"), "<nav>A</nav>"); // reverted: content, not timestamps, is folded
        assertEquals(acme, service.compute(SITE, PAGE, EN).etag());
        assertEquals(acme, new PageValidatorService(layout).compute(SITE, PAGE, EN).etag(),
                "a fresh instance (another node, a restart) folds the same value");
    }

    @Test
    void localeIsPartOfTheEtag(@TempDir Path tmp) throws IOException {
        PageValidatorService service = new PageValidatorService(seed(tmp));
        assertNotEquals(service.compute(SITE, PAGE, EN).etag(), service.compute(SITE, PAGE, ES).etag());
    }

    @Test
    void aPageValidatorCarriesNoModificationTime(@TempDir Path tmp) throws IOException {
        // Most folded inputs have no date that moves with them, so a Last-Modified would vouch for
        // "unchanged" across a change the ETag sees. 0 = none, so If-Modified-Since is ignored.
        PageValidator validator = new PageValidatorService(seed(tmp)).compute(SITE, PAGE, EN);
        assertEquals(0L, validator.lastModified(), "no modification time, even though the files have one");
        assertTrue(validator.etag().startsWith("\"") && validator.etag().endsWith("\""), "ETag is strong/quoted");
    }

    @Test
    void toleratesMissingOptionalFiles(@TempDir Path tmp) throws IOException {
        StorageLayout layout = seed(tmp);
        Files.delete(layout.siteDir("acme").resolve("templates").resolve("home.th.xml")); // optional logic
        // Must not throw, and the absent marker still yields a usable ETag.
        assertTrue(new PageValidatorService(layout).compute(SITE, PAGE, EN).etag().length() > 2);
    }

    // ---- the page's identity, the kernel build, and a prefix-free field encoding --------------------

    /**
     * Two pages whose files are byte-identical must not share a strong ETag. With no {@code path:} in the meta the
     * path is slug-derived and lives in no folded file, so before this fold the slug was the only difference — unfolded.
     */
    @Test
    void twoByteIdenticalPagesHaveDistinctEtags(@TempDir Path tmp) throws IOException {
        StorageLayout layout = seed(tmp);
        Path templates = layout.siteDir("acme").resolve("templates");
        write(templates.resolve("a.html"), "<html><body>same</body></html>");
        write(templates.resolve("a.meta.yml"), "locales: [en]\n");
        write(templates.resolve("b.html"), "<html><body>same</body></html>");
        write(templates.resolve("b.meta.yml"), "locales: [en]\n");
        Page a = new Page(null, null, Map.of(), List.of(EN), Map.of(), Map.of(), "", "_master", false, false,
                null, null).withSlug("a");
        Page b = new Page(null, null, Map.of(), List.of(EN), Map.of(), Map.of(), "", "_master", false, false,
                null, null).withSlug("b");
        PageValidatorService service = new PageValidatorService(layout);

        assertNotEquals(service.compute(SITE, a, EN).etag(), service.compute(SITE, b, EN).etag());
    }

    /** The resolved public path is folded too: the same slug served under another path is another resource. */
    @Test
    void theResolvedPathIsPartOfTheEtag(@TempDir Path tmp) throws IOException {
        PageValidatorService service = new PageValidatorService(seed(tmp));
        Page moved = new Page("home", "/start", Map.of(), List.of(EN), Map.of(), Map.of(), "", "_master", false,
                false, null, null);
        assertNotEquals(service.compute(SITE, PAGE, EN).etag(), service.compute(SITE, moved, EN).etag());
    }

    private static org.springframework.boot.info.BuildProperties build(String version, String time) {
        java.util.Properties p = new java.util.Properties();
        p.setProperty("version", version);
        p.setProperty("time", time);
        return new org.springframework.boot.info.BuildProperties(p);
    }

    /**
     * A new kernel build busts every cached page: the SPI constant alone stays put across most releases, so the
     * running build's version and build time are folded as well.
     */
    @Test
    void theKernelBuildIsPartOfTheEtag(@TempDir Path tmp) throws IOException {
        StorageLayout layout = seed(tmp);
        var b1 = build("1.0.0", "2026-10-16T23:00:00Z");
        String one = new PageValidatorService(layout, null, null, b1).compute(SITE, PAGE, EN).etag();
        String same = new PageValidatorService(layout, null, null, build("1.0.0", "2026-10-16T23:00:00Z"))
                .compute(SITE, PAGE, EN).etag();
        String laterBuild = new PageValidatorService(layout, null, null, build("1.0.0", "2026-10-17T09:00:00Z"))
                .compute(SITE, PAGE, EN).etag();
        String nextVersion = new PageValidatorService(layout, null, null, build("1.0.1", "2026-10-16T23:00:00Z"))
                .compute(SITE, PAGE, EN).etag();
        String noBuildInfo = new PageValidatorService(layout).compute(SITE, PAGE, EN).etag();

        assertEquals(one, same, "an identical build keeps the ETag (no needless revalidation)");
        assertNotEquals(one, laterBuild, "a build-time change moves the ETag");
        assertNotEquals(one, nextVersion, "a version change moves the ETag");
        assertNotEquals(one, noBuildInfo, "the build-info-less fallback is its own fingerprint");
    }

    /** The fallback without build-info is the SPI constant — deliberate, documented, and never a failure. */
    @Test
    void theBuildFingerprintFallsBackToTheSpiVersion() {
        assertEquals("runway/" + mx.quest.runway.spi.RunwaySpi.VERSION, PageValidatorService.buildFingerprint(null));
        assertTrue(PageValidatorService.buildFingerprint(build("1.2.3", "2026-10-16T23:00:00Z"))
                .startsWith("runway/" + mx.quest.runway.spi.RunwaySpi.VERSION + "/1.2.3@"));
    }

    /**
     * The field-shift collision the old {@code |label=}+raw-bytes encoding allowed. Pair 1 puts
     * {@code "|page.thxml="} inside the page's .html; pair 2 has the same bytes split at the real boundary. The
     * old digest input was byte-for-byte identical (asserted below by rebuilding it), so the two different pages
     * shared one strong ETag. Length prefixes make the boundary unforgeable.
     */
    @Test
    void bytesCannotShiftAcrossAFieldBoundary(@TempDir Path tmp) throws IOException {
        String html1 = "a|page.thxml=b";
        String thxml1 = "c";
        String html2 = "a";
        String thxml2 = "b|page.thxml=c";
        // Proof the pair collided under the old scheme: identical old-style streams.
        assertEquals("|page.html=" + html1 + "|page.thxml=" + thxml1,
                "|page.html=" + html2 + "|page.thxml=" + thxml2, "the pair collides under the old encoding");

        StorageLayout layout = seed(tmp);
        Path templates = layout.siteDir("acme").resolve("templates");
        PageValidatorService service = new PageValidatorService(layout);
        write(templates.resolve("home.html"), html1);
        write(templates.resolve("home.th.xml"), thxml1);
        String first = service.compute(SITE, PAGE, EN).etag();
        write(templates.resolve("home.html"), html2);
        write(templates.resolve("home.th.xml"), thxml2);
        String second = service.compute(SITE, PAGE, EN).etag();

        assertNotEquals(first, second);
    }

    /** An absent file and a present-but-empty file are different states (kinds are part of the encoding). */
    @Test
    void anEmptyFileIsNotTheSameAsAnAbsentOne(@TempDir Path tmp) throws IOException {
        StorageLayout layout = seed(tmp);
        Path thxml = layout.siteDir("acme").resolve("templates").resolve("home.th.xml");
        PageValidatorService service = new PageValidatorService(layout);
        Files.delete(thxml);
        String absent = service.compute(SITE, PAGE, EN).etag();
        write(thxml, "");
        assertNotEquals(absent, service.compute(SITE, PAGE, EN).etag());
    }

    /** A virtual render's metadata fingerprint is length-prefixed too: a title cannot fake another locale's. */
    @Test
    void aVirtualTitleCannotForgeAnotherField(@TempDir Path tmp) throws IOException {
        PageValidatorService service = new PageValidatorService(seed(tmp));
        var forged = new mx.quest.runway.content.RoutableContent("blog", ref(Map.of("en", "a|t.es=b")));
        var honest = new mx.quest.runway.content.RoutableContent("blog", ref(Map.of("en", "a", "es", "b")));
        assertNotEquals(service.computeVirtual(SITE, forged, EN, null).etag(),
                service.computeVirtual(SITE, honest, EN, null).etag());

        // Same entry count, so only the per-component length prefix tells these two param maps apart.
        var shiftedLeft = new mx.quest.runway.content.RoutableContent("blog",
                refWithParams(Map.of("a", "x|b|y", "c", "z")));
        var shiftedRight = new mx.quest.runway.content.RoutableContent("blog",
                refWithParams(Map.of("a", "x", "b", "y|c|z")));
        assertNotEquals(service.computeVirtual(SITE, shiftedLeft, EN, null).etag(),
                service.computeVirtual(SITE, shiftedRight, EN, null).etag());
    }

    private static mx.quest.runway.spi.model.ContentRef refWithParams(Map<String, String> params) {
        return new mx.quest.runway.spi.model.ContentRef("e1", "/blog/e1", Map.of(), List.of(EN), Map.of(), Map.of(),
                "", "__site__/acme/entry", true, java.time.Instant.ofEpochMilli(1000), false, null, null, params);
    }

    /** Two virtual entries that differ only by their resolved path have distinct ETags. */
    @Test
    void aVirtualEntrysPathIsPartOfTheEtag(@TempDir Path tmp) throws IOException {
        PageValidatorService service = new PageValidatorService(seed(tmp));
        var one = new mx.quest.runway.content.RoutableContent("blog", refAt("/blog/one"));
        var two = new mx.quest.runway.content.RoutableContent("blog", refAt("/blog/two"));
        assertNotEquals(service.computeVirtual(SITE, one, EN, null).etag(),
                service.computeVirtual(SITE, two, EN, null).etag());
    }

    // ---- the public-page CSP nonce state is part of the body, so it is part of the ETag ------------

    private static PageValidatorService withNonce(StorageLayout layout, Path keyDir, boolean on) {
        return new PageValidatorService(layout, null, null, null,
                new mx.quest.runway.web.PublicPageNonce(on, keyDir.resolve("csp-nonce.key")));
    }

    /** Switching the feature on or off changes every body (nonce attributes appear/vanish), so it moves the ETag. */
    @Test
    void theNonceSwitchMovesTheEtag(@TempDir Path tmp) throws IOException {
        StorageLayout layout = seed(tmp);
        String off = withNonce(layout, tmp, false).compute(SITE, PAGE, EN).etag();
        String on = withNonce(layout, tmp, true).compute(SITE, PAGE, EN).etag();
        assertNotEquals(off, on);
        assertEquals(off, new PageValidatorService(layout).compute(SITE, PAGE, EN).etag(),
                "an unwired nonce source folds exactly like a switched-off one");
        assertEquals(on, withNonce(layout, tmp, true).compute(SITE, PAGE, EN).etag(), "stable across restarts");
    }

    /**
     * Rotating the secret changes every nonce, hence every body — so it must move every ETag, page and virtual,
     * or a cache could revalidate an old body (old nonce) against a header carrying the new one.
     */
    @Test
    void rotatingTheNonceSecretMovesEveryEtag(@TempDir Path tmp) throws IOException {
        StorageLayout layout = seed(tmp);
        var entry = new mx.quest.runway.content.RoutableContent("blog", refAt("/blog/one"));
        PageValidatorService before = withNonce(layout, tmp, true);
        String page = before.compute(SITE, PAGE, EN).etag();
        String virtual = before.computeVirtual(SITE, entry, EN, null).etag();

        Files.delete(tmp.resolve("csp-nonce.key"));
        PageValidatorService rotated = withNonce(layout, tmp, true);

        assertNotEquals(page, rotated.compute(SITE, PAGE, EN).etag());
        assertNotEquals(virtual, rotated.computeVirtual(SITE, entry, EN, null).etag());
    }

    private static mx.quest.runway.spi.model.ContentRef ref(Map<String, String> title) {
        return new mx.quest.runway.spi.model.ContentRef("e1", "/blog/e1", Map.of(), List.of(EN), title, Map.of(),
                "", "__site__/acme/entry", true, java.time.Instant.ofEpochMilli(1000), false, null, null, Map.of());
    }

    private static mx.quest.runway.spi.model.ContentRef refAt(String path) {
        return new mx.quest.runway.spi.model.ContentRef("e1", path, Map.of(), List.of(EN), Map.of(), Map.of(),
                "", "__site__/acme/entry", true, java.time.Instant.ofEpochMilli(1000), false, null, null, Map.of());
    }

    // ---- the injected <head> is part of the byte-identity the ETag promises ----------------------

    /** A service whose head seam is the real kernel pair (SEO config + icons), as production wires it. */
    private static PageValidatorService withHeadSeam(StorageLayout layout) {
        return new PageValidatorService(layout, null, new mx.quest.runway.head.HeadContributorRegistry(
                List.of(new mx.quest.runway.seo.SeoHeadContributor(),
                        new mx.quest.runway.icon.IconHeadContributor())));
    }

    /**
     * The defect this closes: editing a site's SEO config changed the delivered page and not the ETag.
     *
     * <p>The head is spliced in at the response edge, after the validator has been computed and may already
     * have answered {@code 304}. So a client holding the old validator kept the previous head <b>until some
     * unrelated file happened to change</b> — while the operator, checking with a fresh fetch, saw the new
     * value and concluded it was live.
     */
    @Test
    void editingTheSeoConfigMovesTheEtag(@TempDir Path tmp) throws IOException {
        StorageLayout layout = seed(tmp);
        PageValidatorService service = withHeadSeam(layout);
        String before = service.compute(SITE, PAGE, EN).etag();

        write(layout.siteDir("acme").resolve("seo.yml"), "ogType: article\n");

        assertNotEquals(before, service.compute(SITE, PAGE, EN).etag());
    }

    /** Creating a declared input that was absent counts as a change — "absent" is a folded state, not a skip. */
    @Test
    void creatingAndThenEditingADeclaredInputBothMoveTheEtag(@TempDir Path tmp) throws IOException {
        StorageLayout layout = seed(tmp);
        PageValidatorService service = withHeadSeam(layout);
        String absent = service.compute(SITE, PAGE, EN).etag();

        write(layout.siteDir("acme").resolve("seo.yml"), "ogType: website\n");
        String created = service.compute(SITE, PAGE, EN).etag();
        write(layout.siteDir("acme").resolve("seo.yml"), "ogType: article\n");
        String edited = service.compute(SITE, PAGE, EN).etag();

        assertNotEquals(absent, created, "creating the file changes the head");
        assertNotEquals(created, edited, "and so does editing it");
    }

    /**
     * Adding an icon moves the ETag, because the head advertises which icons exist.
     *
     * <p>Folded as the folder's listing rather than each slot's bytes: it is the presence that shapes the
     * markup, and reading image bytes on every conditional request would be a permanent cost for no signal.
     */
    @Test
    void addingAnIconMovesTheEtag(@TempDir Path tmp) throws IOException {
        StorageLayout layout = seed(tmp);
        PageValidatorService service = withHeadSeam(layout);
        String before = service.compute(SITE, PAGE, EN).etag();

        write(layout.siteDir("acme").resolve("icons").resolve("favicon.svg"), "<svg/>");

        assertNotEquals(before, service.compute(SITE, PAGE, EN).etag());
    }

    /** Unchanged declared inputs must not move it — otherwise every request revalidates as changed. */
    @Test
    void unchangedHeadInputsKeepTheEtagStable(@TempDir Path tmp) throws IOException {
        StorageLayout layout = seed(tmp);
        write(layout.siteDir("acme").resolve("seo.yml"), "ogType: article\n");
        write(layout.siteDir("acme").resolve("icons").resolve("favicon.svg"), "<svg/>");
        PageValidatorService service = withHeadSeam(layout);

        assertEquals(service.compute(SITE, PAGE, EN).etag(), service.compute(SITE, PAGE, EN).etag());
    }

    /**
     * The declared set is order-independent.
     *
     * <p>Bean registration order can differ between two nodes behind a load balancer, and between a restart
     * and the one before it. An order-dependent digest would make identical content revalidate as changed —
     * a cache bug that appears only in the deployment where it is hardest to observe.
     */
    @Test
    void theEtagDoesNotDependOnContributorOrder(@TempDir Path tmp) throws IOException {
        StorageLayout layout = seed(tmp);
        write(layout.siteDir("acme").resolve("seo.yml"), "ogType: article\n");
        var seo = new mx.quest.runway.seo.SeoHeadContributor();
        var icons = new mx.quest.runway.icon.IconHeadContributor();

        String oneWay = new PageValidatorService(layout, null,
                new mx.quest.runway.head.HeadContributorRegistry(List.of(seo, icons)))
                .compute(SITE, PAGE, EN).etag();
        String other = new PageValidatorService(layout, null,
                new mx.quest.runway.head.HeadContributorRegistry(List.of(icons, seo)))
                .compute(SITE, PAGE, EN).etag();

        assertEquals(oneWay, other);
    }

    /**
     * A declared path that escapes the site folder is ignored rather than read.
     *
     * <p>These strings come from plugin code, so the confinement is the same rule the master-template name
     * gets: a validator that can be steered into reading another tenant's file would leak its existence and
     * its mtime through ETag changes.
     */
    @Test
    void aDeclaredInputCannotEscapeTheSiteFolder(@TempDir Path tmp) throws IOException {
        StorageLayout layout = seed(tmp);
        write(tmp.resolve("secret.yml"), "a: 1\n");
        PageValidatorService service = new PageValidatorService(layout, null,
                new mx.quest.runway.head.HeadContributorRegistry(List.of(new EscapingContributor())));
        String before = service.compute(SITE, PAGE, EN).etag();

        write(tmp.resolve("secret.yml"), "a: 2\n");

        assertEquals(before, service.compute(SITE, PAGE, EN).etag(),
                "a file outside the tenant must not be readable through a declared head input");
    }

    /** A contributor declaring a traversing path — the hostile shape the confinement exists for. */
    private static final class EscapingContributor implements mx.quest.runway.spi.HeadContributor {
        @Override
        public String key() {
            return "escaping";
        }

        @Override
        public String render(mx.quest.runway.spi.model.HeadContext context) {
            return "";
        }

        @Override
        public List<String> headInputs(String siteId) {
            return List.of("../../secret.yml");
        }
    }
}
