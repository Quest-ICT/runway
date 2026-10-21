package mx.quest.runway.icon;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import javax.imageio.ImageIO;
import mx.quest.runway.icon.IconRasterizer.IconRenderException;
import mx.quest.runway.storage.SiteFileStore;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Generating the full icon set from one SVG: all slots stored, correct sizes; a bad SVG writes nothing. */
class IconGenerationServiceTest {

    private static final String SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 10 10\">"
            + "<rect width=\"10\" height=\"10\" fill=\"#0a0\"/></svg>";

    private SiteIconService icons;
    private IconGenerationService generation;

    @BeforeEach
    void setUp(@TempDir java.nio.file.Path external) {
        StorageProperties props = new StorageProperties();
        props.setExternal(external);
        StorageLayout layout = StorageLayout.from(props);
        icons = new SiteIconService(new SiteFileStore(layout));
        generation = new IconGenerationService(new IconRasterizer(), icons);
    }

    @Test
    void generatesTheSvgTheThreeRastersAndTheIco() throws Exception {
        Set<SiteIcon> written = generation.generateFrom("acme", SVG.getBytes(StandardCharsets.UTF_8));

        assertEquals(Set.of(SiteIcon.SVG_FAVICON, SiteIcon.APPLE_TOUCH, SiteIcon.ICON_192, SiteIcon.ICON_512,
                SiteIcon.FAVICON_ICO), written);
        assertEquals(written, icons.presentSlots("acme"));

        // The SVG slot keeps the original bytes verbatim.
        assertArrayEquals(SVG.getBytes(StandardCharsets.UTF_8), icons.read("acme", SiteIcon.SVG_FAVICON).orElseThrow());
        // The 512 slot is a real 512×512 PNG.
        BufferedImage png = ImageIO.read(new ByteArrayInputStream(icons.read("acme", SiteIcon.ICON_512).orElseThrow()));
        assertEquals(512, png.getWidth());
        assertEquals(512, png.getHeight());
    }

    @Test
    void aBadSvgWritesNothing() {
        assertThrows(IconRenderException.class,
                () -> generation.generateFrom("acme", "not an svg".getBytes(StandardCharsets.UTF_8)));
        assertTrue(icons.presentSlots("acme").isEmpty(), "a failed generation leaves the site's icons untouched");
    }
}
