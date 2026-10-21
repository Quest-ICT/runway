package mx.quest.contact;

import mx.quest.runway.spi.CaptchaService;
import mx.quest.runway.spi.ContentWidget;
import mx.quest.runway.spi.EndpointContributor;
import mx.quest.runway.spi.service.AccessCheck;
import mx.quest.runway.spi.service.Messages;
import mx.quest.runway.spi.service.PluginSettings;
import mx.quest.runway.spi.service.PluginSiteFiles;
import mx.quest.runway.spi.service.SitePages;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The plugin's {@code mainClass} — the single {@code @Configuration} registered into the host context before
 * refresh. Beans are wired EXPLICITLY (no {@code @ComponentScan}). The package {@code mx.quest.contact} lives
 * OUTSIDE {@code mx.quest.runway} so the host never double-scans it.
 *
 * <p><b>SPI only.</b> Everything this plugin needs from the kernel arrives as an SPI service (SPI 1.0.0):
 * {@link PluginSettings} (this plugin's per-site settings, and the SMTP password secret in clear) and
 * {@link PluginSiteFiles} (this plugin's per-site folder, where submissions are kept) are bound to this plugin by
 * the jar this class was loaded from; {@link Messages}, {@link AccessCheck}, {@link SitePages} and the
 * {@link CaptchaService} are kernel singletons. Nothing outside {@code mx.quest.runway.spi} is linked.
 *
 * <p>The {@link ContactWidget} ({@code ContentWidget}) and {@link ContactEndpoint} ({@code EndpointContributor})
 * are list-collected SPIs — plain {@code @Bean}s, NEVER {@code @Primary}. Every bean name starts with the plugin
 * id ({@code contact…}), the kernel's rule for a bean namespace plugins share. The email seam is
 * {@link MailTransport} ({@link JakartaMailTransport} in production, using the Jakarta Mail stack the KERNEL
 * bundles as a core capability).
 */
@Configuration(proxyBeanMethods = false)
public class ContactConfig {

    @Bean
    public ContactStore contactStore(PluginSiteFiles files) {
        return new ContactStore(files);
    }

    /** The production send seam — delegates to {@code jakarta.mail.Transport} (the kernel bundles Jakarta Mail). */
    @Bean
    public MailTransport contactMailTransport() {
        return new JakartaMailTransport();
    }

    @Bean
    public ContactMailer contactMailer(PluginSettings settings, MailTransport transport) {
        return new ContactMailer(settings, transport);
    }

    @Bean
    public ContentWidget contactWidget(PluginSettings settings, Messages messages, CaptchaService captcha) {
        return new ContactWidget(settings, messages, captcha);
    }

    @Bean
    public EndpointContributor contactEndpoint(ContactStore store, ContactMailer mailer, PluginSettings settings,
                                               Messages messages, AccessCheck access, CaptchaService captcha,
                                               SitePages pages) {
        return new ContactEndpoint(store, mailer, settings, messages, access, captcha, pages);
    }

    @Bean
    public ContactAdminModel contactAdminModel(ContactStore store, ContactMailer mailer, SitePages pages) {
        return new ContactAdminModel(store, mailer, pages);
    }
}
