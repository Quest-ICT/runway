package mx.quest.runway.widget;

import mx.quest.runway.web.WidgetAwareDecoupledLogicResolver;
import org.springframework.context.annotation.Configuration;
import org.thymeleaf.spring6.SpringTemplateEngine;

/**
 * Installs the {@link WidgetAwareDecoupledLogicResolver} on the Thymeleaf engine, so every tenant
 * template's decoupled logic gains the kernel-generated rules that fill its content-widget anchors
 * (in addition to the author's own {@code .th.xml}). Mirrors how {@code MessageSourceWiring}
 * post-configures the auto-configured engine.
 *
 * <p>The resolver is stateless (it delegates to a standard resolver for authored logic and references
 * the {@code widgetSupport} bean from generated rules), so a single shared instance serves the engine.
 */
@Configuration(proxyBeanMethods = false)
public class WidgetWiring {

    public WidgetWiring(SpringTemplateEngine templateEngine) {
        templateEngine.setDecoupledTemplateLogicResolver(new WidgetAwareDecoupledLogicResolver());
    }
}
