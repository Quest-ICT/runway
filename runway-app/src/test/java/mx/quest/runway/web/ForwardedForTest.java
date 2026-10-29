package mx.quest.runway.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** The client is read from the right of the X-Forwarded-For chain, past the trusted proxies only. */
class ForwardedForTest {

    private static final CidrSet PROXIES = CidrSet.of(List.of("10.0.0.0/8", "2001:db8:ffff::/48"));

    private static ForwardedFor.Chain chain(String... lines) {
        return ForwardedFor.chain(Collections.enumeration(List.of(lines)));
    }

    @Test
    void anAppendingProxyCannotBeFooledByAClientWrittenEntry() {
        // The client sent "X-Forwarded-For: 6.6.6.6"; the proxy appended the address it saw.
        assertThat(ForwardedFor.client(chain("6.6.6.6, 203.0.113.7"), PROXIES, "10.0.0.9")).isEqualTo("203.0.113.7");
        // Same, with the proxy adding a second header line instead of appending to the first.
        assertThat(ForwardedFor.client(chain("6.6.6.6", "203.0.113.7"), PROXIES, "10.0.0.9")).isEqualTo("203.0.113.7");
    }

    @Test
    void trustedHopsAreSkippedFromTheRight() {
        assertThat(ForwardedFor.client(chain("6.6.6.6, 203.0.113.7, 10.1.1.1, 10.2.2.2"), PROXIES, "10.0.0.9"))
                .isEqualTo("203.0.113.7");
        assertThat(ForwardedFor.client(chain("203.0.113.7, [2001:db8:ffff::1]:443"), PROXIES, "10.0.0.9"))
                .isEqualTo("203.0.113.7");
    }

    @Test
    void aChainOfTrustedProxiesOnlyGivesItsLeftmostEntry() {
        assertThat(ForwardedFor.client(chain("10.3.3.3, 10.1.1.1"), PROXIES, "10.0.0.9")).isEqualTo("10.3.3.3");
    }

    @Test
    void noChainLeavesThePeer() {
        assertThat(ForwardedFor.client(ForwardedFor.Chain.NONE, PROXIES, "10.0.0.9")).isEqualTo("10.0.0.9");
        assertThat(ForwardedFor.client(chain(" , ,"), PROXIES, "10.0.0.9")).isEqualTo("10.0.0.9");
        assertThat(ForwardedFor.chain(null)).isEqualTo(ForwardedFor.Chain.NONE);
    }

    @Test
    void aDecidingEntryThatIsNotAnAddressFallsBackToThePeer() {
        assertThat(ForwardedFor.client(chain("203.0.113.7, unknown"), PROXIES, "10.0.0.9")).isEqualTo("10.0.0.9");
        assertThat(ForwardedFor.client(chain("203.0.113.7, example.com"), PROXIES, "10.0.0.9")).isEqualTo("10.0.0.9");
        // An unreadable entry to the LEFT of the client does not matter: it is never reached.
        assertThat(ForwardedFor.client(chain("garbage, 203.0.113.7"), PROXIES, "10.0.0.9")).isEqualTo("203.0.113.7");
    }

    @Test
    void addressFormsAreNormalised() {
        assertThat(ForwardedFor.address("192.0.2.1")).isEqualTo("192.0.2.1");
        assertThat(ForwardedFor.address("192.0.2.1:8080")).isEqualTo("192.0.2.1");
        assertThat(ForwardedFor.address("\"192.0.2.1\"")).isEqualTo("192.0.2.1");
        assertThat(ForwardedFor.address("2001:db8::1")).isEqualTo("2001:db8::1");
        assertThat(ForwardedFor.address("[2001:db8::1]")).isEqualTo("2001:db8::1");
        assertThat(ForwardedFor.address("[2001:db8::1]:8080")).isEqualTo("2001:db8::1");
        assertThat(ForwardedFor.address("\"[2001:db8::1]:8080\"")).isEqualTo("2001:db8::1");
    }

    @Test
    void nonAddressesAreRefusedWithoutAnyLookup() {
        assertThat(ForwardedFor.address(null)).isNull();
        assertThat(ForwardedFor.address("")).isNull();
        assertThat(ForwardedFor.address("unknown")).isNull();
        assertThat(ForwardedFor.address("_hidden")).isNull();
        assertThat(ForwardedFor.address("localhost")).isNull();
        assertThat(ForwardedFor.address("192.0.2.1:http")).isNull();
        assertThat(ForwardedFor.address("192.0.2.1:")).isNull();
        assertThat(ForwardedFor.address("192.0.2.256")).isNull();
        assertThat(ForwardedFor.address("[192.0.2.1]")).isNull();
        assertThat(ForwardedFor.address("[2001:db8::1")).isNull();
        assertThat(ForwardedFor.address("[2001:db8::1]x")).isNull();
        assertThat(ForwardedFor.address("fe80::1%eth0")).isNull();
        assertThat(ForwardedFor.address("1:2")).isNull();
        assertThat(ForwardedFor.address("1".repeat(65))).isNull();
    }

    @Test
    void aHugeChainKeepsOnlyItsRightmostEntries() {
        String line = IntStream.range(0, 1000).mapToObj(i -> "6.6." + (i / 250) + "." + (i % 250))
                .collect(Collectors.joining(", ")) + ", 203.0.113.7";
        ForwardedFor.Chain chain = chain(line);
        assertThat(chain.total()).isEqualTo(1001);
        assertThat(chain.entries()).hasSize(ForwardedFor.MAX_ENTRIES).endsWith("203.0.113.7");
        assertThat(chain.text()).startsWith("…, ").endsWith(", 203.0.113.7");
        assertThat(ForwardedFor.client(chain, PROXIES, "10.0.0.9")).isEqualTo("203.0.113.7");
    }

    @Test
    void aLongEntryIsCutForDisplayAndNeverTakenAsAnAddress() {
        ForwardedFor.Chain chain = chain("x".repeat(500));
        assertThat(chain.entries().getFirst()).hasSize(ForwardedFor.MAX_ENTRY_LENGTH + 1).endsWith("…");
        assertThat(ForwardedFor.client(chain, PROXIES, "10.0.0.9")).isEqualTo("10.0.0.9");
    }
}
