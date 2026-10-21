package mx.quest.runway.i18n;

import mx.quest.runway.console.OperatorAwareMessageSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.thymeleaf.spring6.SpringTemplateEngine;

/**
 * Wires the engine's {@code #{}} message source. One source is installed, but it routes per request: tenant
 * pages resolve against the per-site {@link SiteAwareMessageSource}, operator-console pages against the operator
 * bundle (the {@link OperatorAwareMessageSource} delegate). Setting it on the engine (rather than as the
 * application's {@code messageSource}) scopes resolution to template rendering and leaves the application
 * context's own messages untouched.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(I18nProperties.class)
public class MessageSourceWiring {

    public MessageSourceWiring(SpringTemplateEngine templateEngine, OperatorAwareMessageSource messageSource) {
        templateEngine.setTemplateEngineMessageSource(messageSource);
    }
}
