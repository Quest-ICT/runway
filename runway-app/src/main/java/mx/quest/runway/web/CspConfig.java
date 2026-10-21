package mx.quest.runway.web;

import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the single {@link CspFilter} at {@link FilterOrder#CORE_CSP_FINALIZE} and binds
 * {@link RunwayCspProperties}. Mirrors {@link CompressionConfig}: the {@link FilterRegistrationBean} pins the
 * order and suppresses Spring Boot's default filter auto-registration, so the filter is wired exactly once at
 * its egress slot (outer of head-injection, inner of compression).
 *
 * <p>Every {@link CspHostContributor} bean (kernel or plugin) is collected and its hosts folded into the built
 * policy — that is how the essential protection plugin allowlists the CAPTCHA provider domains without the kernel
 * knowing any provider. With no contributors the list is empty and the policy is unchanged.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RunwayCspProperties.class)
public class CspConfig {

    @Bean
    public CspFilter cspFilter(RunwayCspProperties properties, List<CspHostContributor> contributors,
                               mx.quest.runway.site.SiteCspService siteCsp) {
        List<String> hosts = pluginHosts(contributors);
        return new CspFilter(properties, hosts, new SiteCspPolicies(properties, hosts, siteCsp));
    }

    /**
     * The host-allowlist {@code script-src} — the same tokens the policy carries after the nonce — that decides which
     * public-page scripts the edge stamps with the page nonce ({@code ScriptNonceStamper}): only those the allowlist
     * policy would have run.
     */
    @Bean
    public CspScriptAllowlist cspScriptAllowlist(RunwayCspProperties properties,
                                                 List<CspHostContributor> contributors) {
        return properties.scriptAllowlist(pluginHosts(contributors));
    }

    /**
     * The public-page nonce source: configured from {@link RunwayCspProperties#isPublicNonceActive()},
     * its secret at {@code <external>/}{@value PublicPageNonce#KEY_FILE} — created lazily on the first page that
     * needs it, never at startup, so a disabled feature leaves no file behind. The allowlist's fingerprint joins the
     * ETag fold, because which scripts get stamped depends on it.
     */
    @Bean
    public PublicPageNonce publicPageNonce(RunwayCspProperties properties,
                                           mx.quest.runway.storage.StorageLayout layout,
                                           CspScriptAllowlist cspScriptAllowlist) {
        return new PublicPageNonce(properties.isPublicNonceActive(),
                layout.external().resolve(PublicPageNonce.KEY_FILE), cspScriptAllowlist.fingerprint());
    }

    private static List<String> pluginHosts(List<CspHostContributor> contributors) {
        return contributors.stream()
                .flatMap(c -> c.cspHosts().stream())
                .filter(h -> h != null && !h.isBlank())
                .toList();
    }

    @Bean
    public FilterRegistrationBean<CspFilter> cspFilterRegistration(CspFilter filter) {
        FilterRegistrationBean<CspFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(FilterOrder.CORE_CSP_FINALIZE);
        registration.addUrlPatterns("/*");
        registration.setName("cspFilter");
        return registration;
    }
}
