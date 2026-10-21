package mx.quest.runway.console;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;

/**
 * Wires the operator console's language switching: binds {@link OperatorProperties} and registers the
 * {@link OperatorLocaleResolver} as the DispatcherServlet's {@code localeResolver} (the bean NAME is
 * load-bearing — it displaces Spring Boot's default {@code AcceptHeaderLocaleResolver}). Only operator
 * requests are affected; public tenant sites keep their own locale pipeline.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OperatorProperties.class)
public class OperatorI18nConfig {

    /** Must be named {@code localeResolver}: the DispatcherServlet resolves the locale via this exact bean name. */
    @Bean
    public LocaleResolver localeResolver(OperatorProperties operator) {
        return new OperatorLocaleResolver(operator.getUiLanguages(), operator.getDefaultLanguage());
    }
}
