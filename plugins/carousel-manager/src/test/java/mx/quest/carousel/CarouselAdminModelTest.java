package mx.quest.carousel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import mx.quest.runway.spi.testkit.FakePluginSettings;
import org.junit.jupiter.api.Test;

/** The console status model counts the slides the widget would render, per site. */
class CarouselAdminModelTest {

    @Test
    void countsOnlyRenderableSlidesPerSite() {
        FakePluginSettings settings = new FakePluginSettings()
                .site("acme", Map.of("slides", "/img/a.jpg | A\n\n | no image | /x\n/img/b.jpg"))
                .site("other", Map.of("slides", "/img/c.jpg"));
        CarouselAdminModel model = new CarouselAdminModel(settings);
        assertEquals(2, model.slideCount("acme"), "blank lines and image-less lines are not slides");
        assertEquals(1, model.slideCount("other"));
        assertEquals(0, model.slideCount("unconfigured"));
    }
}
