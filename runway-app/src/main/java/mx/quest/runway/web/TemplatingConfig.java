package mx.quest.runway.web;

import mx.quest.runway.storage.StorageLayout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the tenant-site render engine. The {@link SiteTemplateResolver} is exposed as a bean;
 * Spring Boot's auto-configured {@code SpringTemplateEngine} collects every {@code ITemplateResolver}
 * bean, so this resolver is consulted for {@code __site__/…} names (and falls through to the
 * default classpath resolver for everything else).
 *
 * <p>The template cache stays <b>on in every mode</b> (the dev/production-parity rule — no profile
 * disables it); live edits are handled by {@link TemplateCacheService}'s targeted eviction, not by
 * turning the cache off.
 */
@Configuration(proxyBeanMethods = false)
public class TemplatingConfig {

    @Bean
    public SiteTemplateResolver siteTemplateResolver(StorageLayout layout) {
        return new SiteTemplateResolver(layout);
    }
}
