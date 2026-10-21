package mx.quest.runway.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

/**
 * The static half of the accessibility guard, independent of fixture data: a decoupled template that repeats an
 * element ({@code th:each}) repeats every {@code id} in it, and duplicate ids break {@code <label for>},
 * {@code aria-labelledby} and {@code getElementById} silently. {@link OperatorA11yGuardTest} catches that only for
 * lists its fixture fills; this test reads every operator {@code .th.xml} with its {@code .html} and requires, for
 * each element repeated by a {@code th:each} on an id selector, that the element and every id-bearing descendant
 * either drops its template id at render time ({@code th:id="${null}"}) or rewrites it per row ({@code th:id} /
 * {@code th:attr="id=..."}).
 *
 * <p>The template id itself stays in the {@code .html}: it is the decoupled logic's selector anchor, and a
 * duplicate id in the SOURCE markup is a hard 500 — this is about the rendered page only.
 */
class OperatorTemplateIdGuardTest {

    private static final Path TEMPLATES = Path.of("src/main/resources/templates/operator");
    private static final Pattern ATTR = Pattern.compile("<attr\\s+(.*?)/>", Pattern.DOTALL);
    private static final Pattern SEL = Pattern.compile("\\bsel=\"([^\"]*)\"");
    private static final Pattern REWRITES_ID = Pattern.compile("\\bth:id=|th:attr=\"(?:[^\"]*,)?\\s*id=");

    /** The id a selector finally targets ({@code #a//#b} → {@code b}), or null when its last step is not an id. */
    static String targetId(String sel) {
        String last = lastStep(sel);
        return last.matches("#[A-Za-z0-9_-]+") ? last.substring(1) : null;
    }

    /** The class a selector finally targets ({@code #a//.b} → {@code b}), or null when its last step is not one. */
    static String targetClass(String sel) {
        String last = lastStep(sel);
        return last.matches("\\.[A-Za-z0-9_-]+") ? last.substring(1) : null;
    }

    private static String lastStep(String sel) {
        String[] steps = sel.split("/+");
        return steps[steps.length - 1].trim();
    }

    /** The violations in one template pair (empty when clean). Package-private so the rule itself is unit-tested. */
    static List<String> violations(String name, String thxml, String html) {
        Map<String, Boolean> rewrites = new HashMap<>();   // id -> some attr rewrites/drops it
        Map<String, Boolean> classRewrites = new HashMap<>(); // class -> an attr on that class rewrites/drops ids
        List<String> iterated = new ArrayList<>();
        Matcher m = ATTR.matcher(thxml);
        while (m.find()) {
            String body = m.group(1);
            Matcher sel = SEL.matcher(body);
            if (!sel.find()) {
                continue;
            }
            String cls = targetClass(sel.group(1));
            if (cls != null && REWRITES_ID.matcher(body).find()) {
                classRewrites.put(cls, true);
            }
            String id = targetId(sel.group(1));
            if (id == null) {
                continue;
            }
            if (REWRITES_ID.matcher(body).find()) {
                rewrites.put(id, true);
            }
            if (body.contains("th:each=")) {
                iterated.add(id);
            }
        }
        Document doc = Jsoup.parse(html);
        List<String> out = new ArrayList<>();
        for (String id : iterated) {
            Element repeated = doc.getElementById(id);
            if (repeated == null) {
                continue;
            }
            for (Element e : repeated.select("[id]")) {   // includes the repeated element itself
                boolean byClass = e.classNames().stream().anyMatch(c -> classRewrites.getOrDefault(c, false));
                if (!rewrites.getOrDefault(e.id(), false) && !byClass) {
                    out.add(name + ": #" + e.id() + " is repeated by th:each on #" + id
                            + " but keeps its template id (add th:id=\"${null}\" or a per-row id)");
                }
            }
        }
        return out;
    }

    @Test
    void everyRepeatedIdIsDroppedOrMadeUniquePerRow() throws IOException {
        List<String> problems = new ArrayList<>();
        int checked = 0;
        try (Stream<Path> files = Files.walk(TEMPLATES)) {
            for (Path thxml : files.filter(p -> p.toString().endsWith(".th.xml")).sorted().toList()) {
                Path html = thxml.resolveSibling(thxml.getFileName().toString().replace(".th.xml", ".html"));
                if (!Files.exists(html)) {
                    continue;
                }
                checked++;
                problems.addAll(violations(TEMPLATES.relativize(html).toString(),
                        Files.readString(thxml, StandardCharsets.UTF_8), Files.readString(html, StandardCharsets.UTF_8)));
            }
        }
        assertTrue(checked > 40, "the walk found the operator templates (" + checked + ")");
        assertTrue(problems.isEmpty(), problems.size() + " repeated id(s):\n  " + String.join("\n  ", problems));
    }

    @Test
    void theRuleFlagsARepeatedRowAndItsDescendantsAndAcceptsEachFix() {
        String html = "<table><tbody><tr id=\"row\"><td><input id=\"cell\"></td></tr></tbody></table>";
        assertEquals(2, violations("t", "<thlogic><attr sel=\"#row\" th:each=\"r : ${rows}\"/></thlogic>", html)
                .size(), "the row and the input inside it");
        assertEquals(List.of(), violations("t", """
                <thlogic>
                  <attr sel="#row" th:each="r : ${rows}" th:id="${null}"/>
                  <attr sel="#row//#cell" th:attr="value=${r},id='cell-' + ${r}"/>
                </thlogic>""", html), "dropped / rewritten per row");
        assertEquals(1, violations("t", """
                <thlogic>
                  <attr sel="#row" th:each="r : ${rows}" th:id="${null}"/>
                  <attr sel="#row//#cell" th:attr="value=${r}"/>
                </thlogic>""", html).size(), "a descendant still repeating its id is caught");
        assertEquals(List.of(), violations("t", "<thlogic><attr sel=\"#row\" th:text=\"${x}\"/></thlogic>", html),
                "no th:each, nothing repeats");
    }
}
