package mx.quest.runway.head;

import mx.quest.runway.web.FilterOrder;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link HeadInjectionFilter} in the EDGE_EGRESS band at {@link FilterOrder#CORE_HEAD_INJECTION}.
 *
 * <p>An egress filter that rewrites the response body sits at the <b>outer</b> edge of the chain — a low
 * order is an outer wrapper whose post-chain code runs last (close to the network) — so it buffers the whole
 * downstream render and splices into the final HTML. It is therefore outer of the TENANT band
 * ({@link FilterOrder#CORE_SITE_CONTEXT}); being outer of site-context is safe only because this filter never
 * reads {@code SiteResolver.current()} — it consumes the stashed {@code runway.head.context} request
 * attribute (see {@link HeadInjectionFilter}).
 *
 * <p>Two egress filters wrap it: {@code CspFilter} ({@link FilterOrder#CORE_CSP_FINALIZE}), which stamps the CSP
 * header at commit time, and {@code CompressionFilter} ({@link FilterOrder#CORE_COMPRESSION}), which buffers what
 * this filter writes and encodes the already-spliced bytes. This filter still sets {@code Content-Length} for the
 * body it writes — the value on the wire when compression does not engage; when it does, the compression
 * filter's buffer withholds that length and sets the length of what it actually sends.
 */
@Configuration(proxyBeanMethods = false)
public class HeadInjectionConfig {

    @Bean
    public FilterRegistrationBean<HeadInjectionFilter> headInjectionFilterRegistration(
            HeadContributorRegistry registry,
            org.springframework.beans.factory.ObjectProvider<mx.quest.runway.web.PublicPageNonce> publicNonce,
            org.springframework.beans.factory.ObjectProvider<mx.quest.runway.web.CspScriptAllowlist> scriptAllowlist) {
        FilterRegistrationBean<HeadInjectionFilter> registration = new FilterRegistrationBean<>(
                new HeadInjectionFilter(registry, publicNonce.getIfAvailable(), scriptAllowlist.getIfAvailable()));
        registration.setOrder(FilterOrder.CORE_HEAD_INJECTION);
        registration.addUrlPatterns("/*");
        registration.setName("headInjectionFilter");
        return registration;
    }
}
