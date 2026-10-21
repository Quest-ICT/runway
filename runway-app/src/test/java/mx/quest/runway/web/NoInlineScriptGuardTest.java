package mx.quest.runway.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Guards the CSP {@code script-src} model. Two invariants, one per test:
 *
 * <ol>
 *   <li><b>No executable inline {@code <script>}</b> in the operator templates — every one is a
 *       {@code <script src=…>} (from {@code /__assets/**} or a plugin namespace) or a
 *       {@code type="application/ld+json"} data block. This is what let {@code script-src} drop
 *       {@code 'unsafe-inline'} in the first place.</li>
 *   <li><b>Every external {@code <script>} is nonced</b> (for the nonce + {@code 'strict-dynamic'}
 *       policy). Because the templates are decoupled, the nonce is injected by a single
 *       {@code <attr sel="script" th:attr="nonce=${cspNonce}"/>} rule in each template's {@code .th.xml} — one
 *       element selector that <em>cannot</em> miss a tag. So the real risk isn't a missed tag; it's a NEW
 *       script-bearing template added without that rule. This test fails <em>here</em> for such a template rather
 *       than silently the day CSP flips to enforce with {@code 'strict-dynamic'}. The wizard's hand-built HTML
 *       (Java, not a template) is checked separately: its {@code <script>} tags must splice
 *       {@code cspNonceAttr()}.</li>
 * </ol>
 *
 * <p>Scans the module's shipped templates + the wizard source on disk (surefire runs with the module dir as CWD).
 */
class NoInlineScriptGuardTest {

    private static final Path TEMPLATES = Path.of("src/main/resources/templates");
    private static final Pattern HTML_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern SCRIPT_OPEN = Pattern.compile("<script\\b[^>]*>", Pattern.CASE_INSENSITIVE);

    @Test
    void noOperatorTemplateCarriesAnExecutableInlineScript() throws IOException {
        assertThat(Files.isDirectory(TEMPLATES)).as("templates dir resolves from the module CWD").isTrue();
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(TEMPLATES)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".html")).toList()) {
                String html = HTML_COMMENT.matcher(Files.readString(file)).replaceAll(""); // ignore commented-out tags
                Matcher m = SCRIPT_OPEN.matcher(html);
                while (m.find()) {
                    String tag = m.group().toLowerCase();
                    boolean external = tag.contains("src=");
                    boolean jsonLd = tag.contains("application/ld+json");
                    if (!external && !jsonLd) {
                        violations.add(TEMPLATES.relativize(file) + " → " + m.group());
                    }
                }
            }
        }
        assertThat(violations)
                .as("executable inline <script> blocks defeat script-src 'self' — externalize to /__assets/**")
                .isEmpty();
    }

    @Test
    void everyTemplateWithAnExternalScriptNoncesItViaItsDecoupledLogic() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(TEMPLATES)) {
            for (Path html : files.filter(p -> p.toString().endsWith(".html")).toList()) {
                String cleaned = HTML_COMMENT.matcher(Files.readString(html)).replaceAll("");
                boolean hasExternalScript = false;
                Matcher m = SCRIPT_OPEN.matcher(cleaned);
                while (m.find()) {
                    String tag = m.group().toLowerCase();
                    if (tag.contains("src=") && !tag.contains("application/ld+json")) {
                        hasExternalScript = true;
                    }
                }
                if (!hasExternalScript) {
                    continue; // a template with no external <script> needs no nonce rule
                }
                Path thxml = Path.of(html.toString().replaceAll("\\.html$", ".th.xml"));
                if (!Files.exists(thxml)) {
                    violations.add(TEMPLATES.relativize(html) + " has a <script src> but no sibling .th.xml to nonce it");
                    continue;
                }
                String logic = Files.readString(thxml);
                if (!(logic.contains("sel=\"script\"") && logic.contains("nonce=${cspNonce}"))) {
                    violations.add(TEMPLATES.relativize(html)
                            + " has a <script src> but its .th.xml lacks the sel=\"script\" th:attr=nonce=${cspNonce} rule");
                }
            }
        }
        assertThat(violations)
                .as("every template with an external <script> must nonce it via a sel=\"script\" rule in its .th.xml")
                .isEmpty();
    }

    @Test
    void theWizardHandBuiltHtmlNoncesEveryScript() throws IOException {
        // The wizard builds its HTML in Java (@ResponseBody), so it is invisible to the template scan. Its scripts
        // are built by concatenation ("<script" + cspNonceAttr() + …), so a COMPLETE <script…> open tag inside a
        // single Java string literal would be a hand-built script that skipped the nonce.
        Path wizard = Path.of("src/main/java/mx/quest/runway/wizard/WizardController.java");
        assertThat(Files.exists(wizard)).as("WizardController resolves from the module CWD").isTrue();
        String src = Files.readString(wizard);
        assertThat(src).as("the wizard has a cspNonceAttr() helper it splices onto its scripts").contains("cspNonceAttr()");
        // Strip block/Javadoc comments so a documented {@code <script>} isn't mistaken for a hand-built tag.
        String code = src.replaceAll("(?s)/\\*.*?\\*/", "");
        assertThat(Pattern.compile("<script\\b[^\"]*>").matcher(code).find())
                .as("WizardController must not hand-build a complete <script...> open tag without splicing cspNonceAttr()")
                .isFalse();
    }
}
