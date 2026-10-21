package mx.quest.runway.web;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Buffers the response body so an egress filter can examine and rewrite it once, with a hard cap
 * that bounds memory: a filter must not hold an arbitrarily large download in RAM just because it
 * <em>might</em> want to rewrite an HTML page.
 *
 * <p><b>Capture vs. overflow.</b> While the buffered size stays at or below {@code maxBufferBytes},
 * every byte is captured and nothing reaches the client; {@link #capturedBytes()} then returns the
 * whole body for rewriting. The moment a write pushes the buffer past the cap the wrapper
 * <b>overflows</b>: the bytes buffered so far are flushed to the real output stream and every
 * subsequent write passes straight through. A caller <b>must</b> check {@link #isOverflowed()} and,
 * when {@code true}, leave the (already-streaming) response alone — {@link #capturedBytes()} is no
 * longer meaningful.
 *
 * <p><b>Content-Length is withheld.</b> A rewriting filter changes the body length, so this wrapper
 * never propagates a downstream {@code Content-Length} (via {@code setContentLength*} or a
 * {@code Content-Length} header) to the real response; the filter sets the final length itself after
 * rewriting. Every other header, status, and content-type operation passes through unchanged, so the
 * real response still carries the correct metadata even on overflow.
 *
 * <p><b>Callers MUST bypass {@code HEAD}.</b> Because Content-Length is withheld and a rewriting filter
 * re-stamps it from {@link #capturedBytes()}, wrapping a <em>body-less</em> response (a {@code HEAD}, where a
 * handler like Spring's {@code ResourceHttpRequestHandler} writes no body) makes the filter re-stamp
 * {@code Content-Length: 0}, clobbering the handler's correct length. There is nothing to rewrite on a HEAD
 * anyway, so every filter that constructs this wrapper (currently {@link CompressionFilter} and
 * {@link mx.quest.runway.head.HeadInjectionFilter}) short-circuits {@code HEAD} before wrapping.
 *
 * <p><b>An error sent downstream ends the filter's say over the body.</b> {@code sendError} passes straight through
 * (the status and the container's error handling must happen) and discards anything captured so far, as the servlet
 * contract does for the real buffer. From then on the container owns the response — it renders the error page in its
 * own dispatch — so a filter must check {@link #isErrorSent()} and write nothing: writing the (empty) captured body
 * into a response the container has closed is what made every {@code /__assets} 404 log a Jetty
 * {@code EofException} stack trace.
 *
 * <p>Not thread-safe: one instance wraps one response for one request, as servlet wrappers do.
 */
public class BufferingResponseWrapper extends HttpServletResponseWrapper {

    private final long maxBufferBytes;
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream(8192);

    private ServletOutputStream outputStream;
    private PrintWriter writer;
    private boolean overflowed;
    private boolean errorSent;
    /** Overflow hook (see the 3-arg constructor), or {@code null}. */
    private final Runnable onOverflow;

    /**
     * @param response       the real response to wrap (never {@code null})
     * @param maxBufferBytes the cap, in bytes, beyond which the wrapper overflows to the real stream
     */
    public BufferingResponseWrapper(HttpServletResponse response, long maxBufferBytes) {
        this(response, maxBufferBytes, null);
    }

    /**
     * @param onOverflow run once, at the moment the cap is exceeded and <b>before</b> the first byte reaches the
     *                   real response (still uncommitted) — the caller's chance to adjust headers for a body it will
     *                   no longer be able to rewrite; {@code null} for none
     */
    public BufferingResponseWrapper(HttpServletResponse response, long maxBufferBytes, Runnable onOverflow) {
        super(response);
        this.maxBufferBytes = maxBufferBytes;
        this.onOverflow = onOverflow;
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        if (writer != null) {
            throw new IllegalStateException("getWriter() has already been called on this response");
        }
        if (outputStream == null) {
            outputStream = new CapturingStream();
        }
        return outputStream;
    }

    @Override
    public PrintWriter getWriter() throws IOException {
        if (writer == null) {
            if (outputStream != null) {
                throw new IllegalStateException("getOutputStream() has already been called on this response");
            }
            writer = new PrintWriter(new OutputStreamWriter(getOutputStream(), charset()));
        }
        return writer;
    }

    @Override
    public void flushBuffer() throws IOException {
        if (writer != null) {
            writer.flush(); // push pending characters into the byte buffer (or the real stream, if overflowed)
        }
        if (overflowed) {
            getResponse().flushBuffer();
        }
        // While still capturing, deliberately do NOT commit the real response.
    }

    @Override
    public void sendError(int sc) throws IOException {
        errorSent();
        super.sendError(sc);
    }

    @Override
    public void sendError(int sc, String msg) throws IOException {
        errorSent();
        super.sendError(sc, msg);
    }

    private void errorSent() {
        errorSent = true;
        if (writer != null) {
            writer.flush(); // drain pending characters so the reset below leaves nothing behind
        }
        if (!overflowed) {
            buffer.reset(); // sendError discards the unsent body; so does this buffer
        }
    }

    /**
     * Whether the downstream handler called {@code sendError}. If {@code true}, the container owns the response and
     * renders its error page; the caller must not write a body (or set headers that describe one).
     */
    public boolean isErrorSent() {
        return errorSent;
    }

    /** Whether the cap was exceeded; if {@code true} the body has already streamed and must be left alone. */
    public boolean isOverflowed() {
        return overflowed;
    }

    /**
     * The fully captured body. Meaningful only when {@link #isOverflowed()} is {@code false}. Flushes
     * a pending writer first, so any buffered characters are encoded into the returned bytes.
     *
     * @return the captured bytes (a fresh array; empty when nothing was written)
     */
    public byte[] capturedBytes() {
        if (writer != null) {
            writer.flush();
        }
        return buffer.toByteArray();
    }

    private Charset charset() {
        String name = getCharacterEncoding();
        if (name == null || name.isBlank()) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(name);
        } catch (RuntimeException ex) {
            return StandardCharsets.UTF_8;
        }
    }

    // --- Content-Length is withheld; the rewriting filter sets the final length. ---

    @Override
    public void setContentLength(int len) {
        // withheld
    }

    @Override
    public void setContentLengthLong(long len) {
        // withheld
    }

    @Override
    public void setHeader(String name, String value) {
        if (!isContentLength(name)) {
            super.setHeader(name, value);
        }
    }

    @Override
    public void addHeader(String name, String value) {
        if (!isContentLength(name)) {
            super.addHeader(name, value);
        }
    }

    @Override
    public void setIntHeader(String name, int value) {
        if (!isContentLength(name)) {
            super.setIntHeader(name, value);
        }
    }

    @Override
    public void addIntHeader(String name, int value) {
        if (!isContentLength(name)) {
            super.addIntHeader(name, value);
        }
    }

    private static boolean isContentLength(String name) {
        return "Content-Length".equalsIgnoreCase(name);
    }

    /** Captures written bytes until the cap is exceeded, then overflows to the real output stream. */
    private final class CapturingStream extends ServletOutputStream {

        private ServletOutputStream real;

        @Override
        public void write(int b) throws IOException {
            if (overflowed) {
                real.write(b);
                return;
            }
            buffer.write(b);
            if (buffer.size() > maxBufferBytes) {
                overflow();
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (overflowed) {
                real.write(b, off, len);
                return;
            }
            buffer.write(b, off, len);
            if (buffer.size() > maxBufferBytes) {
                overflow();
            }
        }

        private void overflow() throws IOException {
            overflowed = true;
            if (onOverflow != null) {
                onOverflow.run();
            }
            real = getResponse().getOutputStream();
            buffer.writeTo(real);
            buffer.reset();
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setWriteListener(WriteListener listener) {
            // blocking I/O only — async writes are not used on the render path
        }
    }
}
