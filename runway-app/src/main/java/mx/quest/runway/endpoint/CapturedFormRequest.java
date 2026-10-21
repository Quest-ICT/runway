package mx.quest.runway.endpoint;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mx.quest.runway.endpoint.EndpointDispatchFilter.MalformedParametersException;

/**
 * A plugin route's {@code application/x-www-form-urlencoded} (or {@code multipart/form-data}) request whose body was
 * read <b>once</b>, into memory and under the endpoint body cap, by {@link RouteFormCaptureFilter} — before anything
 * could consume it.
 *
 * <h2>Why it exists</h2>
 * The container parses a form body the first time anyone calls {@code getParameter()}, and parsing it
 * <em>consumes</em> the input stream. Spring Security's {@code CsrfFilter} does exactly that on a CSRF-checked
 * {@code POST} when the token is not in the {@code X-CSRF-TOKEN} header, so the route dispatcher used to find an
 * empty stream and hand the plugin an empty {@code RouteRequest.form()} (and an empty {@code body()}). This wrapper
 * answers both readers from the same captured bytes:
 * <ul>
 *   <li>{@link #getInputStream()} / {@link #getReader()} replay the body (one stream per request, as the servlet
 *       contract has it);</li>
 *   <li>the parameter methods present the servlet-spec merged view — query-string fields first, then body fields —
 *       so the CSRF token is found wherever the client put it;</li>
 *   <li>{@link #bodyFields()} is the body's fields <b>alone</b>, parsed once: the dispatcher builds {@code form()}
 *       from it, so a query-string field can never leak into {@code form()} (the two stay apart), whatever names
 *       the two share.</li>
 * </ul>
 * The parameter view is tolerant: a half (query or body) that is malformed or carries more than
 * {@link EndpointDispatchFilter#MAX_PARAMETERS} fields contributes nothing to it (so a token in it is simply not
 * found, and the CSRF check refuses). {@link #bodyFields()} is strict, and the dispatcher answers {@code 400}.
 *
 * <h2>A multipart capture</h2>
 * The bytes are kept raw: {@link #bodyFields()} is empty, the parameter view holds the query string alone, and
 * {@link #getParts()} is empty (Spring's {@code MultipartFilter} skips a captured request, see
 * {@link RouteFormCaptureFilter#isCaptured}). The route gets the whole body in {@code RouteRequest.body()} and parses
 * it itself; a {@code _csrf} part is therefore never read, and the CSRF token travels in the {@code X-CSRF-TOKEN}
 * header.
 *
 * <p>Not thread-safe; a request belongs to one thread.
 */
final class CapturedFormRequest extends HttpServletRequestWrapper {

    private final byte[] body;
    private final Charset charset;
    /** {@code true} for an urlencoded form body (parsed into fields), {@code false} for a raw multipart capture. */
    private final boolean formEncoded;
    private ServletInputStream stream;
    private Map<String, List<String>> bodyFields;
    private MalformedParametersException bodyMalformed;
    private Map<String, String[]> parameters;

    /**
     * @param request the request whose body has just been read in full
     * @param body    exactly the bytes read (never {@code null})
     * @param charset the charset the body is decoded with ({@link EndpointDispatchFilter#charsetOf})
     */
    CapturedFormRequest(HttpServletRequest request, byte[] body, Charset charset) {
        this(request, body, charset, true);
    }

    /**
     * @param formEncoded {@code true} for an {@code application/x-www-form-urlencoded} body, {@code false} for a raw
     *                    ({@code multipart/form-data}) capture whose fields are not parsed
     */
    CapturedFormRequest(HttpServletRequest request, byte[] body, Charset charset, boolean formEncoded) {
        super(request);
        this.body = body;
        this.charset = charset;
        this.formEncoded = formEncoded;
    }

    /** The captured body bytes, unchanged; the caller must not modify the array. */
    byte[] body() {
        return body;
    }

    /**
     * The body's fields alone, in order with repeated names kept — parsed once and remembered.
     *
     * @throws MalformedParametersException on a malformed escape or more than
     *                                      {@link EndpointDispatchFilter#MAX_PARAMETERS} fields
     */
    Map<String, List<String>> bodyFields() throws MalformedParametersException {
        if (!formEncoded) {
            return Map.of(); // a raw (multipart) capture: the route parses its own body
        }
        if (bodyFields == null && bodyMalformed == null) {
            try {
                bodyFields = EndpointDispatchFilter.parse(new String(body, charset), charset);
            } catch (MalformedParametersException malformed) {
                bodyMalformed = malformed;
            }
        }
        if (bodyMalformed != null) {
            throw bodyMalformed;
        }
        return bodyFields;
    }

    @Override
    public ServletInputStream getInputStream() {
        if (stream == null) {
            stream = new ReplayStream(body);
        }
        return stream;
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), charset));
    }

    /** A raw capture is never parsed into parts here; the route reads the bytes. */
    @Override
    public java.util.Collection<jakarta.servlet.http.Part> getParts() throws java.io.IOException,
            jakarta.servlet.ServletException {
        return formEncoded ? super.getParts() : List.of();
    }

    @Override
    public jakarta.servlet.http.Part getPart(String name) throws java.io.IOException,
            jakarta.servlet.ServletException {
        return formEncoded ? super.getPart(name) : null;
    }

    @Override
    public String getParameter(String name) {
        String[] values = parameters().get(name);
        return values == null ? null : values[0];
    }

    @Override
    public Map<String, String[]> getParameterMap() {
        return parameters();
    }

    @Override
    public Enumeration<String> getParameterNames() {
        return Collections.enumeration(parameters().keySet());
    }

    @Override
    public String[] getParameterValues(String name) {
        String[] values = parameters().get(name);
        return values == null ? null : values.clone();
    }

    /** The servlet-spec merged view: query-string fields (UTF-8, like every URI here) first, then body fields. */
    private Map<String, String[]> parameters() {
        if (parameters == null) {
            Map<String, List<String>> merged = new LinkedHashMap<>();
            try {
                EndpointDispatchFilter.parse(getQueryString(), StandardCharsets.UTF_8)
                        .forEach((k, v) -> merged.computeIfAbsent(k, n -> new ArrayList<>()).addAll(v));
            } catch (MalformedParametersException malformed) {
                // tolerant view: a malformed query contributes nothing
            }
            try {
                bodyFields().forEach((k, v) -> merged.computeIfAbsent(k, n -> new ArrayList<>()).addAll(v));
            } catch (MalformedParametersException malformed) {
                // tolerant view: a malformed body contributes nothing (the dispatcher answers it 400)
            }
            Map<String, String[]> view = new LinkedHashMap<>();
            merged.forEach((k, v) -> view.put(k, v.toArray(String[]::new)));
            parameters = Collections.unmodifiableMap(view);
        }
        return parameters;
    }

    /** A blocking replay of the captured bytes. */
    private static final class ReplayStream extends ServletInputStream {
        private final ByteArrayInputStream in;

        ReplayStream(byte[] body) {
            this.in = new ByteArrayInputStream(body);
        }

        @Override
        public int read() {
            return in.read();
        }

        @Override
        public int read(byte[] b, int off, int len) {
            return in.read(b, off, len);
        }

        @Override
        public boolean isFinished() {
            return in.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
            throw new UnsupportedOperationException("a captured form body is replayed blocking only");
        }
    }
}
