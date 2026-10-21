package mx.quest.runway.icon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import mx.quest.runway.icon.IconRasterizer.IconRenderException;
import org.junit.jupiter.api.Test;

/** The SVG→PNG/ICO rasterizer: correct sizes, valid PNG/ICO bytes, external refs denied, bad input rejected. */
class IconRasterizerTest {

    private static final String SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 100 100\">"
            + "<rect width=\"100\" height=\"100\" rx=\"20\" fill=\"#0d6efd\"/>"
            + "<circle cx=\"50\" cy=\"50\" r=\"30\" fill=\"#fff\"/></svg>";

    private final IconRasterizer rasterizer = new IconRasterizer();

    private static byte[] svg() {
        return SVG.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void rendersAPngOfTheRequestedSize() throws Exception {
        for (int size : new int[]{180, 192, 512}) {
            byte[] png = rasterizer.toPng(svg(), size);
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
            assertNotNull(img, "the bytes decode as an image");
            assertEquals(size, img.getWidth());
            assertEquals(size, img.getHeight());
        }
    }

    @Test
    void producesAValidSingleImageIco() throws Exception {
        byte[] ico = rasterizer.toIco(svg(), 32);
        // ICONDIR: reserved=0, type=1 (icon), count=1 — all little-endian 16-bit.
        assertEquals(0, ico[0]);
        assertEquals(0, ico[1]);
        assertEquals(1, ico[2]);
        assertEquals(0, ico[3]);
        assertEquals(1, ico[4]);
        assertEquals(0, ico[5]);
        assertEquals(32, ico[6] & 0xFF, "width byte");
        assertEquals(32, ico[7] & 0xFF, "height byte");
        // The embedded payload (offset 22) is a PNG (magic 89 50 4E 47).
        assertEquals((byte) 0x89, ico[22]);
        assertEquals('P', ico[23]);
        assertEquals('N', ico[24]);
        assertEquals('G', ico[25]);
    }

    @Test
    void anExternalResourceReferenceIsNotFetchedAndStillRenders() throws Exception {
        // An <image> pointing at an unreachable host: with DENY_EXTERNAL it is skipped (no fetch, no hang),
        // and the rest of the document still rasterizes. A network fetch would make this test hang/fail.
        String withExternal = "<svg xmlns=\"http://www.w3.org/2000/svg\" "
                + "xmlns:xlink=\"http://www.w3.org/1999/xlink\" viewBox=\"0 0 100 100\">"
                + "<image xlink:href=\"http://10.255.255.1/should-not-fetch.png\" width=\"100\" height=\"100\"/>"
                + "<rect width=\"100\" height=\"100\" fill=\"#000\"/></svg>";
        byte[] png = rasterizer.toPng(withExternal.getBytes(StandardCharsets.UTF_8), 64);
        assertNotNull(ImageIO.read(new ByteArrayInputStream(png)));
    }

    @Test
    void garbageInputIsRejectedNotRendered() {
        assertThrows(IconRenderException.class,
                () -> rasterizer.toPng("this is not an svg".getBytes(StandardCharsets.UTF_8), 64));
    }

    @Test
    void anAbsurdSizeIsRejected() {
        IconRenderException e = assertThrows(IconRenderException.class, () -> rasterizer.toPng(svg(), 100_000));
        assertTrue(e.getMessage().contains("out of range"));
        assertThrows(IconRenderException.class, () -> rasterizer.toIco(svg(), 512)); // ICO cap is 256
    }
}
