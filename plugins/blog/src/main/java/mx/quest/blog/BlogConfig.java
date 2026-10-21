package mx.quest.blog;

import mx.quest.runway.spi.ContentSource;
import mx.quest.runway.spi.service.ContentCatalogView;
import mx.quest.runway.spi.service.ContentChanges;
import mx.quest.runway.spi.service.Messages;
import mx.quest.runway.spi.service.PluginSettings;
import mx.quest.runway.spi.service.PluginSiteFiles;
import mx.quest.runway.spi.service.RequestContext;
import mx.quest.runway.spi.service.SiteEventListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The blog plugin's Spring wiring — the {@code mainClass} the host registers before context refresh. Beans are wired
 * EXPLICITLY (never {@code @ComponentScan}), and every collaborator is a Runway SPI service the kernel provides:
 * <ul>
 *   <li>{@link PluginSettings}, {@link PluginSiteFiles} and {@link ContentChanges} are <b>plugin-scoped</b> — the
 *       kernel binds each to this plugin because the injecting class ({@code BlogConfig}) comes from this plugin's
 *       jar, so they reach only {@code sites/<id>/plugins/blog.yml}, {@code sites/<id>/plugins/blog/} and this
 *       plugin's own content source;</li>
 *   <li>{@link ContentCatalogView}, {@link RequestContext} and {@link Messages} are kernel singletons.</li>
 * </ul>
 * The {@link BlogContentSource} bean is collected twice by type: as a {@link ContentSource} (routing, sitemap and
 * llms.txt, URL maps and search light up with zero further wiring) and as a {@link SiteEventListener} (its index
 * invalidation). The controller carries {@code @ConsoleScreen}, so the kernel renders it inside the operator shell.
 * Every bean name starts with the plugin id ({@code blog…}) — the kernel's bean-name rule.
 */
@Configuration(proxyBeanMethods = false)
public class BlogConfig {

    @Bean
    public BlogStore blogStore(PluginSiteFiles files) {
        return new BlogStore(files);
    }

    @Bean
    public BlogContentSource blogContentSource(BlogStore blogStore, PluginSettings settings,
                                               ContentCatalogView catalog) {
        return new BlogContentSource(blogStore, settings, catalog);
    }

    @Bean
    public BlogAdminController blogAdminController(RequestContext request, BlogStore blogStore,
                                                   BlogContentSource blogContentSource,
                                                   ContentCatalogView catalog, ContentChanges changes,
                                                   Messages messages) {
        return new BlogAdminController(request, blogStore, blogContentSource, catalog, changes, messages);
    }
}
