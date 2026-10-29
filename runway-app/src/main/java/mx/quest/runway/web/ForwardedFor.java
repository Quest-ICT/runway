package mx.quest.runway.web;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * Which address in an {@code X-Forwarded-For} chain is the client. Pure functions.
 *
 * <p>Each proxy <em>appends</em> the address it received the request from, so the chain reads left to right from the
 * furthest hop to the nearest, and only the entries written by proxies Runway trusts are reliable: everything to their
 * left may have been sent by the client itself. The client is therefore found <b>from the right</b>: skip the entries
 * that are trusted proxies ({@code runway.proxy.trusted-cidrs}); the first one that is not is the address the nearest
 * trusted proxy saw, and that is the client. Taking the leftmost entry instead, as Spring's {@code ForwardedHeaderFilter}
 * does, lets any client choose its own address for rate limits, lockouts and logs whenever the proxy appends rather than
 * replaces.
 *
 * <p>An entry that is not an address literal where one is needed (an obfuscated {@code unknown}, a host name, a
 * garbled value) cannot name the client, so the request is attributed to the proxy that sent it: never resolve DNS,
 * never guess. Only the rightmost {@value #MAX_ENTRIES} entries are read; any further left were written by the client.
 */
public final class ForwardedFor {

    /** The header this class reads. */
    public static final String HEADER = "X-Forwarded-For";

    /** How many entries, counted from the right, are kept. */
    static final int MAX_ENTRIES = 32;

    /** The longest entry read as an address; anything longer is not one. */
    static final int MAX_ENTRY_LENGTH = 64;

    private ForwardedFor() {
    }

    /**
     * The chain as received: every {@code X-Forwarded-For} line in order, split on commas, trimmed, empty entries
     * dropped.
     *
     * @param headers the header's lines, or {@code null}
     * @return at most the rightmost {@value #MAX_ENTRIES} entries, and how many there were in all
     */
    public static Chain chain(Enumeration<String> headers) {
        List<String> all = new ArrayList<>();
        int total = 0;
        if (headers != null) {
            while (headers.hasMoreElements()) {
                String line = headers.nextElement();
                if (line == null) {
                    continue;
                }
                for (String part : line.split(",", -1)) {
                    String entry = part.strip();
                    if (entry.isEmpty()) {
                        continue;
                    }
                    total++;
                    all.add(entry.length() > MAX_ENTRY_LENGTH ? entry.substring(0, MAX_ENTRY_LENGTH) + "…" : entry);
                    if (all.size() > MAX_ENTRIES) {
                        all.removeFirst();
                    }
                }
            }
        }
        return new Chain(List.copyOf(all), total);
    }

    /**
     * The client address: the rightmost entry that is not a trusted proxy; the leftmost entry when every entry is one;
     * {@code peer} when the chain is empty or the deciding entry is not an address.
     *
     * @param chain   the received chain
     * @param trusted the trusted proxies
     * @param peer    the connection's own address (a trusted proxy, or this would not be called)
     */
    public static String client(Chain chain, CidrSet trusted, String peer) {
        List<String> entries = chain.entries();
        String address = null;
        for (int i = entries.size() - 1; i >= 0; i--) {
            address = address(entries.get(i));
            if (address == null) {
                return peer;
            }
            if (!trusted.contains(address)) {
                return address;
            }
        }
        return address == null ? peer : address;
    }

    /**
     * An entry's address without quotes, brackets or port, or {@code null} when it is not an address literal.
     * Accepts {@code 192.0.2.1}, {@code 192.0.2.1:8080}, {@code 2001:db8::1}, {@code [2001:db8::1]} and
     * {@code [2001:db8::1]:8080}, optionally in double quotes.
     */
    static String address(String entry) {
        if (entry == null) {
            return null;
        }
        String text = entry.strip();
        if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\"")) {
            text = text.substring(1, text.length() - 1).strip();
        }
        if (text.isEmpty() || text.length() > MAX_ENTRY_LENGTH || text.indexOf('%') >= 0) {
            return null;
        }
        if (text.startsWith("[")) {
            int close = text.indexOf(']');
            if (close < 0) {
                return null;
            }
            String rest = text.substring(close + 1);
            if (!rest.isEmpty() && !isPort(rest)) {
                return null;
            }
            text = text.substring(1, close);
            if (text.indexOf(':') < 0) {
                return null; // brackets hold IPv6 only
            }
        } else {
            int colon = text.indexOf(':');
            if (colon >= 0 && colon == text.lastIndexOf(':')) {
                // one colon: IPv4 with a port (IPv6 has at least two)
                if (!isPort(text.substring(colon))) {
                    return null;
                }
                text = text.substring(0, colon);
                if (text.indexOf('.') < 0) {
                    return null;
                }
            }
        }
        try {
            InetAddress.ofLiteral(text); // literal only: never resolves DNS
            return text;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean isPort(String colonAndDigits) {
        if (colonAndDigits.length() < 2 || colonAndDigits.length() > 6 || colonAndDigits.charAt(0) != ':') {
            return false;
        }
        for (int i = 1; i < colonAndDigits.length(); i++) {
            char c = colonAndDigits.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * A received chain.
     *
     * @param entries at most the rightmost {@value #MAX_ENTRIES} entries, each cut to {@value #MAX_ENTRY_LENGTH}
     *                characters
     * @param total   how many entries the request carried
     */
    public record Chain(List<String> entries, int total) {

        /** No header. */
        public static final Chain NONE = new Chain(List.of(), 0);

        /** The chain as one line, with {@code …, } in front when entries were dropped on the left. */
        public String text() {
            String joined = String.join(", ", entries);
            return total > entries.size() ? "…, " + joined : joined;
        }
    }
}
