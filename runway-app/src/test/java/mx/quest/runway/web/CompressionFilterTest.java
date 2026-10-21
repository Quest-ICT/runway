package mx.quest.runway.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aayushatharva.brotli4j.decoder.Decoder;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import mx.quest.runway.platform.PlatformSettings;
import mx.quest.runway.platform.PlatformSettingsStore;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The single compression filter: negotiates brotli &gt; gzip &gt; identity, honours the mime-type + min-size +
 * already-encoded rules, and round-trips exactly. Brotli assertions are skipped (not failed) if the brotli4j
 * native library did not load on this platform — that path is exercised live on the deployment host.
 */
class CompressionFilterTest {

    private static final byte[] HTML = "<html>".concat("x".repeat(4000)).concat("</html>")
            .getBytes(StandardCharsets.UTF_8);

    private static CompressionFilter filter() {
        return new CompressionFilter(new RunwayCompressionProperties(), emptyPlatform());
    }

    /** A platform store with no overrides set — so the filter falls back to the {@code application.yml} defaults. */
    private static PlatformSettingsStore emptyPlatform() {
        PlatformSettingsStore p = mock(PlatformSettingsStore.class);
        when(p.get()).thenReturn(PlatformSettings.EMPTY);
        return p;
    }

    /** A platform store returning a fixed override, for the live-toggle tests. */
    private static PlatformSettingsStore platformWith(PlatformSettings settings) {
        PlatformSettingsStore p = mock(PlatformSettingsStore.class);
        when(p.get()).thenReturn(settings);
        return p;
    }

