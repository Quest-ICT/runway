package mx.quest.runway.icon;

import com.github.weisj.jsvg.SVGDocument;
import com.github.weisj.jsvg.parser.DocumentLimits;
import com.github.weisj.jsvg.parser.LoaderContext;
import com.github.weisj.jsvg.parser.SVGLoader;
import com.github.weisj.jsvg.parser.resources.ResourcePolicy;
import com.github.weisj.jsvg.view.ViewBox;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;

/**
 * Rasterizes an uploaded master SVG into the fixed-size PNG icon slots (and a favicon {@code .ico}) — the engine
 * behind the tenant <b>Icons</b> screen's "generate from SVG" action. Pure and stateless; a fresh
 * {@link SVGLoader} + {@link SVGDocument} per call (the document is not reusable across renders).
 *
 * <h2>Security</h2>
 * The SVG is <b>tenant-supplied</b>, so the loader is locked down: {@link ResourcePolicy#DENY_EXTERNAL} forbids
 * any external resource fetch (no SSRF via {@code <image href="http://…">} / {@code xlink:href}), and
 * {@link DocumentLimits#DEFAULT} bounds nesting / {@code <use>} recursion / path count (no billion-laughs or
 * use-bomb). jsvg runs <b>no scripts</b> (it is a static renderer, not a browser), and rendering targets an
 * in-memory {@link BufferedImage} (headless — no display, no AWT window). The input is size-capped by the caller
 * (the upload ceiling); a malformed SVG raises {@link IconRenderException} rather than producing a broken slot.
 */
@Component
public class IconRasterizer {

    /** A parse/render failure — the caller surfaces it as a form error, never a 500. */
    public static class IconRenderException extends Exception {
        public IconRenderException(String message, Throwable cause) {
            super(message, cause);
        }

        public IconRenderException(String message) {
            super(message);
        }
    }

    /** The largest raster dimension we will produce (guards an absurd size request → huge heap image). */
    private static final int MAX_DIMENSION = 1024;

    /**
     * Renders {@code svgBytes} to a square PNG of {@code size}×{@code size} pixels (transparent background,
     * anti-aliased, the SVG scaled to fill the square via its own viewBox).
     *
     * @return the PNG bytes (never {@code null}/empty)
     * @throws IconRenderException if the SVG cannot be parsed or rendered, or {@code size} is out of range
     */
    public byte[] toPng(byte[] svgBytes, int size) throws IconRenderException {
        if (size < 1 || size > MAX_DIMENSION) {
            throw new IconRenderException("icon size " + size + " is out of range 1.." + MAX_DIMENSION);
        }
        BufferedImage image = render(svgBytes, size);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", out)) {
                throw new IconRenderException("no PNG writer available");
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IconRenderException("could not encode PNG", e);
        }
    }

    /**
     * Renders {@code svgBytes} to a single-image {@code .ico} (a PNG of {@code size}×{@code size} embedded in an
     * ICO container — the PNG-in-ICO form every current browser reads). {@code size} must be ≤ 256 (an ICO
     * dimension byte of 0 means 256; larger will not fit the header).
     *
     * @return the ICO bytes
     * @throws IconRenderException on a render failure or a {@code size} above 256
     */
    public byte[] toIco(byte[] svgBytes, int size) throws IconRenderException {
        if (size < 1 || size > 256) {
            throw new IconRenderException("ico size " + size + " must be 1..256");
        }
        byte[] png = toPng(svgBytes, size);
        return wrapPngInIco(png, size);
    }

    private BufferedImage render(byte[] svgBytes, int size) throws IconRenderException {
        LoaderContext context = LoaderContext.builder()
                .externalResourcePolicy(ResourcePolicy.DENY_EXTERNAL)   // no SSRF from a tenant SVG
                .documentLimits(DocumentLimits.DEFAULT)                 // bound nesting / <use> / path count
                .build();
        SVGDocument document;
        try {
            document = new SVGLoader().load(new ByteArrayInputStream(svgBytes), null, context);
        } catch (RuntimeException e) {
            throw new IconRenderException("the SVG could not be parsed", e);
        }
        if (document == null) {
            throw new IconRenderException("the uploaded file is not a valid SVG");
        }
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setBackground(new Color(0, 0, 0, 0)); // transparent
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            document.render((java.awt.Component) null, g, new ViewBox(0, 0, size, size));
        } catch (RuntimeException e) {
            throw new IconRenderException("the SVG could not be rendered", e);
        } finally {
            g.dispose();
        }
        return image;
    }

    /** Wraps a PNG in a minimal single-entry ICO (ICONDIR + one ICONDIRENTRY + the PNG payload). */
    private static byte[] wrapPngInIco(byte[] png, int size) {
        int dim = (size >= 256) ? 0 : size; // 0 in an ICO dimension byte means 256
        ByteArrayOutputStream out = new ByteArrayOutputStream(22 + png.length);
        // ICONDIR
        writeLe16(out, 0);           // reserved
        writeLe16(out, 1);           // type = icon
        writeLe16(out, 1);           // image count
        // ICONDIRENTRY (16 bytes)
        out.write(dim);              // width  (0 => 256)
        out.write(dim);              // height (0 => 256)
        out.write(0);                // color count (0 = ≥ 8bpp)
        out.write(0);                // reserved
        writeLe16(out, 1);           // color planes
        writeLe16(out, 32);          // bits per pixel
        writeLe32(out, png.length);  // size of the image data
        writeLe32(out, 22);          // offset of the image data (6 ICONDIR + 16 ICONDIRENTRY)
        out.write(png, 0, png.length);
        return out.toByteArray();
    }

    private static void writeLe16(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >>> 8) & 0xFF);
    }

    private static void writeLe32(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 24) & 0xFF);
    }
}
