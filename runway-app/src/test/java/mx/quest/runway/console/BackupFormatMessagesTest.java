package mx.quest.runway.console;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.support.ResourceBundleMessageSource;

/** The "newer backup format" refusal exists in both console languages and fills in both versions. */
class BackupFormatMessagesTest {

    private static ResourceBundleMessageSource bundle() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("i18n/operator-messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false); // the workstation runs es_MX; English must not silently become ES
        return source;
    }

    @ParameterizedTest
    @ValueSource(strings = {"op.appbackups.newerFormat", "op.backups.newerFormat"})
    void bothLanguagesFormatTheVersions(String key) {
        ResourceBundleMessageSource source = bundle();
        Object[] args = {2, 1};

        String en = source.getMessage(key, args, Locale.ENGLISH);
        String es = source.getMessage(key, args, Locale.forLanguageTag("es"));

        for (String text : new String[] {en, es}) {
            assertTrue(text.contains("2") && text.contains("1"), text);
            assertFalse(text.contains("{0}") || text.contains("{1}"), "placeholders are filled: " + text);
        }
        assertTrue(en.contains("newer version of Runway"), en);
        assertTrue(es.contains("versión más reciente de Runway"), es);
        assertNotEquals(en, es, "the Spanish bundle carries its own translation");
    }
}