    /** Runs the filter over a chain that produces {@code body} with {@code contentType}. */
    private static MockHttpServletResponse run(CompressionFilter filter, String acceptEncoding,
                                               String contentType, byte[] body, String preEncoding) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/x");
        if (acceptEncoding != null) {
            req.addHeader("Accept-Encoding", acceptEncoding);
        }
        MockHttpServletResponse resp = new MockHttpServletResponse();
        HttpServlet producer = new HttpServlet() {
            @Override
            protected void service(HttpServletRequest rq, HttpServletResponse rs) throws IOException {
                rs.setContentType(contentType);
                if (preEncoding != null) {
                    rs.setHeader("Content-Encoding", preEncoding);
                }
                rs.getOutputStream().write(body);
            }
        };
        filter.doFilter(req, resp, new MockFilterChain(producer));
        return resp;
    }

    @Test
    void gzipRoundTrips() throws Exception {
        MockHttpServletResponse resp = run(filter(), "gzip", "text/html;charset=UTF-8", HTML, null);
        assertThat(resp.getHeader("Content-Encoding")).isEqualTo("gzip");
        assertThat(resp.getHeader("Vary")).containsIgnoringCase("Accept-Encoding");
        byte[] out = new GZIPInputStream(new ByteArrayInputStream(resp.getContentAsByteArray())).readAllBytes();
        assertThat(out).isEqualTo(HTML);
        assertThat(resp.getContentAsByteArray().length).isLessThan(HTML.length); // actually smaller
    }

    @Test
    void brotliIsPreferredAndRoundTrips() throws Exception {
        CompressionFilter f = filter();
        assumeTrue(f.brotliNativeAvailable(), "brotli4j native library not loaded on this platform");
        MockHttpServletResponse resp = run(f, "br, gzip", "text/html", HTML, null);
        assertThat(resp.getHeader("Content-Encoding")).isEqualTo("br"); // brotli beats gzip
        byte[] out = Decoder.decompress(resp.getContentAsByteArray()).getDecompressedData();
        assertThat(out).isEqualTo(HTML);
    }

    @Test
    void belowMinSizeIsNotCompressed() throws Exception {
        byte[] tiny = "<html>hi</html>".getBytes(StandardCharsets.UTF_8); // < 1024
        MockHttpServletResponse resp = run(filter(), "gzip", "text/html", tiny, null);
        assertThat(resp.getHeader("Content-Encoding")).isNull();
        assertThat(resp.getContentAsByteArray()).isEqualTo(tiny);
    }

    @Test
    void nonCompressibleTypeIsNotCompressed() throws Exception {
        byte[] png = new byte[4000];
        MockHttpServletResponse resp = run(filter(), "gzip", "image/png", png, null);
        assertThat(resp.getHeader("Content-Encoding")).isNull();
        assertThat(resp.getContentAsByteArray()).isEqualTo(png);
    }

    @Test
    void noAcceptEncodingPassesThroughUntouched() throws Exception {
        MockHttpServletResponse resp = run(filter(), null, "text/html", HTML, null);
        assertThat(resp.getHeader("Content-Encoding")).isNull();
        assertThat(resp.getContentAsByteArray()).isEqualTo(HTML);
    }

    @Test
    void anAlreadyEncodedResponseIsNotDoubleCompressed() throws Exception {
        // The chain already set Content-Encoding: the filter must leave the body byte-for-byte.
        MockHttpServletResponse resp = run(filter(), "gzip", "text/html", HTML, "gzip");
        assertThat(resp.getHeader("Content-Encoding")).isEqualTo("gzip");
        assertThat(resp.getContentAsByteArray()).isEqualTo(HTML); // unchanged — not re-compressed
    }

    @Test
    void headRequestIsNotBufferedSoContentLengthSurvives() throws Exception {
        // A HEAD carries no body; the handler sets Content-Length for what a GET would return. The filter must NOT
        // buffer it — BufferingResponseWrapper withholds Content-Length, so buffering a body-less HEAD would emit
        // `Content-Length: 0`. Regression for `HEAD /__assets/captcha-v3.js` returning 0 while GET returns the body.
        MockHttpServletRequest req = new MockHttpServletRequest("HEAD", "/__assets/captcha-v3.js");
        req.addHeader("Accept-Encoding", "br, gzip");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        HttpServlet producer = new HttpServlet() {
            @Override
            protected void service(HttpServletRequest rq, HttpServletResponse rs) {
                rs.setContentType("text/javascript");
                rs.setContentLength(2173); // the length a GET would return; a HEAD writes no body
            }
        };
        filter().doFilter(req, resp, new MockFilterChain(producer));
        assertThat(resp.getContentLength()).isEqualTo(2173); // the handler's value survives (not 0)
        assertThat(resp.getHeader("Content-Encoding")).isNull(); // nothing is compressed on a HEAD
        assertThat(resp.getContentAsByteArray()).isEmpty();
    }

    @Test
    void disabledMasterSwitchPassesThrough() throws Exception {
        RunwayCompressionProperties props = new RunwayCompressionProperties();
        props.setEnabled(false);
        MockHttpServletResponse resp = run(new CompressionFilter(props, emptyPlatform()),
                "br, gzip", "text/html", HTML, null);
        assertThat(resp.getHeader("Content-Encoding")).isNull();
        assertThat(resp.getContentAsByteArray()).isEqualTo(HTML);
    }

    @Test
    void aLivePlatformOverrideCanDisableCompression() throws Exception {
        // application.yml defaults have compression ON; a platform override turns it OFF, read live per request.
        PlatformSettings off = new PlatformSettings(null, null, null, false, null, null);
        CompressionFilter f = new CompressionFilter(new RunwayCompressionProperties(), platformWith(off));
        MockHttpServletResponse resp = run(f, "br, gzip", "text/html", HTML, null);
        assertThat(resp.getHeader("Content-Encoding")).isNull(); // passed through despite br/gzip being accepted
        assertThat(resp.getContentAsByteArray()).isEqualTo(HTML);
    }

    @Test
    void aLivePlatformOverrideCanTurnBrotliOffAndForceGzip() throws Exception {
        // Compression on, brotli OFF, gzip on — even though the client accepts br, gzip must be chosen.
        PlatformSettings gzipOnly = new PlatformSettings(null, null, null, true, false, true);
        CompressionFilter f = new CompressionFilter(new RunwayCompressionProperties(), platformWith(gzipOnly));
        MockHttpServletResponse resp = run(f, "br, gzip", "text/html", HTML, null);
        assertThat(resp.getHeader("Content-Encoding")).isEqualTo("gzip");
        byte[] out = new GZIPInputStream(new ByteArrayInputStream(resp.getContentAsByteArray())).readAllBytes();
        assertThat(out).isEqualTo(HTML);
    }
}
