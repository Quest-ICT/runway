package mx.quest.runway.web;

import java.math.BigInteger;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * An immutable set of IPv4/IPv6 address prefixes (CIDR blocks) with O(n) membership testing — the backing
 * structure for the trusted-proxy set and (later) the abuse allowlist. Parsing is DNS-free (it uses
 * {@link InetAddress#ofLiteral}, never name resolution), tolerant of a trailing IPv6 zone id, and treats a
 * bare address as a host prefix ({@code /32} for IPv4, {@code /128} for IPv6).
 *
 * <p>Two parse entry points: {@link #of(List)} is lenient (malformed entries are dropped), while
 * {@link #parse(List)} returns both the set and the list of <b>rejected</b> entries so a caller can warn the
 * operator rather than silently shrinking the set. A family mismatch never matches (an IPv4 prefix never
 * contains an IPv6 address and vice versa), and an empty set contains nothing.
 */
public final class CidrSet {

    private record Prefix(byte[] network, int bits) {
    }

    /** The result of {@link #parse(List)}: the membership set plus any entries that could not be parsed. */
    public record Parsed(CidrSet set, List<String> rejected) {
    }

    /** The bits an IPv4-mapped IPv6 address ({@code ::ffff:a.b.c.d}) has in front of its IPv4 part. */
    private static final int MAPPED_BITS = 96;

    private final List<Prefix> prefixes;

    private CidrSet(List<Prefix> prefixes) {
        this.prefixes = prefixes;
    }

    /** Lenient parse: malformed entries are silently dropped. */
    public static CidrSet of(List<String> entries) {
        return parse(entries).set();
    }

    /** Parse, returning the set and the rejected entries (for an operator-visible warning). */
    public static Parsed parse(List<String> entries) {
        List<Prefix> prefixes = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        if (entries != null) {
            for (String entry : entries) {
                Prefix prefix = tryParse(entry);
                if (prefix == null) {
                    rejected.add(entry);
                } else {
                    prefixes.add(prefix);
                }
            }
        }
        return new Parsed(new CidrSet(List.copyOf(prefixes)), List.copyOf(rejected));
    }

    /** Whether this set has no prefixes (so {@link #contains} is always false). */
    public boolean isEmpty() {
        return prefixes.isEmpty();
    }

    /**
     * Whether the set covers every address of a family: every IPv4 address, or every IPv6 address. A {@code /0} does,
     * and so do prefixes that add up to one: {@code 0.0.0.0/1} with {@code 128.0.0.0/1}, four {@code /2} quarters,
     * overlapping or nested ranges, and the same written in IPv4-mapped form ({@code ::ffff:0.0.0.0/97}). As a
     * trusted-proxy set that trusts every client of that family to name its own address, so callers refuse or warn.
     *
     * <p>Exact, not a heuristic: the family's ranges are sorted and merged, and the answer is whether they leave a
     * gap. A broad set with one hole (a single {@code /1}, or every {@code /2} but one) is not a catch-all.
     */
    public boolean hasCatchAll() {
        return coversFamily(4) || coversFamily(16);
    }

    /** Whether the prefixes of the family whose addresses are {@code length} bytes long leave no address out. */
    private boolean coversFamily(int length) {
        List<BigInteger[]> ranges = new ArrayList<>();
        for (Prefix prefix : prefixes) {
            if (prefix.network().length == length) {
                int hostBits = length * 8 - prefix.bits();
                BigInteger start = new BigInteger(1, prefix.network()).shiftRight(hostBits).shiftLeft(hostBits);
                ranges.add(new BigInteger[] {start, start.add(BigInteger.ONE.shiftLeft(hostBits))}); // [start, end)
            }
        }
        ranges.sort((a, b) -> a[0].compareTo(b[0]));
        BigInteger covered = BigInteger.ZERO; // every address below this is in some range
        for (BigInteger[] range : ranges) {
            if (range[0].compareTo(covered) > 0) {
                return false; // a gap before this range
            }
            covered = covered.max(range[1]);
        }
        return covered.equals(BigInteger.ONE.shiftLeft(length * 8));
    }

    /** Whether {@code ip} (an address literal) falls within any prefix. A non-literal or null is never contained. */
    public boolean contains(String ip) {
        byte[] address = literalBytes(ip);
        if (address == null) {
            return false;
        }
        for (Prefix prefix : prefixes) {
            if (prefix.network().length == address.length && matches(prefix.network(), address, prefix.bits())) {
                return true;
            }
        }
        return false;
    }

    private static Prefix tryParse(String entry) {
        if (entry == null) {
            return null;
        }
        String text = entry.trim();
        if (text.isEmpty()) {
            return null;
        }
        int slash = text.indexOf('/');
        byte[] address = literalBytes(slash < 0 ? text : text.substring(0, slash));
        if (address == null) {
            return null;
        }
        int maxBits = address.length * 8;
        if (slash < 0) {
            return new Prefix(address, maxBits);
        }
        int bits;
        try {
            bits = Integer.parseInt(text.substring(slash + 1).trim());
        } catch (NumberFormatException e) {
            return null;
        }
        // An IPv4-mapped prefix (::ffff:10.0.0.0/104) parses to IPv4 bytes, and a mapped peer is matched as IPv4 too:
        // its prefix counts the 96 bits in front of the IPv4 part. Shorter than /96 it is not a set of IPv4 addresses.
        if (address.length == 4 && text.substring(0, slash).indexOf(':') >= 0) {
            bits -= MAPPED_BITS;
        }
        return (bits < 0 || bits > maxBits) ? null : new Prefix(address, bits);
    }

    private static byte[] literalBytes(String ip) {
        if (ip == null) {
            return null;
        }
        String text = ip.trim();
        int zone = text.indexOf('%');
        if (zone >= 0) {
            text = text.substring(0, zone); // drop the IPv6 zone id (e.g. fe80::1%eth0)
        }
        if (text.isEmpty()) {
            return null;
        }
        try {
            return InetAddress.ofLiteral(text).getAddress(); // literal only — never resolves DNS
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean matches(byte[] network, byte[] address, int bits) {
        int fullBytes = bits / 8;
        for (int i = 0; i < fullBytes; i++) {
            if (network[i] != address[i]) {
                return false;
            }
        }
        int remaining = bits % 8;
        if (remaining != 0) {
            int mask = (0xFF << (8 - remaining)) & 0xFF;
            return (network[fullBytes] & mask) == (address[fullBytes] & mask);
        }
        return true;
    }
}
