package mx.quest.runway.web;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * Bound {@code runway.compression.*} configuration for the kernel's single response-compression filter
 * ({@link CompressionFilter}, wired at {@link FilterOrder#CORE_COMPRESSION}). One filter owns <b>both</b>
 * Brotli and gzip, so Jetty's own gzip handler stays off ({@code server.compression.enabled: false}) — there
 * is exactly one compression decision point and no risk of double-encoding.
 *
 * <p>Brotli is preferred when the client accepts {@code br} and the brotli4j native library loaded; otherwise
 * the filter falls back to gzip, otherwise it passes the body through untouched. Only the configured
 * {@link #mimeTypes} are compressed, and only bodies at or above the per-encoding {@code min-size}.
 */
@ConfigurationProperties("runway.compression")
public class RunwayCompressionProperties {

    /** Master switch. {@code false} disables the filter entirely (no gzip, no brotli). Default: on. */
    private boolean enabled = true;

    /**
     * The largest response (in bytes) the filter buffers in order to compress it. A response that grows past
     * this cap is streamed straight to the client <b>uncompressed</b> (so large downloads — backups, media —
     * are never held whole in memory). Default: 4 MiB.
     */
    private long maxBufferBytes = 4L * 1024 * 1024;

    /**
     * The content types eligible for compression, matched against the response's base media type (charset
     * ignored). Already-compressed binaries (images, zips, fonts) are deliberately absent. Default: the common
     * text/markup/script/JSON/SVG set.
     */
    private List<String> mimeTypes = new ArrayList<>(List.of(
            "text/html", "text/xml", "text/plain", "text/css", "text/javascript",
            "application/javascript", "application/json", "application/xml", "image/svg+xml"));

    @NestedConfigurationProperty
    private final Gzip gzip = new Gzip();

    @NestedConfigurationProperty
    private final Brotli brotli = new Brotli();

    /** gzip settings — the universally-supported fallback encoding. */
    public static class Gzip {
        /** Whether gzip is offered. Default: on. */
        private boolean enabled = true;
        /** Smallest body (bytes) worth gzipping; below this the overhead is not worth it. Default: 1024. */
        private int minSize = 1024;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getMinSize() {
            return minSize;
        }

        public void setMinSize(int minSize) {
            this.minSize = minSize;
        }
    }

    /** Brotli settings — preferred when the client accepts it and the native library is available. */
    public static class Brotli {
        /** Whether brotli is offered (honored only if the brotli4j native library loaded). Default: on. */
        private boolean enabled = true;
        /** Brotli quality {@code 0..11}; 4–6 is the sensible speed/ratio range for on-the-fly encoding. Default: 5. */
        private int quality = 5;
        /** Smallest body (bytes) worth brotli-encoding. Default: 1024. */
        private int minSize = 1024;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getQuality() {
            return quality;
        }

        public void setQuality(int quality) {
            this.quality = quality;
        }

        public int getMinSize() {
            return minSize;
        }

        public void setMinSize(int minSize) {
            this.minSize = minSize;
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getMaxBufferBytes() {
        return maxBufferBytes;
    }

    public void setMaxBufferBytes(long maxBufferBytes) {
        this.maxBufferBytes = maxBufferBytes;
    }

    public List<String> getMimeTypes() {
        return mimeTypes;
    }

    public void setMimeTypes(List<String> mimeTypes) {
        this.mimeTypes = mimeTypes;
    }

    public Gzip getGzip() {
        return gzip;
    }

    public Brotli getBrotli() {
        return brotli;
    }
}
