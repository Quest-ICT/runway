package mx.quest.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A message without arguments is printed verbatim, so it must not carry the MessageFormat apostrophe escape.
 *
 * <h2>Why</h2>
 * The kernel's message source runs a message through {@link java.text.MessageFormat} only when it is given
 * arguments. In a message with {@code {0}}, {@code ''} is a literal apostrophe; in one without, it reaches the page
 * as two. The per-site protection screen said "this site''s login" until 1.0.0 (reported from a live deployment).
 */
class MessageApostropheTest {

    private static final Pattern ARGUMENT = Pattern.compile("\\{\\d");

    @ParameterizedTest
    @ValueSource(strings = {"i18n/messages.properties", "i18n/messages_es.properties"})
    void messagesWithoutArgumentsUseASingleApostrophe(String bundle) throws IOException {
        Properties messages = new Properties();
        try (Reader in = Files.newBufferedReader(Path.of(bundle), StandardCharsets.UTF_8)) {
            messages.load(in);
        }
        List<String> doubled = new ArrayList<>();
        messages.forEach((key, value) -> {
            String text = (String) value;
            if (!ARGUMENT.matcher(text).find() && text.contains("''")) {
                doubled.add(key + "=" + text);
            }
        });
        assertEquals(List.of(), doubled, bundle + ": these print '' verbatim");
    }
}
