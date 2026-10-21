package mx.quest.runway.head;

import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import mx.quest.runway.web.BufferingResponseWrapper;

/**
 * A {@link BufferingResponseWrapper} that also <b>holds back the validators</b> — {@code ETag} and
 * {@code Last-Modified} — until the head-injection filter knows whether the body it is about to send is the
 * healthy representation those validators describe.
 *
 * <p>Why: the page controller stamps the validators before the body leaves, but head contributors only run at
 * the response edge. When one of them throws, the page goes out without its markup (contained), and a validator that
 * names the healthy version would let a browser or CDN revalidate the broken copy into a {@code 304} for as
 * long as the content stays unchanged. The servlet API has no portable way to remove a header once set, so the
 * filter never lets them reach the real response until it has decided: {@link #releaseValidators()} on the
 * healthy path (and before the first byte of an overflowing body), {@link #dropValidators()} on the degraded one.
 *
 * <p>Every other header passes straight through, as in the parent. Not thread-safe (one response, one request).
 */
final class ValidatorHoldingResponse extends BufferingResponseWrapper {

    private final List<String[]> held = new ArrayList<>();
    private boolean released;

    ValidatorHoldingResponse(HttpServletResponse response, long maxBufferBytes, Runnable onOverflow) {
        super(response, maxBufferBytes, onOverflow);
    }

    private static boolean isValidator(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.equals("etag") || lower.equals("last-modified");
    }

    @Override
    public void setHeader(String name, String value) {
        if (!released && isValidator(name)) {
            held.removeIf(h -> h[0].equalsIgnoreCase(name));
            if (value != null) { // a null value removes the header, as the servlet container does
                held.add(new String[] {name, value, "set"});
            }
            return;
        }
        super.setHeader(name, value);
    }

    @Override
    public void addHeader(String name, String value) {
        if (!released && isValidator(name)) {
            if (value != null) {
                held.add(new String[] {name, value, "add"});
            }
            return;
        }
        super.addHeader(name, value);
    }

    @Override
    public void setDateHeader(String name, long date) {
        if (!released && isValidator(name)) {
            held.removeIf(h -> h[0].equalsIgnoreCase(name));
            held.add(new String[] {name, Long.toString(date), "date"});
            return;
        }
        super.setDateHeader(name, date);
    }

    @Override
    public void addDateHeader(String name, long date) {
        if (!released && isValidator(name)) {
            held.add(new String[] {name, Long.toString(date), "date"});
            return;
        }
        super.addDateHeader(name, date);
    }

    @Override
    public boolean containsHeader(String name) {
        if (!released && isValidator(name)) {
            return held.stream().anyMatch(h -> h[0].equalsIgnoreCase(name));
        }
        return super.containsHeader(name);
    }

    @Override
    public String getHeader(String name) {
        if (!released && isValidator(name)) {
            return held.stream().filter(h -> h[0].equalsIgnoreCase(name) && !"date".equals(h[2]))
                    .map(h -> h[1]).findFirst().orElse(null);
        }
        return super.getHeader(name);
    }

    @Override
    public Collection<String> getHeaders(String name) {
        if (!released && isValidator(name)) {
            return held.stream().filter(h -> h[0].equalsIgnoreCase(name) && !"date".equals(h[2]))
                    .map(h -> h[1]).toList();
        }
        return super.getHeaders(name);
    }

    /** Writes the held validators through to the real response; later ones pass straight through. Idempotent. */
    void releaseValidators() {
        if (released) {
            return;
        }
        released = true;
        HttpServletResponse real = (HttpServletResponse) getResponse();
        for (String[] header : held) {
            switch (header[2]) {
                case "set" -> real.setHeader(header[0], header[1]);
                case "add" -> real.addHeader(header[0], header[1]);
                default -> real.setDateHeader(header[0], Long.parseLong(header[1]));
            }
        }
        held.clear();
    }

    /** Discards the held validators; any set afterwards are discarded too. */
    void dropValidators() {
        held.clear();
    }
}
