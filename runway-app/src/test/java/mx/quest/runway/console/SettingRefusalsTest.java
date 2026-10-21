package mx.quest.runway.console;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Locale;
import mx.quest.runway.plugin.SettingValidationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.StaticMessageSource;

/** A plugin or widget setting's refusal reads in the operator's language when it carries a code. */
class SettingRefusalsTest {

    private final StaticMessageSource messages = new StaticMessageSource();
    private final SettingRefusals refusals = new SettingRefusals(messages);

    SettingRefusalsTest() {
        messages.addMessage("op.config.kernel.invalid", Locale.ENGLISH, "{0}: {1}");
        messages.addMessage("op.config.kernel.invalid", Locale.forLanguageTag("es"), "{0}: {1}");
        messages.addMessage("op.config.invalid.outOfRange", Locale.forLanguageTag("es"), "{0} está fuera del rango permitido {1}");
    }

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void aCodedRefusalIsLocalized() {
        LocaleContextHolder.setLocale(Locale.forLanguageTag("es"));
        // As SettingType raises it: the value and the range as text, so no locale number-formats them ("3.600").
        SettingValidationException invalid = new SettingValidationException("indexnow.batch-seconds",
                "4000 is outside the allowed range 5..3600", "op.config.invalid.outOfRange", "4000", "5..3600");
        assertEquals("indexnow.batch-seconds: 4000 está fuera del rango permitido 5..3600", refusals.describe(invalid));
    }

    @Test
    void aRefusalWithoutACodeKeepsItsText() {
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        assertEquals("a.key: not a number",
                refusals.describe(new SettingValidationException("a.key", "not a number")));
    }
}
