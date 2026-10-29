package mx.quest.demodatabase;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import mx.quest.database.spi.Databases;
import mx.quest.runway.spi.CaptchaService;
import mx.quest.runway.spi.ContentWidget;
import mx.quest.runway.spi.EndpointContributor;
import mx.quest.runway.spi.model.PageMeta;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.spi.model.WidgetView;
import mx.quest.runway.spi.service.PluginSettings;
import mx.quest.runway.spi.testkit.FakePluginSettings;
import mx.quest.runway.spi.testkit.FakeSiteServices;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class DemoDatabaseConfigTest {

    @Test
    void theConfigurationWiresFromTheDatabaseSpiAndKernelServicesAlone() {
        try (AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(PluginSettings.class, FakePluginSettings::new);
            ctx.registerBean(FakeSiteServices.class, FakeSiteServices::new);
            ctx.registerBean(CaptchaService.class, () -> GuestbookEndpointsTest.captcha(false, ""));
            ctx.registerBean("databaseDatabases", Databases.class, GuestbookEndpointsTest::unavailable);
            ctx.register(DemoDatabaseConfig.class);
            ctx.refresh();
            for (String name : ctx.getBeanDefinitionNames()) {
                if (ctx.getBeanDefinition(name).getFactoryMethodName() != null
                        && !name.equals("databaseDatabases")) {
                    assertThat(name).startsWith("runwayDemoDatabase");
                }
            }
            assertThat(ctx.getBeansOfType(EndpointContributor.class)).hasSize(1);
            assertThat(ctx.getBeansOfType(ContentWidget.class)).hasSize(1);
        }
    }

    @Test
    void theWidgetRendersAStaticShellWithEscapedTextAndNoRows() {
        GuestbookWidget widget = new GuestbookWidget(new FakePluginSettings(), new FakeSiteServices(),
                GuestbookEndpointsTest.captcha(true, "solved"));
        SiteView site = new SiteView("acme", "Acme", Locale.ENGLISH, List.of(Locale.ENGLISH), null);
        PageMeta page = new PageMeta("guests", "Guests", "", List.of(Locale.ENGLISH), Locale.ENGLISH, true, false);
        WidgetView view = new WidgetView(site, page, Locale.ENGLISH, "g1", false, Map.of(),
                Map.of("guestbook", "thanks"), "/guests\"<x>");
        String html = widget.render(view).html();
        assertThat(html).contains("action=\"/guestbook/sign\"", "data-src=\"/guestbook/entries.json\"",
                "name=\"website\"", "guestbook.js", "role=\"status\"", "<div class=\"challenge\"></div>")
                .contains("value=\"/guests&quot;&lt;x&gt;\"").doesNotContain("<x>");
    }

    @Test
    void theCompiledClassesLinkNoHostInternals() throws Exception {
        Path classes = Path.of("target", "classes");
        Pattern host = Pattern.compile("mx[/.]quest[/.]runway[/.](?!spi[/.])[\\w/.$]*");
        Pattern internal = Pattern.compile("mx[/.]quest[/.]database[/.](?!spi[/.])[\\w/.$]*");
        List<String> problems = new ArrayList<>();
        try (Stream<Path> files = Files.walk(classes)) {
            for (Path f : files.filter(x -> x.toString().endsWith(".class")).toList()) {
                String text = new String(Files.readAllBytes(f), StandardCharsets.ISO_8859_1);
                for (Pattern p : List.of(host, internal)) {
                    Matcher m = p.matcher(text);
                    while (m.find()) {
                        problems.add(f + " → " + m.group());
                    }
                }
            }
        }
        assertThat(problems).as("only the Runway SPI and the database SPI").isEmpty();
    }
}
