package mx.quest.carousel;

import mx.quest.runway.spi.ContentWidget;
import mx.quest.runway.spi.service.PluginSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The plugin's {@code mainClass} — the single {@code @Configuration} registered into the host context before
 * refresh. Beans are wired EXPLICITLY (no {@code @ComponentScan}). The package {@code mx.quest.carousel} lives
 * OUTSIDE {@code mx.quest.runway} so the host never double-scans it, and the plugin links nothing but the SPI.
 *
 * <p><b>Bean names start with the plugin id in lowerCamelCase</b> ({@code carouselManager…}): plugins share the
 * host's bean namespace, and the host refuses any other name. The templates read
 * the admin model by that name ({@code ${@carouselManagerAdminModel…}}).
 *
 * <p>{@link PluginSettings} is injected as a {@code @Bean} method parameter of this class, which comes from the
 * plugin's own jar — that is what binds the instance to {@code carousel-manager}'s settings; no id is passed.
 * The {@link CarouselWidget} is a list-collected {@code ContentWidget} SPI — a plain {@code @Bean}, NEVER
 * {@code @Primary}.
 */
@Configuration(proxyBeanMethods = false)
public class CarouselConfig {

    @Bean
    public ContentWidget carouselManagerWidget(PluginSettings settings) {
        return new CarouselWidget(settings);
    }

    @Bean
    public CarouselAdminModel carouselManagerAdminModel(PluginSettings settings) {
        return new CarouselAdminModel(settings);
    }
}
