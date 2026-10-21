package mx.quest.runway.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** CIDR membership: v4/v6 prefixes, bare-address coercion, zone-id strip, family isolation, rejected entries. */
class CidrSetTest {

    /** A /0 is a catch-all; a broad but real range is not. */
    @Test
    void aSlashZeroIsACatchAll() {
        assertTrue(CidrSet.of(List.of("0.0.0.0/0")).hasCatchAll());
        assertTrue(CidrSet.of(List.of("::/0")).hasCatchAll());
        assertTrue(CidrSet.of(List.of("10.0.0.5/32", "192.168.0.0/0")).hasCatchAll(), "any address with /0");
        assertFalse(CidrSet.of(List.of("10.0.0.0/8", "2001:db8::/32", "0.0.0.0/1")).hasCatchAll());
        assertFalse(CidrSet.of(List.of()).hasCatchAll());
    }

    /**
     * Ranges that together cover a whole family are a catch-all too. Two /1 halves let every client
     * name its own address exactly as a /0 does, and before the fix the form accepted them and the server did not
     * warn.
     */
    @Test
    void rangesThatAddUpToAFamilyAreACatchAll() {
        assertTrue(CidrSet.of(List.of("0.0.0.0/1", "128.0.0.0/1")).hasCatchAll(), "two IPv4 halves");
        assertTrue(CidrSet.of(List.of("::/1", "8000::/1")).hasCatchAll(), "two IPv6 halves");
        assertTrue(CidrSet.of(List.of("192.0.0.0/2", "0.0.0.0/2", "128.0.0.0/2", "64.0.0.0/2")).hasCatchAll(),
                "four quarters in any order");
        assertTrue(CidrSet.of(List.of("0.0.0.0/1", "128.0.0.0/2", "192.0.0.0/2", "10.0.0.0/8")).hasCatchAll(),
                "mixed sizes, one nested inside another");
        assertTrue(CidrSet.of(List.of("127.255.255.255/1", "200.1.2.3/1")).hasCatchAll(),
                "host bits after the prefix do not matter");
        assertTrue(CidrSet.of(List.of("::ffff:0.0.0.0/96")).hasCatchAll(), "every IPv4 address, IPv4-mapped");
        assertTrue(CidrSet.of(List.of("::ffff:0.0.0.0/97", "128.0.0.0/1")).hasCatchAll(), "mapped half + plain half");

        assertFalse(CidrSet.of(List.of("0.0.0.0/1", "128.0.0.0/2")).hasCatchAll(), "a quarter is missing");
        assertFalse(CidrSet.of(List.of("0.0.0.0/2", "64.0.0.0/2", "128.0.0.0/2", "192.0.0.0/3")).hasCatchAll(),
                "one /3 short at the top");
        assertFalse(CidrSet.of(List.of("0.0.0.0/1", "8000::/1")).hasCatchAll(), "halves of different families");
        assertFalse(CidrSet.of(List.of("1.0.0.0/1", "255.255.255.255")).hasCatchAll(),
                "the top address alone does not complete the top half");
    }

    /** An IPv4-mapped prefix is the IPv4 range it names, and a mapped peer matches it; shorter than /96 it is refused. */
    @Test
    void anIpv4MappedPrefixIsItsIpv4Range() {
        CidrSet mapped = CidrSet.of(List.of("::ffff:10.0.0.0/104"));
        assertTrue(mapped.contains("10.200.0.1"));
        assertTrue(mapped.contains("::ffff:10.200.0.1"));
        assertFalse(mapped.contains("11.0.0.1"));
        assertEquals(List.of("::ffff:0.0.0.0/95"), CidrSet.parse(List.of("::ffff:0.0.0.0/95")).rejected());
        assertTrue(CidrSet.of(List.of("::ffff:192.0.2.7")).contains("192.0.2.7"), "a bare mapped address is a host");
    }

    @Test
    void ipv4PrefixAndBareAddress() {
        CidrSet block = CidrSet.of(List.of("10.0.0.0/8"));
        assertTrue(block.contains("10.255.1.1"));
        assertFalse(block.contains("11.0.0.1"));

        CidrSet host = CidrSet.of(List.of("127.0.0.1")); // bare => /32
        assertTrue(host.contains("127.0.0.1"));
        assertFalse(host.contains("127.0.0.2"));
    }

    @Test
    void ipv6PrefixBareAddressAndZoneId() {
        CidrSet linkLocal = CidrSet.of(List.of("fe80::/10"));
        assertTrue(linkLocal.contains("fe80::1"));
        assertTrue(linkLocal.contains("fe80::1%eth0"), "the zone id is stripped before matching");
        assertFalse(linkLocal.contains("fe00::1"));

        CidrSet loopback = CidrSet.of(List.of("::1/128"));
        assertTrue(loopback.contains("::1"));
        assertFalse(loopback.contains("::2"));
    }

    @Test
    void nonByteAlignedPrefixesMaskTheBoundaryByteCorrectly() {
        // /22: the mask falls inside byte index 2 (mask 0xFC). In-block and out-of-block addresses that share
        // bytes 0-1 and differ only in the masked byte isolate the partial-mask math.
        CidrSet v4 = CidrSet.of(List.of("192.168.0.0/22"));
        assertTrue(v4.contains("192.168.0.1"));
        assertTrue(v4.contains("192.168.3.255"), "byte[2]=3 is inside the /22 block (3 & 0xFC == 0)");
        assertFalse(v4.contains("192.168.4.0"), "byte[2]=4 is outside the /22 block (4 & 0xFC != 0)");

        // fc00::/7: the mask falls inside byte index 0 (mask 0xFE). fc and fd share the masked high bits.
        CidrSet uniqueLocal = CidrSet.of(List.of("fc00::/7"));
        assertTrue(uniqueLocal.contains("fc00::1"), "0xfc & 0xfe == 0xfc");
        assertTrue(uniqueLocal.contains("fd12::9"), "0xfd & 0xfe == 0xfc — still inside");
        assertFalse(uniqueLocal.contains("fe00::1"), "0xfe & 0xfe == 0xfe — outside");
    }

    @Test
    void familiesNeverCross() {
        assertFalse(CidrSet.of(List.of("10.0.0.0/8")).contains("::1"), "v4 prefix never contains a v6 address");
        assertFalse(CidrSet.of(List.of("::/0")).contains("1.2.3.4"), "v6 prefix never contains a v4 address");
        assertTrue(CidrSet.of(List.of("::/0")).contains("2001:db8::1"), "::/0 matches any v6");
    }

    @Test
    void anEmptySetContainsNothing() {
        assertTrue(CidrSet.of(List.of()).isEmpty());
        assertFalse(CidrSet.of(List.of()).contains("127.0.0.1"));
        assertTrue(CidrSet.of(null).isEmpty());
    }

    @Test
    void aNonLiteralIsNeverContained() {
        assertFalse(CidrSet.of(List.of("0.0.0.0/0")).contains("not-an-ip"));
        assertFalse(CidrSet.of(List.of("0.0.0.0/0")).contains(null));
    }

    @Test
    void parseReportsRejectedEntriesAndKeepsTheValidOnes() {
        CidrSet.Parsed parsed = CidrSet.parse(List.of("garbage", "10.0.0.0/8", "10.0.0.0/40", "  ", "::1"));
        assertTrue(parsed.rejected().contains("garbage"));
        assertTrue(parsed.rejected().contains("10.0.0.0/40"), "a prefix length beyond the family width is rejected");
        assertTrue(parsed.set().contains("10.1.2.3"));
        assertTrue(parsed.set().contains("::1"));
    }
}
