package mx.quest.runway.console;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Operator-console UI settings, bound from {@code runway.operator.*}. Today this is the set of UI languages the
 * console's language switcher offers and the fallback language. It is a <b>platform</b> concern (the operator
 * console is platform chrome), not a per-tenant one.
 */
@ConfigurationProperties("runway.operator")
public class OperatorProperties {

    /**
     * The UI languages the operator console offers in its language switcher, as BCP-47 language codes, in the
     * order shown. Each needs a matching {@code operator-messages[_xx].properties} bundle and an
     * {@code op.lang.<code>} label. Default: {@code [en, es]} (Runway ships both operator bundles).
     */
    private List<String> uiLanguages = List.of("en", "es");

    /** The fallback UI language when the browser requests none of the offered ones. Default: {@code en}. */
    private String defaultLanguage = "en";

    public List<String> getUiLanguages() {
        return uiLanguages;
    }

    public void setUiLanguages(List<String> uiLanguages) {
        this.uiLanguages = uiLanguages;
    }

    public String getDefaultLanguage() {
        return defaultLanguage;
    }

    public void setDefaultLanguage(String defaultLanguage) {
        this.defaultLanguage = defaultLanguage;
    }
}
