package mx.quest.runway.web;

import com.aayushatharva.brotli4j.Brotli4jLoader;
import com.aayushatharva.brotli4j.encoder.Encoder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.GZIPOutputStream;
import mx.quest.runway.platform.HostSettings;
import mx.quest.runway.platform.PlatformSettingsStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The kernel's single response-compression filter — it owns <b>both</b> Brotli and gzip, so Jetty's own gzip
 * handler stays off and there is exactly one compression decision (no double-encoding). Registered at
 * {@link FilterOrder#CORE_COMPRESSION}: inner of the bandwidth meter (which therefore counts the compressed
 * wire bytes) and outer of {@link mx.quest.runway.head.HeadInjectionFilter} (so head markup is spliced in
 * <em>before</em> the body is encoded).
 *
 * <p>It buffers the body once (via {@link BufferingResponseWrapper}, bounded — a large download streams through
 * uncompressed), then picks the best encoding the client will accept: <b>brotli preferred</b> (when enabled,
 * accepted, and the brotli4j native library loaded), <b>gzip</b> as the universal fallback, else the body is
 * sent untouched. Only the configured {@link RunwayCompressionProperties#getMimeTypes() mime types} and bodies
 * at or above the per-encoding {@code min-size} are compressed; an already-encoded response is never touched;
 * and {@code Vary: Accept-Encoding} is added so shared caches key on the negotiated encoding.
 *
 * <p><b>Egress-filter contract:</b> its {@code pre} runs before the site is bound (see {@link FilterOrder}), so
 * it does all its work in {@code post} and never reads the resolved site — it decides purely from the request's
 * {@code Accept-Encoding} and the response's content type.
 */
public class CompressionFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CompressionFilter.class);

    private final PlatformSettingsStore platform;
    /** Whether the server itself sets a property; such a switch ignores the console's override. */
    private final java.util.function.Predicate<String> setOnHost;
    private final boolean configEnabled;
    private final boolean configGzipEnabled;
    private final boolean configBrotliEnabled;
    private final long maxBufferBytes;
    private final Set<String> compressibleTypes;
    private final int gzipMinSize;
    private final int brotliMinSize;
    private final int brotliQuality;
    private final Encoder.Parameters brotliParams;
    private final boolean brotliNativeAvailable;

    public CompressionFilter(RunwayCompressionProperties props, PlatformSettingsStore platform) {
        this(props, platform, key -> false);
    }

    /**
     * @param setOnHost whether the server itself sets a property ({@link HostSettings#setOnHost}): such a switch
     *                  ignores the Platform-settings override, because the host's value wins (one home per
     *                  setting). Until 1.0.0 the console's stored switches always won, so a later change to
     *                  {@code runway.compression.*} in the host's {@code application.yml} was silently ignored.
     */
    public CompressionFilter(RunwayCompressionProperties props, PlatformSettingsStore platform, HostSettings host) {
        this(props, platform, host::setOnHost);
    }

    private CompressionFilter(RunwayCompressionProperties props, PlatformSettingsStore platform,
                              java.util.function.Predicate<String> setOnHost) {
        this.setOnHost = setOnHost;
        this.platform = platform;
        this.configEnabled = props.isEnabled();
        this.maxBufferBytes = props.getMaxBufferBytes();
        this.compressibleTypes = new HashSet<>();
        for (String type : props.getMimeTypes()) {
            String t = type.trim().toLowerCase(Locale.ROOT);
            if (!t.isEmpty()) {
                this.compressibleTypes.add(t);
            }
        }
        this.configGzipEnabled = props.getGzip().isEnabled();
        this.gzipMinSize = props.getGzip().getMinSize();
        this.configBrotliEnabled = props.getBrotli().isEnabled();
        this.brotliMinSize = props.getBrotli().getMinSize();
        this.brotliQuality = props.getBrotli().getQuality();
        this.brotliParams = new Encoder.Parameters().setQuality(this.brotliQuality);
        // Probe the native library UNCONDITIONALLY — it is a capability check, not a toggle — so an operator can
        // turn Brotli ON at runtime (Platform settings) even when application.yml shipped it off. The probe is
        // safe (it catches Throwable and disables Brotli on failure, whereupon it falls back to gzip).
        this.brotliNativeAvailable = probeBrotli();
        if (configEnabled) {
            log.info("Response compression on (config default): gzip={}, brotli={} (native lib {}). "
                    + "Live values may be overridden on the Platform settings page.",
                    configGzipEnabled, configBrotliEnabled,
                    brotliNativeAvailable ? "loaded" : "unavailable — brotli will fall back to gzip");
        }
    }

    /**
     * Master switch, resolved LIVE: the platform override if set (Platform settings), else the config default.
     * Public so the Platform-settings form seeds its switches from the single source of truth (this filter),
     * and About reports the same value it acts on.
     */
    public boolean enabled() {
        Boolean override = setOnHost.test("runway.compression.enabled") ? null : platform.get().compressionEnabled();
        return override != null ? override : configEnabled;
    }

    /** Whether gzip is offered right now — the platform override if set, else the config default. */
    public boolean gzipOn() {
        Boolean override = setOnHost.test("runway.compression.gzip.enabled") ? null : platform.get().gzipEnabled();
        return override != null ? override : configGzipEnabled;
    }

    /** Whether Brotli is offered right now — the platform override if set, else the config default (it is only
     *  actually applied when {@link #brotliNativeAvailable()} too). */
    public boolean brotliOn() {
        Boolean override = setOnHost.test("runway.compression.brotli.enabled") ? null
                : platform.get().brotliEnabled();
        return override != null ? override : configBrotliEnabled;
    }

    /**
     * The response encodings in force <b>right now</b>, for the operator About page — e.g. {@code "brotli q5 +
     * gzip"}, {@code "gzip"}, {@code "off"}, or a note when Brotli is on but its native library did not load.
     * Reflects the live Platform-settings overrides, so About and the actual behaviour never disagree.
     */
    public String summary() {
        if (!enabled()) {
            return "off";
        }
        StringBuilder s = new StringBuilder();
        if (brotliOn() && brotliNativeAvailable) {
            s.append("brotli q").append(brotliQuality);
        }
        if (gzipOn()) {
            s.append(s.length() > 0 ? " + gzip" : "gzip");
        }
        if (s.length() == 0) {
            s.append("on (no encoding available)");
        }
        if (brotliOn() && !brotliNativeAvailable) {
            s.append(" (brotli native unavailable)");
        }
        return s.toString();
    }

    /** Loads the brotli4j native library once; on failure brotli is disabled (gzip still works). */
    private static boolean probeBrotli() {
        try {
            Brotli4jLoader.ensureAvailability();
            return true;
        } catch (Throwable unavailable) {
            log.warn("brotli4j native library not available; brotli compression disabled: {}",
                    unavailable.toString());
            return false;
        }
    }

    /**
     * Whether the brotli4j native library loaded — surfaced on the operator About page so it is clear whether
     * brotli is actually in effect on this build/platform (it falls back to gzip when {@code false}).
     */
    public boolean brotliNativeAvailable() {
        return brotliNativeAvailable;
    }

    private boolean brotliUsable(HttpServletRequest request) {
        return brotliOn() && brotliNativeAvailable && accepts(request, "br");
    }

    private boolean gzipUsable(HttpServletRequest request) {
        return gzipOn() && accepts(request, "gzip");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Never buffer a HEAD: it carries no body to compress, and BufferingResponseWrapper WITHHOLDS
        // Content-Length (a compressing filter recomputes it from the body) — so buffering a body-less HEAD would
        // strip the handler's correct Content-Length and emit `Content-Length: 0`. Let HEAD pass straight through.
        // Also skip when compression is off or the client accepts no encoding we offer.
        return "HEAD".equalsIgnoreCase(request.getMethod())
                || !enabled() || (!brotliUsable(request) && !gzipUsable(request));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        BufferingResponseWrapper wrapper = new BufferingResponseWrapper(response, maxBufferBytes);
        chain.doFilter(request, wrapper);
        wrapper.flushBuffer();
        if (wrapper.isOverflowed()) {
            return; // too large to buffer — already streamed to the client, uncompressed
        }
        if (wrapper.isErrorSent()) {
            return; // sendError: the container owns the response and renders the error page — nothing to encode
        }
        byte[] body = wrapper.capturedBytes();
        if (body.length == 0) {
            return; // no body (204/304/empty) — nothing to encode, headers already delegated
        }

        boolean alreadyEncoded = response.getHeader(HttpHeaders.CONTENT_ENCODING) != null;
        boolean compressible = !alreadyEncoded && isCompressible(response.getContentType());

        String encoding = null;
        byte[] encoded = null;
        if (compressible && brotliUsable(request) && body.length >= brotliMinSize) {
            encoding = "br";
            encoded = Encoder.compress(body, brotliParams);
        } else if (compressible && gzipUsable(request) && body.length >= gzipMinSize) {
            encoding = "gzip";
            encoded = gzip(body);
        }

        if (encoding == null) {
            response.setContentLength(body.length);
            writeBody(response, body);
            return;
        }
        response.setHeader(HttpHeaders.CONTENT_ENCODING, encoding);
        response.setContentLength(encoded.length);
        addVaryAcceptEncoding(response);
        writeBody(response, encoded);
    }

    /**
     * Writes the finished body to the client, swallowing the {@code IOException} a mid-write client disconnect
     * produces (navigated away, hit Stop, dropped connection). That is not a server error — the body was
     * produced fine on in-memory buffers above; there is simply no socket left. Logging it at DEBUG avoids a
     * noisy stack trace per aborted request.
     */
    private void writeBody(HttpServletResponse response, byte[] bytes) {
        try {
            response.getOutputStream().write(bytes);
        } catch (IOException disconnected) {
            if (log.isDebugEnabled()) {
                log.debug("Client disconnected before the {}-byte response was written; dropping: {}",
                        bytes.length, disconnected.toString());
            }
        }
    }

    private static byte[] gzip(byte[] body) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, body.length / 3));
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(body);
        }
        return out.toByteArray();
    }

    /** Whether {@code Accept-Encoding} offers {@code coding} without an explicit {@code q=0}. */
    private static boolean accepts(HttpServletRequest request, String coding) {
        String header = request.getHeader(HttpHeaders.ACCEPT_ENCODING);
        if (header == null) {
            return false;
        }
        for (String token : header.split(",")) {
            String t = token.trim();
            int semi = t.indexOf(';');
            String name = (semi >= 0 ? t.substring(0, semi) : t).trim();
            if (name.equalsIgnoreCase(coding)) {
                return !(semi >= 0 && t.substring(semi + 1).replaceAll("\\s+", "").startsWith("q=0"));
            }
        }
        return false;
    }

    private boolean isCompressible(String contentType) {
        if (contentType == null) {
            return false;
        }
        int semi = contentType.indexOf(';');
        String base = (semi > 0 ? contentType.substring(0, semi) : contentType).trim().toLowerCase(Locale.ROOT);
        return compressibleTypes.contains(base);
    }

    private static void addVaryAcceptEncoding(HttpServletResponse response) {
        String existing = response.getHeader(HttpHeaders.VARY);
        if (existing == null || existing.isBlank()) {
            response.setHeader(HttpHeaders.VARY, "Accept-Encoding");
        } else if (!existing.toLowerCase(Locale.ROOT).contains("accept-encoding")) {
            response.setHeader(HttpHeaders.VARY, existing + ", Accept-Encoding");
        }
    }
}
