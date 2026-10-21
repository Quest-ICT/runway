package mx.quest.runway.icon;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import mx.quest.runway.icon.IconRasterizer.IconRenderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Generates a site's full icon set from one uploaded master SVG — the "generate from SVG" action of the tenant
 * <b>Icons</b> screen, which closes the manual per-slot upload gap. From a single SVG it produces the raster
 * slots ({@link SiteIcon#APPLE_TOUCH 180}, {@link SiteIcon#ICON_192 192}, {@link SiteIcon#ICON_512 512}) and the
 * legacy {@link SiteIcon#FAVICON_ICO favicon.ico}, and keeps the SVG itself as {@link SiteIcon#SVG_FAVICON}.
 *
 * <p><b>All-or-nothing:</b> every slot is rendered into memory <em>first</em>; only if all succeed are they
 * written. A bad/unsupported SVG therefore leaves the site's existing icons untouched (and raises
 * {@link IconRenderException} for the screen to report) rather than half-replacing the set.
 */
@Service
public class IconGenerationService {

    private static final Logger log = LoggerFactory.getLogger(IconGenerationService.class);

    /** The raster slots produced from the SVG, each with its pixel size. Order is the storage/report order. */
    private static final Map<SiteIcon, Integer> RASTER_SIZES = new LinkedHashMap<>();

    static {
        RASTER_SIZES.put(SiteIcon.APPLE_TOUCH, 180);
        RASTER_SIZES.put(SiteIcon.ICON_192, 192);
        RASTER_SIZES.put(SiteIcon.ICON_512, 512);
    }

    /** The favicon.ico is generated at this pixel size (PNG-in-ICO). */
    private static final int ICO_SIZE = 32;

    private final IconRasterizer rasterizer;
    private final SiteIconService icons;

    public IconGenerationService(IconRasterizer rasterizer, SiteIconService icons) {
        this.rasterizer = rasterizer;
        this.icons = icons;
    }

    /**
     * Generates the SVG favicon, the three PNG raster slots, and favicon.ico for {@code siteId} from
     * {@code svgBytes}, storing each. The bytes are rasterized before any store, so a render failure changes
     * nothing on disk.
     *
     * @param siteId   the bound tenancy (traversal-validated by the storage layer)
     * @param svgBytes the uploaded master SVG (size already capped by the upload boundary)
     * @return the slots written (the SVG plus the four generated rasters)
     * @throws IconRenderException if the SVG cannot be parsed/rendered — nothing is written
     * @throws IOException         if a store write fails
     */
    public Set<SiteIcon> generateFrom(String siteId, byte[] svgBytes) throws IconRenderException, IOException {
        // 1) render everything up front (fail fast, write nothing on a bad SVG)
        Map<SiteIcon, byte[]> generated = new LinkedHashMap<>();
        generated.put(SiteIcon.SVG_FAVICON, svgBytes);
        for (Map.Entry<SiteIcon, Integer> slot : RASTER_SIZES.entrySet()) {
            generated.put(slot.getKey(), rasterizer.toPng(svgBytes, slot.getValue()));
        }
        generated.put(SiteIcon.FAVICON_ICO, rasterizer.toIco(svgBytes, ICO_SIZE));

        // 2) all renders succeeded — now persist
        for (Map.Entry<SiteIcon, byte[]> slot : generated.entrySet()) {
            icons.store(siteId, slot.getKey(), slot.getValue());
        }
        log.info("generated {} icon slot(s) from an SVG for site '{}'", generated.size(), siteId);
        return generated.keySet();
    }
}
