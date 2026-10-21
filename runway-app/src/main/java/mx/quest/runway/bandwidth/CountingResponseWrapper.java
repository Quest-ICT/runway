package mx.quest.runway.bandwidth;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/**
 * Wraps a response so every byte written to the client is counted. Sitting outside compression on the filter
 * ladder, the count is the real on-the-wire response size. The wrapper adds no buffering — it delegates and
 * only tallies — so it is safe on the hot path.
 */
public final class CountingResponseWrapper extends HttpServletResponseWrapper {

    private long bytesWritten;
    private CountingOutputStream stream;
    private PrintWriter writer;

    public CountingResponseWrapper(HttpServletResponse response) {
        super(response);
    }

    /** The number of body bytes written to the client so far. */
    public long bytesWritten() {
        return bytesWritten;
    }

    @Override
    public ServletOutputStream getOutputStream() throws IOException {
        if (writer != null) {
            return super.getOutputStream();
        }
        if (stream == null) {
            stream = new CountingOutputStream(super.getOutputStream());
        }
        return stream;
    }

    @Override
    public PrintWriter getWriter() throws IOException {
        if (stream != null) {
            return super.getWriter();
        }
        if (writer == null) {
            writer = new PrintWriter(new java.io.OutputStreamWriter(
                    new CountingOutputStream(super.getOutputStream()), charset()), false);
        }
        return writer;
    }

    @Override
    public void flushBuffer() throws IOException {
        if (writer != null) {
            writer.flush();
        }
        super.flushBuffer();
    }

    private java.nio.charset.Charset charset() {
        String name = getCharacterEncoding();
        try {
            return name == null ? StandardCharsets.UTF_8 : java.nio.charset.Charset.forName(name);
        } catch (RuntimeException e) {
            return StandardCharsets.UTF_8;
        }
    }

    /** A pass-through {@link ServletOutputStream} that tallies every byte before delegating. */
    private final class CountingOutputStream extends ServletOutputStream {

        private final ServletOutputStream delegate;

        CountingOutputStream(ServletOutputStream delegate) {
            this.delegate = delegate;
        }

        @Override
        public void write(int b) throws IOException {
            delegate.write(b);
            bytesWritten++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            delegate.write(b, off, len);
            bytesWritten += len;
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setWriteListener(WriteListener listener) {
            delegate.setWriteListener(listener);
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
