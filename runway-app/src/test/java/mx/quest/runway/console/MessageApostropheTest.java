package mx.quest.runway.console;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * A console message without arguments is printed verbatim, so it must not carry the MessageFormat apostrophe escape.
 *
 * <h2>Why</h2>
 * Spring's message source runs a message through {@link java.text.MessageFormat} only when it is given arguments
 * ({@code alwaysUseMessageFormat} is off). In a message with {@code {0}}, {@code ''} is a literal apostrophe; in one
 * without, it reaches the page as two. The manifest hint showed "the site''s manifest" until 1.0.0-rc1.
 */
class MessageApostropheTest {

    private static final Pattern ARGUMENT = Pattern.compile("\\{\\d");

    @ParameterizedTest
    @ValueSource(strings = {"i18n/operator-messages.properties", "i18n/operator-messages_es.properties"})
    void messagesWithoutArgumentsUseASingleApostrophe(String bundle) throws IOException {
        Properties messages = new Properties();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(bundle)) {
            messages.load(new InputStreamReader(in, StandardCharsets.UTF_8));
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
