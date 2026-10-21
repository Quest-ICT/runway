package mx.quest.runway.console;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves the operator console's <b>self-hosted</b> chrome assets from the classpath at {@code /__assets/**} —
 * the vendor bundles (Tabler/Bootstrap, FontAwesome, HTMX, Alpine, Chart.js) and Runway's own
 * {@code runway-chrome.css/js}. Self-hosting (not a CDN) is the locked decision: offline/air-gapped capability,
 * pinned reproducible versions, a tight CSP, and no runtime outbound call.
 *
 * <p>These are <b>classpath-only</b> (in the jar), never the external tree, so an accidental on-disk copy can
 * never shadow the jar's and serve stale chrome after an upgrade (per-tenant {@code /static/**} and per-plugin
 * {@code /__plugin/<id>/static/**} stay external — those <em>are</em> tenant/plugin-editable). The handler is
 * registered at {@link Ordered#HIGHEST_PRECEDENCE} mirroring {@code PluginStaticResourceConfig}; because
 * {@code spring.web.resources.add-mappings} is already {@code false}, raising it cannot pull Boot's default
 * {@code /**} handler ahead of the page/operator controllers (the handler-ordering trap is structurally avoided). The
 * {@code /__assets} prefix is unreachable as a public page path — every {@code /__} path is blocked by
 * {@code PathSafety}/{@code PageController} — so no tenant page can claim it.
 *
 * <p><b>Caching</b> is decided per request by {@link AssetCachePolicy} (one day for a {@code ?v=}-versioned URL,
 * revalidate-every-time for an unversioned one), so the handler itself is given <b>no</b> cache period — it would
 * otherwise overwrite the policy's header with one fixed value for every request.
 */
@Configuration(proxyBeanMethods = false)
public class OperatorAssetsConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/__assets/**")
                .addResourceLocations("classpath:/static/")
                .resourceChain(true);
        registry.setOrder(Ordered.HIGHEST_PRECEDENCE);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AssetCachePolicy()).addPathPatterns("/__assets/**");
    }
}
