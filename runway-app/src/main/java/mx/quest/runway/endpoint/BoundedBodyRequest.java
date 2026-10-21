package mx.quest.runway.endpoint;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import mx.quest.runway.endpoint.EndpointDispatchFilter.BodyTooLargeException;

/**
 * A request whose body stream refuses to deliver more than {@code cap} bytes: whoever reads it — a message
 * converter, Spring's form-content parser, the route dispatcher — gets a {@link BodyTooLargeException} (an
 * {@code IOException}) at byte {@code cap + 1} instead of the rest of the body, so nothing past the cap is ever
 * buffered. It does not read anything itself, and it leaves the parameter methods to the container.
 *
 * <p>Used where a body may be read before anyone is authenticated and its length is not known in advance (a chunked
 * body); a declared {@code Content-Length} over the cap is refused by the caller before this wrapper is made. Whether
 * the cap was hit is remembered ({@link #exceeded()}), so the caller can answer {@code 413} for an exception that
 * surfaced through someone else's code.
 *
 * <p>Not thread-safe; a request belongs to one thread.
 */
final class BoundedBodyRequest extends HttpServletRequestWrapper {

    private final long cap;
    private BoundedStream stream;
    private boolean exceeded;

    /**
     * @param request the request to bound
     * @param cap     the most body bytes any reader may take ({@code >= 0})
     */
    BoundedBodyRequest(HttpServletRequest request, long cap) {
        super(request);
        this.cap = Math.max(0, cap);
    }

    /** Whether a reader tried to read past the cap. */
    boolean exceeded() {
        return exceeded;
    }

    @Override
    public ServletInputStream getInputStream() throws IOException {
        if (stream == null) {
            stream = new BoundedStream(super.getInputStream());
        }
        return stream;
    }

    @Override
    public BufferedReader getReader() throws IOException {
        return new BufferedReader(new InputStreamReader(getInputStream(), EndpointDispatchFilter.charsetOf(this)));
    }

    /** Counts what passes through and throws once the count would exceed the cap. */
    private final class BoundedStream extends ServletInputStream {
        private final ServletInputStream in;
        private long total;

        BoundedStream(ServletInputStream in) {
            this.in = in;
        }

        private int count(int read) throws BodyTooLargeException {
            if (read > 0) {
                total += read;
                if (total > cap) {
                    exceeded = true;
                    throw new BodyTooLargeException();
                }
            }
            return read;
        }

        @Override
        public int read() throws IOException {
            if (exceeded) {
                throw new BodyTooLargeException();
            }
            int b = in.read();
            if (b >= 0) {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int off, int len) throws IOException {
            if (exceeded) {
                throw new BodyTooLargeException();
            }
            // Never ask for more than one byte past the cap: the overflow is detected without buffering further.
            long room = cap - total + 1;
            return count(in.read(buffer, off, (int) Math.min(len, Math.max(1, room))));
        }

        @Override
        public boolean isFinished() {
            return in.isFinished();
        }

        @Override
        public boolean isReady() {
            return in.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            in.setReadListener(listener);
        }
    }
}
