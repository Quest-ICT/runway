package mx.quest.demo;

import mx.quest.runway.spi.CaptchaService;
import mx.quest.runway.spi.model.PluginInfo;
import mx.quest.runway.spi.service.AccessCheck;
import mx.quest.runway.spi.service.ContentCatalogView;
import mx.quest.runway.spi.service.ContentChanges;
import mx.quest.runway.spi.service.Messages;
import mx.quest.runway.spi.service.PluginSettings;
import mx.quest.runway.spi.service.PluginSiteFiles;
import mx.quest.runway.spi.service.RequestContext;
import mx.quest.runway.spi.service.SitePages;
import mx.quest.runway.spi.service.Sites;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

/**
 * The plugin's {@code mainClass} — the single {@code @Configuration} the host registers into its context before
 * refresh (as the bean {@code plugin:runway-demo}). Every bean is declared <b>explicitly</b> here; no
 * {@code @ComponentScan} of the plugin's own package. The package ({@code mx.quest.demo}) lies outside
 * {@code mx.quest.runway}, so the host never scans it.
 *
 * <h2>Everything from the kernel arrives as an SPI service</h2>
 * The plugin links nothing of the host but {@code mx.quest.runway.spi} (a test proves it on the compiled classes).
 * Services are ordinary Spring beans, received as {@code @Bean} method parameters:
 * <ul>
 *   <li><b>plugin-scoped</b> — {@link PluginSettings}, {@link PluginSiteFiles}, {@link ContentChanges},
 *       {@link PluginInfo}: the host binds each to <i>this</i> plugin by the jar the injecting class
 *       ({@code DemoPluginConfig}) was loaded from. Inject them in a class of your own jar — never from a {@code lib/}
 *       jar, and never with a bare {@code getBean}, both of which fail at startup;</li>
 *   <li><b>kernel singletons</b> — {@link Sites}, {@link RequestContext}, {@link AccessCheck}, {@link Messages},
 *       {@link SitePages}, {@link ContentCatalogView}.</li>
 * </ul>
 *
 * <h2>Rules the kernel checks</h2>
 * <ul>
 *   <li><b>Bean names start with the plugin id in lowerCamelCase</b> ({@code runway-demo} → {@code runwayDemo…}):
 *       plugins share one bean namespace with the kernel and each other. The templates follow
 *       ({@code ${@runwayDemoAdminModel…}}).</li>
 *   <li><b>Allowed Spring surface</b>: SPI implementations, plain beans, and {@code @Controller}s mapped under
 *       {@code /__admin/runway-demo} or {@code /__app/runway-demo}. <b>Not allowed</b>: servlet {@code Filter}s (1.x's
 *       timing filter is gone — servlet filters are outside the 1.0 plugin contract), {@code SecurityFilterChain}s,
 *       bean (factory) post-processors, unrestricted {@code @ControllerAdvice}.</li>
 *   <li>Axis implementations ({@code HeadContributor}, {@code ContentWidget}, {@code EndpointContributor},
 *       {@code PageQualityCheck}, {@code ContentSource}) and callbacks ({@code SiteEventListener},
 *       {@code PluginLifecycle}) are collected <b>by type</b> — plain {@code @Bean}s, never {@code @Primary}. A bean
 *       method returns the <b>concrete</b> class, so Spring sees every interface it implements before creating it
 *       ({@link DemoShowcaseSource} is both a content source and a listener).</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class DemoPluginConfig {

    // --- app-level settings ---------------------------------------------------------------------------------

    /** Bound from {@code runway.plugin.runway-demo.*} (the descriptor's {@code settings:}); applied on restart. */
    @Bean
    @ConfigurationProperties("runway.plugin.runway-demo")
    public DemoAppProps runwayDemoAppProps() {
        return new DemoAppProps();
    }

    // --- per-site data -----------------------------------------------------------------------------------------

    @Bean
    public DemoNotesStore runwayDemoNotesStore(PluginSiteFiles files) {
        return new DemoNotesStore(files);
    }

    @Bean
    public DemoShowcaseStore runwayDemoShowcaseStore(PluginSiteFiles files, ContentChanges changes) {
        return new DemoShowcaseStore(files, changes);
    }

    // --- callbacks ---------------------------------------------------------------------------------------------

    @Bean
    public DemoLifecycle runwayDemoLifecycle() {
        return new DemoLifecycle();
    }

    @Bean
    public DemoActivity runwayDemoActivity(DemoNotesStore notes, Sites sites) {
        return new DemoActivity(notes, sites);
    }

    // --- axis implementations ----------------------------------------------------------------------------------

    @Bean
    @Order(200) // after the kernel's SEO (@Order 100) and icon (@Order 90) head contributors
    public DemoAnalyticsHead runwayDemoAnalyticsHead(DemoAppProps props, PluginSettings settings) {
        return new DemoAnalyticsHead(props, settings);
    }

    @Bean
    public DemoNoticeWidget runwayDemoNoticeWidget(DemoAppProps props, PluginSettings settings, Messages messages) {
        return new DemoNoticeWidget(props, settings, messages);
    }

    @Bean
    public DemoFeedbackWidget runwayDemoFeedbackWidget(PluginSettings settings, Messages messages,
                                                       CaptchaService captcha) {
        return new DemoFeedbackWidget(settings, messages, captcha);
    }

    @Bean
    public DemoMetaLengthCheck runwayDemoMetaLengthCheck(PluginSettings settings) {
        return new DemoMetaLengthCheck(settings);
    }

    @Bean
    public DemoEndpoints runwayDemoEndpoints(AccessCheck access, DemoNotesStore notes, Messages messages,
                                             PluginSettings settings, CaptchaService captcha) {
        return new DemoEndpoints(access, notes, messages, settings, captcha);
    }

    /** A content source AND a SOURCE-phase listener (it drops its own cache before consumers re-read). */
    @Bean
    public DemoShowcaseSource runwayDemoShowcaseSource(DemoShowcaseStore store, Messages messages) {
        return new DemoShowcaseSource(store, messages);
    }

    // --- console -------------------------------------------------------------------------------------------------

    @Bean
    public DemoAdminModel runwayDemoAdminModel(DemoNotesStore notes, DemoAppProps props, PluginSettings settings,
                                               Sites sites, SitePages pages, PluginInfo self) {
        return new DemoAdminModel(notes, props, settings, sites, pages, self);
    }

    @Bean
    public DemoConsoleController runwayDemoConsoleController(RequestContext request, AccessCheck access,
                                                             Messages messages, SitePages pages,
                                                             ContentCatalogView catalog, DemoNotesStore notes,
                                                             DemoShowcaseStore showcase, DemoActivity activity,
                                                             PluginInfo self) {
        return new DemoConsoleController(request, access, messages, pages, catalog, notes, showcase, activity, self);
    }
}
