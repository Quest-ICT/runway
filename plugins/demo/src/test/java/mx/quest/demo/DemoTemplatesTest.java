package mx.quest.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * The shipped templates as files: every state-changing action of the console screen is reached by a
 * {@code <form method="post">} that carries the console session's CSRF token, no link points at one (a link is a
 * {@code GET}), every form action is a real handler, and no decoupled template repeats an {@code id} (a hard
 * Thymeleaf error that would 500 the screen).
 */
class DemoTemplatesTest {

    private static final Pattern FORM = Pattern.compile("(?s)<form\\b([^>]*)>(.*?)</form>");
    private static final String TOKEN_BINDING =
            "th:attr=\"name=${_csrf != null ? _csrf.parameterName : '_csrf'},value=${_csrf != null ? _csrf.token : ''}\"";

    private static String read(String file) throws IOException {
        return Files.readString(Path.of("templates", file), StandardCharsets.UTF_8).replaceAll("(?s)<!--.*?-->", "");
    }

    private static Set<String> postActions() {
        Set<String> paths = new TreeSet<>();
        for (Method m : DemoConsoleController.class.getDeclaredMethods()) {
            PostMapping post = m.getAnnotation(PostMapping.class);
            if (post != null) {
                for (String p : post.value()) {
                    paths.add(DemoConsoleController.BASE + p);
                }
            }
        }
        return paths;
    }

    @Test
    void everyPostActionHasAPostFormCarryingTheCsrfToken() throws IOException {
        assertEquals(Set.of("/__admin/runway-demo/notes", "/__admin/runway-demo/showcase",
                "/__admin/runway-demo/showcase/unpublish", "/__admin/runway-demo/install-page",
                "/__admin/runway-demo/catalog/refresh"), postActions());
        String html = read("console.html");
        Set<String> formed = new HashSet<>();
        Matcher form = FORM.matcher(html);
        while (form.find()) {
            String attrs = form.group(1);
            Matcher action = Pattern.compile("action=\"([^\"]+)\"").matcher(attrs);
            assertTrue(action.find(), "every form names its action: " + attrs);
            if (attrs.contains("method=\"get\"")) {
                assertEquals(DemoConsoleController.BASE, action.group(1), "the only GET form is the read-only check");
                assertFalse(form.group(2).contains("_csrf"), "a GET form must not put the token in a URL");
                continue;
            }
            assertTrue(attrs.contains("method=\"post\""), attrs);
            assertTrue(postActions().contains(action.group(1)), "a form posts to a real handler: " + action.group(1));
            assertTrue(form.group(2).contains("class=\"rd-csrf\" type=\"hidden\" name=\"_csrf\""),
                    action.group(1) + "'s form carries the hidden CSRF field");
            formed.add(action.group(1));
        }
        assertEquals(postActions(), formed, "every action is reachable from the screen");
        assertTrue(read("console.th.xml").contains("<attr sel=\".rd-csrf\"\n          " + TOKEN_BINDING + "/>"),
                "the .rd-csrf fields are bound to the console session's token");
    }

    @Test
    void noLinkPointsAtAnAction() throws IOException {
        try (Stream<Path> files = Files.list(Path.of("templates"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".html")).toList()) {
                String html = read(file.getFileName().toString());
                for (String action : postActions()) {
                    assertFalse(html.contains("href=\"" + action), file + " links to " + action);
                }
            }
        }
    }

    @Test
    void noTemplateRepeatsAnId() throws IOException {
        try (Stream<Path> files = Files.list(Path.of("templates"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".html")).toList()) {
                Matcher ids = Pattern.compile("\\bid=\"([^\"]+)\"").matcher(read(file.getFileName().toString()));
                Set<String> seen = new HashSet<>();
                while (ids.find()) {
                    assertTrue(seen.add(ids.group(1)), file + ": duplicate id " + ids.group(1));
                }
            }
        }
    }

    @Test
    void everySelectorInTheLogicMatchesTheMarkup() throws IOException {
        try (Stream<Path> files = Files.list(Path.of("templates"))) {
            for (Path logic : files.filter(p -> p.toString().endsWith(".th.xml")).toList()) {
                String name = logic.getFileName().toString().replace(".th.xml", "");
                String html = read(name + ".html");
                Matcher sel = Pattern.compile("sel=\"([^\"]+)\"").matcher(read(name + ".th.xml"));
                while (sel.find()) {
                    for (String part : sel.group(1).split("//")) {
                        String token = part.startsWith("#") ? "id=\"" + part.substring(1) + "\""
                                : part.startsWith(".") ? part.substring(1) : "<" + part;
                        assertTrue(html.contains(token), name + ": selector " + sel.group(1) + " matches nothing");
                    }
                }
            }
        }
    }
}
