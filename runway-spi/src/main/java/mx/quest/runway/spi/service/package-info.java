/**
 * The SPI <b>service tier</b> (since SPI 0.4.0): narrow interfaces the <b>kernel implements</b> and a plugin
 * <b>receives by ordinary Spring injection</b>, plus the callbacks a plugin implements and the host calls. They
 * replace the host-internal classes plugins used to inject: since SPI 1.0.0 a plugin that links a {@code runway-app}
 * class is refused at load time (blocked, with the reason on the plugins screen).
 *
 * <h2>Rules that apply to every service</h2>
 * <ul>
 *   <li><b>Nullability.</b> No method returns {@code null}. Every parameter is non-null unless documented
 *       nullable.</li>
 *   <li><b>Unsafe input.</b> An unsafe site id, slug or relative path throws {@link IllegalArgumentException}
 *       before any I/O; a well-formed id of an unknown site is not an error.</li>
 *   <li><b>Threading.</b> Every service is thread-safe. {@link mx.quest.runway.spi.service.RequestContext},
 *       {@link mx.quest.runway.spi.service.AccessCheck} and {@link mx.quest.runway.spi.service.Messages#console}
 *       read the request bound to the calling thread; off a request thread they answer empty, {@code false} or the
 *       fallback, and never throw.</li>
 *   <li><b>Isolation.</b> A plugin-scoped service ({@link mx.quest.runway.spi.service.PluginSettings},
 *       {@link mx.quest.runway.spi.service.PluginSiteFiles}, {@link mx.quest.runway.spi.service.ContentChanges},
 *       {@link mx.quest.runway.spi.model.PluginInfo}) is bound to the plugin whose own jar the injecting class came
 *       from; no parameter can name another plugin. The other services ({@link mx.quest.runway.spi.service.Sites},
 *       {@link mx.quest.runway.spi.service.RequestContext}, {@link mx.quest.runway.spi.service.AccessCheck},
 *       {@link mx.quest.runway.spi.service.Messages}, {@link mx.quest.runway.spi.service.SitePages},
 *       {@link mx.quest.runway.spi.service.ContentCatalogView}) are kernel singletons.</li>
 *   <li><b>Callbacks</b> a plugin implements ({@link mx.quest.runway.spi.service.PluginLifecycle},
 *       {@link mx.quest.runway.spi.service.SiteEventListener}) are collected by type and isolated: one that throws
 *       is logged and counted, and never affects the host or another plugin.</li>
 *   <li><b>Enums</b> in this tier may gain constants in a MINOR release; a {@code switch} over one needs a
 *       {@code default} branch.</li>
 * </ul>
 */
package mx.quest.runway.spi.service;
