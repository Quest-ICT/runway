package mx.quest.runway.console;

import java.util.Set;
import mx.quest.runway.plugin.RunningPlugins;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

/**
 * Resolves the <b>operator-console</b> templates, which — unlike tenant pages — are <b>kernel-owned</b> and
 * live on the classpath ({@code templates/operator/**}), never in the tenant-editable external tree. This is a
 * conscious, documented divergence from "templates live in external": that rule governs <em>tenant</em>
 * presentation; operator chrome is platform code and must not be operator-editable (nor able to be shadowed by
 * a stale on-disk copy after an upgrade).
 *
 * <p>Like the tenant resolver it uses <b>decoupled logic</b> (zero Thymeleaf in the {@code .html}; the
 * {@code th:*} lives in a sibling {@code .th.xml}) and keeps the cache <b>on</b> (dev/production parity). It is
 * ordered <b>after</b> {@link mx.quest.runway.web.SiteTemplateResolver} (order 1) and only claims the
 * {@code operator/*} name space (so every other name falls through to Boot's default classpath resolver); a
 * view name like {@code operator/op-layout} maps to {@code classpath:/templates/operator/op-layout.html}.
 */
@Configuration(proxyBeanMethods = false)
public class OperatorTemplatingConfig {

    /** The classpath resolver for {@code operator/*} templates (decoupled logic, cache on, order 2). */
    @Bean
    public ClassLoaderTemplateResolver operatorTemplateResolver() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setResolvablePatterns(Set.of("operator/*"));
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setUseDecoupledLogic(true);
        resolver.setCacheable(true);
        resolver.setOrder(2);
        return resolver;
    }

    /** Resolves {@code __plugin__/<id>/<name>} to a loaded plugin's own {@code templates/} (dashboard cards,
     *  admin fragments) — ordered 3, after the site and operator resolvers. */
    @Bean
    public PluginTemplateResolver pluginTemplateResolver(RunningPlugins runningPlugins) {
        return new PluginTemplateResolver(runningPlugins);
    }
}
