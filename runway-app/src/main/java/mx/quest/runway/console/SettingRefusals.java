package mx.quest.runway.console;

import mx.quest.runway.plugin.SettingValidationException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

/**
 * Why a settings value was refused, in the operator's language: the field, then the reason from the refusal's
 * message code ({@code op.config.invalid.*}). A refusal without a code, or a code with no message, falls back to its
 * English text, so the operator always learns which field and why. Used by the plugin settings forms (app level and
 * per site) and the widget settings; Server settings resolves its own field labels.
 */
@Component
public class SettingRefusals {

    private final MessageSource messages;

    public SettingRefusals(@Qualifier("operatorAwareMessageSource") MessageSource messages) {
        this.messages = messages;
    }

    /** The refusal for the form's flash: {@code <field>: <reason>}, the reason localized when it carries a code. */
    public String describe(SettingValidationException invalid) {
        var locale = LocaleContextHolder.getLocale();
        String field = invalid.key();
        String detail = invalid.code() == null ? invalid.getMessage()
                : messages.getMessage(invalid.code(), invalid.args(), invalid.getMessage(), locale);
        return messages.getMessage("op.config.kernel.invalid", new Object[] {field, detail}, field + ": " + detail,
                locale);
    }
}
