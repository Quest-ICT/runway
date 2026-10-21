package mx.quest.contact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import mx.quest.runway.spi.model.Route;
import mx.quest.runway.spi.testkit.FakePluginSettings;
import mx.quest.runway.spi.testkit.FakeSiteServices;
import org.junit.jupiter.api.Test;

/**
 * The console side of the CSRF fix: every operator action the endpoint declares (a non-anonymous {@code POST}) is
 * reached from the admin fragment by a {@code <form method="post">} that carries the console session's CSRF token,
 * and no link points at it any more (a link is a {@code GET}, and a {@code GET} on those paths changes nothing).
 * The markup is read from {@code templates/} as shipped; the token binding from the sibling {@code .th.xml}.
 */
class ContactAdminTemplateTest {

    private static final Pattern FORM = Pattern.compile("(?s)<form\\b([^>]*)>(.*?)</form>");
    private static final Pattern CSRF_INPUT =
            Pattern.compile("<input\\b[^>]*\\bid=\"([^\"]+)\"[^>]*\\bname=\"_csrf\"[^>]*>");
    private static final String TOKEN_BINDING =
            "th:attr=\"name=${_csrf != null ? _csrf.parameterName : '_csrf'},value=${_csrf != null ? _csrf.token : ''}\"";

    private static String read(String file) throws IOException {
        return Files.readString(Path.of("templates", file), StandardCharsets.UTF_8).replaceAll("(?s)<!--.*?-->", "");
    }

    private static List<String> operatorActions() {
        ContactEndpoint endpoint = new ContactEndpoint(null, null, new FakePluginSettings(), new FakeSiteServices(),
                new FakeSiteServices(), null, new FakeSiteServices());
        List<String> paths = new ArrayList<>();
        for (Route r : endpoint.routes()) {
            if (!r.anonymous()) {
                paths.add(r.pathPattern());
            }
        }
        return paths;
    }

    @Test
    void theOperatorActionsAreTheInstallAndTheSmtpTest() {
        assertEquals(List.of("/contact/install-page", "/contact/smtp-test"), operatorActions());
    }

    @Test
    void everyOperatorActionIsAPostFormCarryingTheCsrfToken() throws IOException {
        String html = read("admin.html");
        String logic = read("admin.th.xml");
        for (String action : operatorActions()) {
            Matcher form = FORM.matcher(html);
            boolean found = false;
            while (form.find()) {
                String attrs = form.group(1);
                if (!attrs.contains("action=\"" + action + "\"")) {
                    continue;
                }
                found = true;
                assertTrue(attrs.contains("method=\"post\""), action + " must be submitted by POST: " + attrs);
                Matcher csrf = CSRF_INPUT.matcher(form.group(2));
                assertTrue(csrf.find(), action + "'s form carries a hidden _csrf field");
                String id = csrf.group(1);
                assertTrue(logic.contains("<attr sel=\"#" + id + "\"\n          " + TOKEN_BINDING + "/>"),
                        "#" + id + " is bound to the console session's CSRF token in admin.th.xml");
            }
            assertTrue(found, "the admin fragment has a form for " + action);
        }
    }

    @Test
    void noLinkPointsAtAnOperatorAction() throws IOException {
        String html = read("admin.html");
        for (String action : operatorActions()) {
            assertFalse(html.contains("href=\"" + action), "a link is a GET and would not carry the token: " + action);
        }
    }

    @Test
    void theAdminFragmentsIdsAreUnique() throws IOException {
        // A duplicate id in a decoupled template is a hard Thymeleaf error that would 500 the settings page.
        Matcher ids = Pattern.compile("\\bid=\"([^\"]+)\"").matcher(read("admin.html"));
        Set<String> seen = new HashSet<>();
        while (ids.find()) {
            assertTrue(seen.add(ids.group(1)), "duplicate id " + ids.group(1));
        }
    }
}
