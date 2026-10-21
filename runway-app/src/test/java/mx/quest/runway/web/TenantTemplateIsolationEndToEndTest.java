package mx.quest.runway.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import mx.quest.runway.security.PasswordEncoders;
import mx.quest.runway.security.PasswordStrength;
import mx.quest.runway.security.Roles;
import mx.quest.runway.security.User;
import mx.quest.runway.security.UserStore;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteStatus;
import mx.quest.runway.site.SiteStore;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Tenant isolation of the <b>template render path</b>, against a real server: a site manager owns their site's
 * {@code .th.xml} (full Thymeleaf), so a template is untrusted with respect to every other site. Two sites,
 * {@code acme} (host {@code localhost}) and {@code globex} (host {@code 127.0.0.1}), each try every way a template
 * can name another site's template — a computed name, a {@code th:with}-built name, {@code __${…}__} preprocessing,
 * a whole-template {@code th:replace}, an overridden {@code ${master}}, a page whose {@code template:} meta climbs
 * out, a custom error page — and a {@code ${@bean}} call into the kernel's stores. None may render a byte of the
 * other site; the same-site forms keep working; a foreign template that exists fails exactly like one that does
 * not (no existence oracle); and it all holds with the template cache cold <em>and</em> warmed by the victim's own
 * legitimate renders of the very templates being reached for.
 *
 * <p>Before the fix the computed-name form rendered the other site's private fragment into the attacker's public
 * page, and the bean call listed every site on the platform.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TenantTemplateIsolationEndToEndTest {

    private static final String ACME_HOST = "localhost";
    private static final String GLOBEX_HOST = "127.0.0.1";
    private static final List<String> ATTACKS =
            List.of("x-lit", "x-whole", "x-computed", "x-pre", "x-master", "x-gst", "x-meta", "x-bean");

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-tpl-iso");
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        SiteStore store = new SiteStore(layout, yaml);
        store.save(new Site("acme", "Acme", List.of(ACME_HOST), Locale.ENGLISH, List.of(Locale.ENGLISH),
                null, SiteStatus.ACTIVE, false, null));
        store.save(new Site("globex", "Globex", List.of(GLOBEX_HOST), Locale.ENGLISH, List.of(Locale.ENGLISH),
                null, SiteStatus.ACTIVE, false, null));
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode("Iso-Test-9x");
        new UserStore(yaml).save(external.resolve("app-users.yml"),
                new User("app-master", "root", hash, "Root", "", true, true, Realm.APPLICATION,
                        Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        registry.add("runway.storage.external", () -> external.toString());
        registry.add("runway.security.app-master-id", () -> "app-master");
        registry.add("runway.health.content-check", () -> "false");

        seed(layout, "acme", "globex");
        seed(layout, "globex", "acme");
    }

    /** A site's own templates (markers {@code <SITE>_…}) plus its attack pages aimed at {@code victim}. */
    private static void seed(StorageLayout layout, String self, String victim) throws IOException {
        Path dir = layout.siteDir(self).resolve("templates");
        String mark = self.toUpperCase(Locale.ROOT);
        write(dir.resolve("_master.html"), doc("<main id=\"content\">" + mark + "_MASTER_CHROME</main>"));
        write(dir.resolve("_nav.html"), doc("<nav id=\"nav\">" + mark + "_OWN_NAV</nav>"));
        write(dir.resolve("secret.html"), doc("<main id=\"content\"><p id=\"secret\">" + mark
                + "_PRIVATE_FRAGMENT</p></main>"));
        write(dir.resolve("draftpage.html"), doc("<main id=\"content\"><p>" + mark + "_DRAFT_BODY</p></main>"));
        write(dir.resolve("draftpage.meta.yml"), "path: /draftpage\nlocales: [en]\ndraft: true\n");

        // The legitimate same-site forms: computed from ${master}, and preprocessed from a th:with-built name.
        page(dir, "home", null, "<attr sel=\"#x\" th:insert=\"~{${master.replace('_master', 'secret')} :: #secret}\"/>");
        page(dir, "own", null,
                "<attr sel=\"#content\" th:with=\"n=" + name(self, "_nav") + "\"/>"
                + "<attr sel=\"#x\" th:insert=\"~{${master.replace('_master', '_nav')} :: #nav}\"/>"
                + "<attr sel=\"#y\" th:insert=\"~{__${n}__ :: #nav}\"/>");

        // Every way this site's manager could name the victim's templates.
        page(dir, "x-lit", null, "<attr sel=\"#content\" th:with=\"n=" + name(victim, "secret") + "\"/>"
                + "<attr sel=\"#x\" th:insert=\"~{${n} :: #secret}\"/>");
        page(dir, "x-gst", null, "<attr sel=\"#content\" th:with=\"n=" + name(victim, "no-such-template") + "\"/>"
                + "<attr sel=\"#x\" th:insert=\"~{${n} :: #secret}\"/>");
        page(dir, "x-whole", null, "<attr sel=\"#content\" th:with=\"n=" + name(victim, "draftpage") + "\"/>"
                + "<attr sel=\"#x\" th:replace=\"~{${n}}\"/>");
        page(dir, "x-computed", null, "<attr sel=\"#x\" th:insert=\"~{${master.replace('/" + self + "/', '/"
                + victim + "/').replace('_master', 'secret')} :: #secret}\"/>");
        page(dir, "x-pre", null, "<attr sel=\"#content\" th:with=\"n=" + name(victim, "secret") + "\"/>"
                + "<attr sel=\"#x\" th:insert=\"~{__${n}__ :: #secret}\"/>");
        page(dir, "x-master", null, "<attr sel=\"#content\" th:with=\"master=" + name(victim, "_master") + "\"/>"
                + "<attr sel=\"#x\" th:replace=\"~{${master} :: #content}\"/>");
        page(dir, "x-meta", "../" + victim + "/secret", "<attr sel=\"#x\" th:replace=\"~{${master} :: #secret}\"/>");
        page(dir, "x-bean", null, "<attr sel=\"#x\" th:text=\"${@siteStore.findAll()}\"/>");

        // A custom error page is a tenant template too.
        write(dir.resolve("_404.html"), doc("<main id=\"content\"><div id=\"x\">E</div></main>"));
        write(dir.resolve("_404.th.xml"), "<?xml version=\"1.0\"?><thlogic>"
                + "<attr sel=\"#content\" th:with=\"n=" + name(victim, "secret") + "\"/>"
                + "<attr sel=\"#x\" th:insert=\"~{${n} :: #secret}\"/></thlogic>");
    }

    /** A SpEL string literal for {@code __site__/<site>/<leaf>} with no {@code __} pair left for preprocessing. */
    private static String name(String site, String leaf) {
        return "'_' + '_site_' + '_/" + site + "/" + leaf + "'";
    }

    private static String doc(String body) {
        return "<!DOCTYPE html><html><head><title>T</title></head><body>" + body + "</body></html>";
    }

    private static void page(Path dir, String slug, String template, String rules) throws IOException {
        write(dir.resolve(slug + ".html"), doc("<main id=\"content\"><div id=\"x\">PLACEHOLDER</div>"
                + "<div id=\"y\">PLACEHOLDER</div></main>"));
        write(dir.resolve(slug + ".th.xml"), "<?xml version=\"1.0\"?><thlogic>" + rules + "</thlogic>");
        write(dir.resolve(slug + ".meta.yml"), "path: /" + slug + "\nlocales: [en]\n"
                + (template == null ? "" : "template: \"" + template + "\"\n"));
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    @LocalServerPort
    private int port;

    private HttpResponse<String> get(String host, String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://" + host + ":" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** Every attack page of {@code host} fails, and no byte of {@code victimMark}'s templates reaches the wire. */
    private void assertEveryAttackFails(String host, String victimMark) throws Exception {
        for (String slug : ATTACKS) {
            HttpResponse<String> r = get(host, "/" + slug);
            assertEquals(500, r.statusCode(), () -> host + "/" + slug + " must fail to render; got:\n" + r.body());
            assertFalse(r.body().contains(victimMark + "_"), () -> host + "/" + slug + " leaked:\n" + r.body());
            assertFalse(r.body().contains("Site["), () -> host + "/" + slug + " reached a kernel bean:\n" + r.body());
        }
        HttpResponse<String> error = get(host, "/no-such-page");
        assertEquals(404, error.statusCode());
        assertFalse(error.body().contains(victimMark + "_"), () -> host + " error page leaked:\n" + error.body());
    }

    private void assertOwnRendersWork(String host, String mark) throws Exception {
        HttpResponse<String> home = get(host, "/home");
        assertEquals(200, home.statusCode(), home::body);
        assertTrue(home.body().contains(mark + "_PRIVATE_FRAGMENT"), () -> "a site includes its own fragment:\n" + home.body());
        HttpResponse<String> own = get(host, "/own");
        assertEquals(200, own.statusCode(), own::body);
        assertEquals(2, own.body().split(mark + "_OWN_NAV", -1).length - 1,
                () -> "computed and preprocessed same-site includes both render:\n" + own.body());
    }

    @Test
    void neitherSiteReachesTheOthersTemplatesColdOrWarm() throws Exception {
        // Cold: nothing of either site parsed yet.
        assertEveryAttackFails(ACME_HOST, "GLOBEX");
        assertEveryAttackFails(GLOBEX_HOST, "ACME");
        // Warm the victims' caches with their own legitimate renders of exactly the templates being reached for…
        assertOwnRendersWork(GLOBEX_HOST, "GLOBEX");
        assertOwnRendersWork(ACME_HOST, "ACME");
        // …and attack again: a cached parse of the victim's template is not a cache hit for another site's render.
        assertEveryAttackFails(ACME_HOST, "GLOBEX");
        assertEveryAttackFails(GLOBEX_HOST, "ACME");
        // The attacks did not poison anything either: the victims still render their own.
        assertOwnRendersWork(GLOBEX_HOST, "GLOBEX");
        assertOwnRendersWork(ACME_HOST, "ACME");
    }

    @Test
    void anotherSitesExistingTemplateFailsExactlyLikeAMissingOne() throws Exception {
        for (String host : List.of(ACME_HOST, GLOBEX_HOST)) {
            HttpResponse<String> exists = get(host, "/x-lit");
            HttpResponse<String> missing = get(host, "/x-gst");
            assertEquals(missing.statusCode(), exists.statusCode());
            // The only difference allowed is the request path (same length by design), which the error page echoes.
            assertEquals(missing.body(), exists.body().replace("/x-lit", "/x-gst"),
                    "no oracle: the response must not tell the two apart");
            assertEquals(withoutDate(missing), withoutDate(exists), "same headers too");
        }
    }

    private static java.util.Map<String, List<String>> withoutDate(HttpResponse<String> r) {
        java.util.Map<String, List<String>> m = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        m.putAll(r.headers().map());
        m.remove("date");
        return m;
    }
}
