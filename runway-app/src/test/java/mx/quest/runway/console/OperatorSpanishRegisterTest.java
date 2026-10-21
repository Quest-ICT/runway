package mx.quest.runway.console;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The kernel console's Spanish addresses the reader as "usted", never "tú".
 *
 * <h2>Why</h2>
 * The console is a professional tool used by operators and site managers the product has never met, so it speaks
 * formally: "Elija un archivo", "su sitio", "puede". Strings written before 1.0.0-rc3 mixed in "tú" ("Elige",
 * "tu sitio", "puedes"). A review listed 35 of them; 1.0.0-rc3 rewrote 61 values in usted. This test
 * keeps new ones out.
 *
 * <h2>What it checks, and what it cannot</h2>
 * It looks for whole words that only "tú" Spanish uses: the pronouns and possessives, and a few second-person verb
 * forms that no third-person sentence shares. It deliberately leaves out forms such as "elige", "usa", "agrega" or
 * "escribe", which are also third person ("Cada sitio elige su retención") and appear in correct usted text. So a
 * tú imperative like "Escribe una nota" still needs a reviewer's eye; the list catches the common slips.
 *
 * <p>Plugins are not covered: their own bundles use "tú" on purpose.
 */
class OperatorSpanishRegisterTest {

    /** Words no usted sentence contains. Lower case; compared against each whole word of every value. */
    private static final Set<String> TU_MARKERS = Set.of(
            // pronouns and possessives
            "tú", "tu", "tus", "te", "ti", "contigo", "tuyo", "tuya", "tuyos", "tuyas",
            // second-person singular verb forms with no third-person twin
            "puedes", "podrás", "tienes", "quieres", "quieras", "necesitas", "debes", "estás", "eres", "vas",
            "eliges", "prefieras", "termines", "organices", "haz",
            // preterite tú forms
            "cambiaste", "eliminaste", "abriste", "guardaste", "hiciste",
            // tú imperatives with a pronoun attached (usted: déjelo, revíselo, ...)
            "déjalo", "déjala", "revísalo", "apruébalo", "devuélvelo", "descártalo", "retíralo",
            "descríbela", "muévela", "ajústala");

    private static final Pattern WORD = Pattern.compile("\\p{L}+");

    @Test
    void operatorSpanishUsesUsted() throws IOException {
        assertEquals(List.of(), tuStrings("i18n/operator-messages_es.properties"),
                "operator-messages_es.properties: rewrite these in usted (the console never uses tú)");
    }

    /** Each {@code key=value} whose value contains a tú marker, sorted by key. */
    private List<String> tuStrings(String bundle) throws IOException {
        Properties messages = new Properties();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(bundle)) {
            messages.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        Set<String> hits = new TreeSet<>();
        messages.forEach((key, value) -> {
            Matcher words = WORD.matcher((String) value);
            while (words.find()) {
                if (TU_MARKERS.contains(words.group().toLowerCase(Locale.ROOT))) {
                    hits.add(key + "=" + value);
                    break;
                }
            }
        });
        return List.copyOf(hits);
    }
}
