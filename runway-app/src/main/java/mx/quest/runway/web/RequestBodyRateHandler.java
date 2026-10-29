package mx.quest.runway.web;

import java.nio.ByteBuffer;
import org.eclipse.jetty.http.HttpException;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;

/**
 * Refuses a request body that arrives more slowly than a minimum rate, on <b>every</b> read of the body: the
 * servlet input stream, form parameters and multipart parts alike.
 *
 * <p><b>Why a handler of our own.</b> Jetty 12's own minimum request data rate is checked only where a servlet reads
 * the body through {@code getInputStream()}. Form parameters ({@code getParameter}, which Spring Security's login
 * reads) and multipart parts are parsed through Jetty's core request instead, and that path never checks it, so a
 * client could trickle a form for as long as it kept sending something inside each idle interval, holding a request
 * thread all the while. Measured on 1.1.0: form and multipart posts dripped at 20 bytes a second were held until the
 * 30-second idle timeout. This handler sits under the server and wraps the core request, so every body read passes
 * through it.
 *
 * <p><b>What is measured.</b> Only the time spent <em>waiting for the client</em>: from a read that found no data to
 * the next one that did. Time the application spends before or between reads does not count, so a server that is
 * slow to read is never blamed. The check keeps a balance: every byte received earns one, every second of waiting
 * costs the rate's worth, and the balance is capped at {@link #CREDIT_SECONDS} of the rate, so a body sent fast at
 * first cannot buy an hour of dripping later. It runs when data arrives: once the waiting time reaches one second and
 * the balance is negative, the read fails with {@code 408 Request Timeout} and so does every later read. A client
 * pausing between packets is therefore cut when its next packet arrives, at most one idle timeout later. A chunk that completes the body is never refused: once the client has finished, no thread
 * is waiting on it any more, and a small form whose only packet arrived late (a network stall) is not punished for
 * it; the body counts as complete at its last chunk or, with a {@code Content-Length}, at its last byte. A client that
 * sends nothing at all is left to the connection idle timeout.
 *
 * <p>The refusal is answered {@code 408} however the application handled the failed read: a form or multipart parser
 * would report it as {@code 400}, as if the body were malformed, and code reading the stream itself could leave an
 * empty {@code 200} (see {@link RefusalStatusResponse}).
 */
final class RequestBodyRateHandler extends Handler.Wrapper {

    /** Waiting shorter than this is never judged: a single network stall must not look like a slow client. */
    static final long GRACE_NANOS = 1_000_000_000L;

    /**
     * The most waiting a body may have banked by arriving fast earlier: 30 seconds' worth of the rate, the connection
     * idle timeout's default. Without a cap, a client that sent most of a large body at line speed and then dripped
     * the rest could hold the thread for the body's size divided by the rate (16 MiB at 4 KiB/s: over an hour).
     */
    static final long CREDIT_SECONDS = 30;

    private final long bytesPerSecond;
    private final long creditSeconds;

    /** @param bytesPerSecond the slowest a body may arrive; must be positive (0 means "do not install") */
    RequestBodyRateHandler(long bytesPerSecond) {
        this(bytesPerSecond, CREDIT_SECONDS);
    }

    /** As {@link #RequestBodyRateHandler(long)}, with the credit cap in seconds (tests use a short one). */
    RequestBodyRateHandler(long bytesPerSecond, long creditSeconds) {
        if (bytesPerSecond <= 0 || creditSeconds <= 0) {
            throw new IllegalArgumentException("rate and credit must be positive: " + bytesPerSecond + ", "
                    + creditSeconds);
        }
        this.bytesPerSecond = bytesPerSecond;
        this.creditSeconds = creditSeconds;
    }

    long bytesPerSecond() {
        return bytesPerSecond;
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception {
        RateCheckedRequest checked = new RateCheckedRequest(request, bytesPerSecond, creditSeconds);
        return super.handle(checked, new RefusalStatusResponse(checked, response), callback);
    }

    /**
     * Keeps the refusal's status. A form or multipart parser reports any failed read as {@code 400 Bad Request}
     * ("unable to parse form content"), which would tell the client its body was malformed when it was only slow; and
     * code that reads the stream itself lets the failure escape as an {@code IOException}, which Jetty handles like a
     * client that went away, completing the response with the status it already had ({@code 200}, empty). Once this
     * request's body was refused, a client error status set on the response becomes {@code 408}, and so does whatever
     * status the response is committed with.
     */
    private static final class RefusalStatusResponse extends Response.Wrapper {

        private final RateCheckedRequest request;

        RefusalStatusResponse(RateCheckedRequest request, Response wrapped) {
            super(request, wrapped);
            this.request = request;
        }

        @Override
        public void write(boolean last, ByteBuffer content, Callback callback) {
            if (request.refused != null && !isCommitted() && getStatus() != HttpStatus.REQUEST_TIMEOUT_408) {
                super.setStatus(HttpStatus.REQUEST_TIMEOUT_408);
            }
            super.write(last, content, callback);
        }

        @Override
        public void setStatus(int code) {
            super.setStatus(request.refused != null && code >= 400 && code != HttpStatus.REQUEST_TIMEOUT_408
                    && code < 500 ? HttpStatus.REQUEST_TIMEOUT_408 : code);
        }
    }

    /** The request whose body reads are measured. Reads of one request are serial, as Jetty's demand model makes them. */
    private static final class RateCheckedRequest extends Request.Wrapper {

        private final long bytesPerSecond;
        private final long creditCap;
        private long received;
        private long waitedNanos;
        private long waitingSince = -1;
        /** Bytes received minus the rate times the time waited, capped at {@link #creditCap}; negative = too slow. */
        private long credit;
        private Content.Chunk refused;

        RateCheckedRequest(Request wrapped, long bytesPerSecond, long creditSeconds) {
            super(wrapped);
            this.bytesPerSecond = bytesPerSecond;
            this.creditCap = bytesPerSecond * creditSeconds;
        }

        @Override
        public Content.Chunk read() {
            if (refused != null) {
                return refused;
            }
            Content.Chunk chunk = super.read();
            long now = System.nanoTime();
            if (chunk == null) {
                if (waitingSince < 0) {
                    waitingSince = now;
                }
                return null;
            }
            long waited = 0;
            if (waitingSince >= 0) {
                waited = now - waitingSince;
                waitedNanos += waited;
                waitingSince = -1;
            }
            if (Content.Chunk.isFailure(chunk)) {
                return chunk;
            }
            int bytes = chunk.remaining();
            received += bytes;
            // Each byte earns credit and each second of waiting costs the rate's worth; earning is capped, so a body
            // sent fast at first cannot bank more than CREDIT_SECONDS of waiting for a drip later.
            credit = Math.min(creditCap, credit + bytes - bytesPerSecond * (waited / 1_000_000) / 1000);
            boolean complete = chunk.isLast() || (getLength() >= 0 && received >= getLength());
            if (!complete && waitedNanos >= GRACE_NANOS && credit < 0) {
                chunk.release();
                refused = Content.Chunk.from(new HttpException.RuntimeException(HttpStatus.REQUEST_TIMEOUT_408,
                        "request body slower than " + bytesPerSecond + " bytes/s"), true);
                return refused;
            }
            return chunk;
        }
    }

}
