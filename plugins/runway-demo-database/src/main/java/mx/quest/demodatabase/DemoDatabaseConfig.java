package mx.quest.demodatabase;

import mx.quest.database.spi.Databases;
import mx.quest.runway.spi.CaptchaService;
import mx.quest.runway.spi.service.Messages;
import mx.quest.runway.spi.service.PluginSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The worked example of a database consumer. {@link Databases} arrives by type from the database plugin; this
 * plugin's {@code plugin.yml} declares {@code requires: database}, so it never loads without it.
 */
@Configuration(proxyBeanMethods = false)
public class DemoDatabaseConfig {

    @Bean
    public GuestbookConsumer runwayDemoDatabaseConsumer() {
        return new GuestbookConsumer();
    }

    @Bean
    public GuestbookStore runwayDemoDatabaseStore(Databases databases, GuestbookConsumer runwayDemoDatabaseConsumer) {
        return new GuestbookStore(databases, runwayDemoDatabaseConsumer);
    }

    @Bean
    public GuestbookEndpoints runwayDemoDatabaseEndpoints(GuestbookStore runwayDemoDatabaseStore,
                                                          PluginSettings settings, CaptchaService captcha) {
        return new GuestbookEndpoints(runwayDemoDatabaseStore, settings, captcha);
    }

    @Bean
    public GuestbookWidget runwayDemoDatabaseWidget(PluginSettings settings, Messages messages,
                                                    CaptchaService captcha) {
        return new GuestbookWidget(settings, messages, captcha);
    }

    @Bean
    public GuestbookConsoleController runwayDemoDatabaseConsoleController(GuestbookStore runwayDemoDatabaseStore,
                                                                          Messages messages) {
        return new GuestbookConsoleController(runwayDemoDatabaseStore, messages);
    }
}
