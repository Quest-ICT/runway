package mx.quest.carousel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import mx.quest.runway.spi.model.PageMeta;
import mx.quest.runway.spi.model.RenderFragment;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.spi.model.WidgetView;
import mx.quest.runway.spi.testkit.FakePluginSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The carousel widget: slide parsing, rendering, escaping, setting gating, defaults and clamping, and the
 * empty-when-unconfigured case — against the SPI testkit's {@link FakePluginSettings}, configured with the
 * descriptor's real {@code siteSettings:} defaults (read from {@code plugin.yml}) the way the kernel applies them.
 */
class CarouselWidgetTest {

    private FakePluginSettings settings;
    private CarouselWidget widget;

    @BeforeEach
    void setUp() throws IOException {
        settings = new FakePluginSettings().defaults(descriptorDefaults());
        widget = new CarouselWidget(settings);
    }

    /** The {@code default:} of every {@code siteSettings:} entry in {@code plugin.yml}, typed as the kernel types them. */
    static Map<String, Object> descriptorDefaults() throws IOException {
        String yml = Files.readString(Path.of("plugin.yml"), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("\\{\\s*key:\\s*(\\w+)\\s*,\\s*type:\\s*(\\w+)[^}]*?default:\\s*([^,}]+?)\\s*[,}]")
                .matcher(yml);
        Map<String, Object> out = new LinkedHashMap<>();
        while (m.find()) {
            String raw = m.group(3);
            Object value = switch (m.group(2)) {
                case "boolean" -> Boolean.valueOf(raw);
                case "int" -> Long.valueOf(raw);
                default -> raw;
            };
            out.put(m.group(1), value);
        }
        return out;
    }

    private String html(String siteId) {
        SiteView site = new SiteView(siteId, "Acme", Locale.ENGLISH, List.of(Locale.ENGLISH), null);
        PageMeta page = new PageMeta("home", "Home", "", List.of(Locale.ENGLISH), Locale.ENGLISH, true, false);
        RenderFragment fragment = widget.render(new WidgetView(site, page, Locale.ENGLISH, "hero", false, Map.of()));
        return fragment.isEmpty() ? "" : fragment.html();
    }

    private String render(Map<String, ?> stored) {
        settings.site("acme", stored);
        return html("acme");
    }

    private static String attr(String html, String name) {
        Matcher m = Pattern.compile(name + "=\"([^\"]*)\"").matcher(html);
        return m.find() ? m.group(1) : null;
    }

    @Test
    void keyIsCarousel() {
        assertEquals("carousel", widget.key());
    }

    @Test
    void rendersConfiguredSlidesWithCaptionsAndLinks() {
        String html = render(Map.of("slides", "/img/a.jpg | Welcome | /about\n/img/b.jpg | Our work | /work"));
        assertTrue(html.contains("src=\"/img/a.jpg\""), "first slide image");
        assertTrue(html.contains("src=\"/img/b.jpg\""), "second slide image");
        assertTrue(html.contains(">Welcome</figcaption>"), "caption rendered");
        assertTrue(html.contains("href=\"/about\""), "link rendered");
        assertTrue(html.contains("/__plugin/carousel-manager/static/carousel.css"), "references the plugin CSS");
        assertTrue(html.contains("/__plugin/carousel-manager/static/carousel.js"), "references the plugin JS");
        assertEquals(1, html.split("is-active", -1).length - 1, "exactly the first slide is active");
        assertTrue(html.indexOf("is-active") < html.indexOf("/img/a.jpg"), "…and it is the first one");
    }

    @Test
    void anImageOnlySlideNeedsNoCaptionOrLink() {
        String html = render(Map.of("slides", "/img/solo.jpg"));
        assertTrue(html.contains("src=\"/img/solo.jpg\""));
        assertFalse(html.contains("<a class=\"rw-carousel-link\""), "no link element when none configured");
        assertFalse(html.contains("rw-carousel-caption"), "no caption element when none configured");
    }

    @Test
    void noSlidesRendersEmpty() {
        assertTrue(render(Map.of()).isEmpty(), "an unconfigured carousel renders nothing");
        assertTrue(render(Map.of("slides", "   \n  ")).isEmpty(), "blank slide lines render nothing");
        assertTrue(render(Map.of("slides", " | caption only | /x")).isEmpty(), "a line with no image is dropped");
    }

    @Test
    void aCaptionWithACommaSurvivesAndWindowsLineEndingsSplit() {
        String html = render(Map.of("slides", "/img/a.jpg | Fast, fresh, local | /x\r\n/img/b.jpg"));
        assertTrue(html.contains(">Fast, fresh, local</figcaption>"), "the text setting is never comma-split");
        assertTrue(html.contains("src=\"/img/b.jpg\""), "a CRLF line ending separates slides");
    }

    @Test
    void captionsAndControlsHonorTheirFlags() {
        String noCaptions = render(Map.of("slides", "/img/a.jpg | Hi | /x\n/img/b.jpg | Yo | /y",
                "showCaptions", false));
        assertFalse(noCaptions.contains("rw-carousel-caption"), "captions off ⇒ no caption element");
        assertTrue(noCaptions.contains("alt=\"Hi\""), "…the caption still serves as the image's alt text");
        String noControls = render(Map.of("slides", "/img/a.jpg\n/img/b.jpg", "showControls", false));
        assertFalse(noControls.contains("rw-carousel-next"), "controls off ⇒ no next button");
        assertEquals("false", attr(noControls, "data-controls"));
    }

    @Test
    void flagsStoredAsTextAreReadToo() {
        String html = render(Map.of("slides", "/img/a.jpg | Hi\n/img/b.jpg", "showCaptions", "off",
                "showControls", "no"));
        assertFalse(html.contains("rw-carousel-caption"), "\"off\" turns captions off");
        assertFalse(html.contains("rw-carousel-next"), "\"no\" turns controls off");
    }

    @Test
    void theDefaultsShowCaptionsAndControlsAndAdvanceEveryFiveSeconds() {
        String html = render(Map.of("slides", "/img/a.jpg | Hi | /x\n/img/b.jpg"));
        assertEquals("5", attr(html, "data-interval"));
        assertEquals("true", attr(html, "data-controls"));
        assertTrue(html.contains("rw-carousel-prev") && html.contains("rw-carousel-next"), "two slides ⇒ controls");
        assertTrue(html.contains("rw-carousel-caption"), "captions on by default");
    }

    @Test
    void controlsNeedMoreThanOneSlide() {
        String html = render(Map.of("slides", "/img/a.jpg"));
        assertFalse(html.contains("rw-carousel-prev"), "a single slide has nothing to step through");
    }

    @Test
    void theIntervalIsReadAndClampedToTheDeclaredRange() {
        assertEquals("12", attr(render(Map.of("slides", "/img/a.jpg", "intervalSeconds", 12L)), "data-interval"));
        assertEquals("12", attr(render(Map.of("slides", "/img/a.jpg", "intervalSeconds", "12")), "data-interval"));
        assertEquals("0", attr(render(Map.of("slides", "/img/a.jpg", "intervalSeconds", 0L)), "data-interval"),
                "0 = auto-advance off");
        assertEquals("60", attr(render(Map.of("slides", "/img/a.jpg", "intervalSeconds", 600L)), "data-interval"));
        assertEquals("0", attr(render(Map.of("slides", "/img/a.jpg", "intervalSeconds", -3L)), "data-interval"));
        assertEquals("5", attr(render(Map.of("slides", "/img/a.jpg", "intervalSeconds", "soon")), "data-interval"),
                "an unparsable hand-edited value falls back to the default");
    }

    @Test
    void codeFallbacksEqualTheDescriptorDefaults() throws IOException {
        Map<String, Object> declared = descriptorDefaults();
        assertEquals(Map.of("intervalSeconds", 5L, "showCaptions", true, "showControls", true), declared,
                "plugin.yml declares the defaults this test (and the docs) assume");
        String slides = "/img/a.jpg | Hi | /x\n/img/b.jpg | Yo";
        String withDefaults = render(Map.of("slides", slides));
        widget = new CarouselWidget(new FakePluginSettings().site("acme", Map.of("slides", slides)));
        assertEquals(withDefaults, html("acme"), "the widget renders the same with or without the host's defaults");
    }

    @Test
    void eachSiteRendersItsOwnSlides() {
        settings.site("acme", Map.of("slides", "/img/acme.jpg"));
        settings.site("other", Map.of("slides", "/img/other.jpg"));
        assertTrue(html("acme").contains("/img/acme.jpg") && !html("acme").contains("/img/other.jpg"));
        assertTrue(html("other").contains("/img/other.jpg") && !html("other").contains("/img/acme.jpg"));
        assertTrue(html("nobody").isEmpty(), "a site with nothing saved renders nothing");
    }

    @Test
    void aCraftedCaptionOrUrlIsEscaped() {
        String html = render(Map.of("slides", "/img/a.jpg\" onerror=\"x | <script>x</script> | javascript:\"x"));
        assertFalse(html.contains("<script>x</script>"), "the caption is HTML-escaped, never injected");
        assertTrue(html.contains("&lt;script&gt;"), "…it appears escaped");
        assertFalse(html.contains("\" onerror=\""), "an image URL cannot break out of its attribute");
        assertTrue(html.contains("src=\"/img/a.jpg&quot; onerror=&quot;x\""));
        assertTrue(html.contains("href=\"javascript:&quot;x\""), "the link's quote is escaped too");
    }

    @Test
    void anUnsafeSiteIdIsRefusedNotRead() {
        SiteView site = new SiteView("../etc", "x", Locale.ENGLISH, List.of(Locale.ENGLISH), null);
        PageMeta page = new PageMeta("home", "Home", "", List.of(Locale.ENGLISH), Locale.ENGLISH, true, false);
        assertThrows(IllegalArgumentException.class,
                () -> widget.render(new WidgetView(site, page, Locale.ENGLISH, "hero", false, Map.of())),
                "the settings service refuses it; the host's containment turns that into an empty widget");
    }

    @Test
    void declaresItsCapability() {
        assertTrue(widget.capabilities().toString().contains("widget.carousel"));
    }
}
