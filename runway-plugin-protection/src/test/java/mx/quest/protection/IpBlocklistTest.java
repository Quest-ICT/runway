package mx.quest.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import mx.quest.runway.storage.YamlStore;
import mx.quest.runway.web.CidrSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The mutable blocklist's contract: manual blocks persist and round-trip across a fresh instance over the same
 * file; auto blocks are in-memory only (never written); loopback and the configured allowlist are unblockable;
 * expired entries prune lazily. All with an injectable clock so expiry is deterministic.
 */
class IpBlocklistTest {

    @TempDir
    Path dir;

    private final AtomicLong clock = new AtomicLong(1_000L); // epoch seconds

    private IpBlocklist blocklist(CidrSet allowlist) {
        return new IpBlocklist(dir.resolve("blocklist.yml"), new YamlStore(), allowlist, clock::get);
    }

    private IpBlocklist blocklist() {
        return blocklist(CidrSet.of(List.of()));
    }

    @Test
    void aManualBlockIsReportedBlockedAndUnblockRemovesIt() {
        IpBlocklist b = blocklist();
        assertTrue(b.block("203.0.113.7", 900, "abuse", true));
        assertTrue(b.isBlocked("203.0.113.7"));
        assertEquals(1, b.list().size());

        b.unblock("203.0.113.7");
        assertFalse(b.isBlocked("203.0.113.7"));
        assertTrue(b.list().isEmpty());
    }

    @Test
    void aManualBlockPersistsAndSurvivesAFreshInstanceOverTheSameFile() {
        IpBlocklist first = blocklist();
        first.block("198.51.100.4", 3_600, "manual ban", true);
        assertTrue(Files.isRegularFile(dir.resolve("blocklist.yml")), "manual block wrote the file");

        // A fresh instance over the same file (same not-yet-advanced clock) re-reads the persisted manual block.
        IpBlocklist reloaded = blocklist();
        assertTrue(reloaded.isBlocked("198.51.100.4"), "the persisted manual block survived a restart");
        assertEquals("manual ban", reloaded.list().get(0).reason());
        assertTrue(reloaded.list().get(0).manual());
    }

    @Test
    void unblockingAManualEntryRewritesTheFileSoItDoesNotComeBack() {
        IpBlocklist first = blocklist();
        first.block("198.51.100.4", 3_600, "x", true);
        first.unblock("198.51.100.4");

        IpBlocklist reloaded = blocklist();
        assertFalse(reloaded.isBlocked("198.51.100.4"), "unblock rewrote the file — the entry does not resurrect");
    }

    @Test
    void anAutoBlockIsInMemoryOnlyAndIsNeverWrittenToTheFile() throws IOException {
        IpBlocklist b = blocklist();
        assertTrue(b.block("192.0.2.9", 900, "auto: rate-limit", false));
        assertTrue(b.isBlocked("192.0.2.9"), "the auto block is live in memory");

        Path file = dir.resolve("blocklist.yml");
        if (Files.isRegularFile(file)) {
            String yaml = Files.readString(file);
            assertFalse(yaml.contains("192.0.2.9"), "an auto block must NEVER be persisted");
        }
        // And a fresh instance does not see it (it was never on disk).
        assertFalse(blocklist().isBlocked("192.0.2.9"), "an auto block does not survive a restart");
    }

    @Test
    void loopbackIsAlwaysAllowlistedAndNeverBlockable() {
        IpBlocklist b = blocklist();
        assertTrue(b.isAllowlisted("127.0.0.1"));
        assertTrue(b.isAllowlisted("::1"));
        assertFalse(b.block("127.0.0.1", 900, "nope", true), "loopback is refused (unblockable)");
        assertFalse(b.isBlocked("127.0.0.1"));
    }

    @Test
    void aConfiguredAllowlistedIpIsNeverBlockable() {
        IpBlocklist b = blocklist(CidrSet.of(List.of("10.0.0.0/8")));
        assertTrue(b.isAllowlisted("10.1.2.3"));
        assertFalse(b.block("10.1.2.3", 900, "nope", true), "an allowlisted IP is refused (manual)");
        assertFalse(b.block("10.1.2.3", 900, "auto", false), "an allowlisted IP is refused (auto too)");
        assertFalse(b.isBlocked("10.1.2.3"));
    }

    @Test
    void anExpiredEntryPrunesLazilyOnTheNextCheck() {
        IpBlocklist b = blocklist();
        b.block("203.0.113.50", 60, "temp", true); // until = 1000 + 60 = 1060
        assertTrue(b.isBlocked("203.0.113.50"));

        clock.set(1_100L); // past the expiry
        assertFalse(b.isBlocked("203.0.113.50"), "an expired block is pruned on the next check");
        assertTrue(b.list().isEmpty());
    }

    @Test
    void anIndefiniteBlockNeverExpires() {
        IpBlocklist b = blocklist();
        b.block("203.0.113.99", 0, "forever", true); // 0 seconds = indefinite
        clock.set(Long.MAX_VALUE / 2);
        assertTrue(b.isBlocked("203.0.113.99"), "an indefinite block never lapses");
        assertTrue(b.list().get(0).indefinite());
    }

    @Test
    void aCidrBlockMatchesEveryAddressInTheRange() {
        IpBlocklist b = blocklist();
        b.block("203.0.113.0/24", 900, "range ban", true);
        assertTrue(b.isBlocked("203.0.113.1"));
        assertTrue(b.isBlocked("203.0.113.254"));
        assertFalse(b.isBlocked("203.0.114.1"), "an address outside the range is not blocked");
    }

    @Test
    void aMalformedFileFailsSoftToAnEmptyBlocklist() throws IOException {
        Files.writeString(dir.resolve("blocklist.yml"), "this: is: not: a: valid: blocklist: [[[");
        IpBlocklist b = blocklist(); // must not throw at construction
        assertTrue(b.list().isEmpty(), "a corrupt file degrades to an empty blocklist (fail-soft)");
        // And it still works after the bad load.
        assertTrue(b.block("192.0.2.1", 900, "ok", true));
        assertTrue(b.isBlocked("192.0.2.1"));
    }

    @Test
    void anAlreadyExpiredPersistedEntryIsDroppedOnLoad() {
        IpBlocklist first = blocklist();
        first.block("192.0.2.7", 60, "temp", true); // until 1060
        clock.set(2_000L); // both instances now see it as expired
        IpBlocklist reloaded = blocklist();
        assertFalse(reloaded.isBlocked("192.0.2.7"), "an expired persisted entry is dropped on load");
    }

    /**
     * A bare {@code 403} is not diagnosable: an operator ban and a system auto-escalation look identical on the
     * wire but call for opposite responses — investigate the client, versus check whether your own thresholds
     * are too tight. {@code blockKind} is what lets the rejection log and the metric tell them apart.
     */
    @Test
    void blockKindDistinguishesManualFromAutoFromCidr() {
        IpBlocklist blocklist = blocklist();
        blocklist.block("203.0.113.10", 60, "operator ban", true);
        blocklist.block("203.0.113.11", 60, "escalated", false);
        blocklist.block("198.51.100.0/24", 60, "range", true);

        assertEquals("blocklist-manual", blocklist.blockKind("203.0.113.10"));
        assertEquals("blocklist-auto", blocklist.blockKind("203.0.113.11"));
        assertEquals("blocklist-cidr", blocklist.blockKind("198.51.100.7"));
        assertNull(blocklist.blockKind("203.0.113.99"), "an unblocked address has no kind");
    }

    /**
     * {@code isBlocked} delegates to {@code blockKind}, so the two can never drift — the fail-open contract, the
     * allowlist precedence and the lazy prune all live in one implementation. Asserted across every shape,
     * because a second copy of this logic is exactly how a security check and its diagnostic disagree.
     */
    @Test
    void isBlockedAndBlockKindAlwaysAgree() {
        IpBlocklist blocklist = blocklist();
        blocklist.block("203.0.113.20", 60, "manual", true);
        blocklist.block("203.0.113.21", 60, "auto", false);
        blocklist.block("198.51.100.0/24", 60, "range", true);

        for (String ip : new String[] {"203.0.113.20", "203.0.113.21", "198.51.100.5", "203.0.113.99",
                "127.0.0.1", "", "  "}) {
            assertEquals(blocklist.isBlocked(ip), blocklist.blockKind(ip) != null,
                    "isBlocked and blockKind disagree for: " + ip);
        }
        assertNull(blocklist.blockKind(null));
    }

    /** An allowlisted address is never blocked, so it must report no kind either — not "blocked but allowed". */
    @Test
    void anAllowlistedAddressReportsNoBlockKind() {
        IpBlocklist blocklist = blocklist();
        blocklist.block("127.0.0.1", 60, "attempt", true);

        assertNull(blocklist.blockKind("127.0.0.1"));
        assertEquals(false, blocklist.isBlocked("127.0.0.1"));
    }

    // ---- a block that ends must leave a trace ---------------------------------------------------

    /**
     * An expired block appears in the history.
     *
     * <p>The default auto-block lasts fifteen minutes, so an operator asking "was the guard doing anything
     * when the site was slow at three?" arrives to an empty blocklist and cannot tell whether it acted at all
     * — the evidence deleted itself at exactly the rate that makes it hardest to catch.
     *
     * <p>Expiry is lazy, so the trace is written when something looks; {@code list()} is what looks.
     */
    @Test
    void anExpiredBlockIsRememberedWithItsReason(@TempDir Path dir) {
        long[] now = {1_000L};
        IpBlocklist blocklist = new IpBlocklist(dir.resolve("b.yml"), new YamlStore(), CidrSet.of(List.of()),
                () -> now[0]);
        blocklist.block("198.51.100.4", 900, "auto: 60 breaches in 60s", false);

        now[0] = 2_000L;
        blocklist.list();                                   // the lazy prune

        List<IpBlocklist.Lapsed> ended = blocklist.recentlyEnded();
        assertEquals(1, ended.size());
        assertEquals("198.51.100.4", ended.get(0).ip());
        assertEquals("auto: 60 breaches in 60s", ended.get(0).reason(),
                "the reason is the whole point — the rejection counter already says a rejection happened");
        assertFalse(ended.get(0).manual());
        assertEquals(2_000L, ended.get(0).endedAt());
    }

    /** An operator lifting a block is also an ending, and it is worth showing who did what. */
    @Test
    void anOperatorUnblockIsRecordedAsManual(@TempDir Path dir) {
        IpBlocklist blocklist = new IpBlocklist(dir.resolve("b.yml"), new YamlStore(), CidrSet.of(List.of()),
                () -> 1_000L);
        blocklist.block("203.0.113.7", 900, "banned by hand", true);

        blocklist.unblock("203.0.113.7");

        assertEquals(1, blocklist.recentlyEnded().size());
        assertTrue(blocklist.recentlyEnded().get(0).manual());
    }

    /** A live block is not in the history — the two lists must never show the same address at once. */
    @Test
    void aBlockStillInForceIsNotInTheHistory(@TempDir Path dir) {
        IpBlocklist blocklist = new IpBlocklist(dir.resolve("b.yml"), new YamlStore(), CidrSet.of(List.of()),
                () -> 1_000L);
        blocklist.block("198.51.100.5", 900, "auto", false);

        blocklist.list();

        assertTrue(blocklist.recentlyEnded().isEmpty());
    }

    /**
     * The history is bounded, newest first.
     *
     * <p>It runs during a flood, which is when an unbounded structure is most dangerous — and the addresses
     * in it are attacker-supplied. A diagnostic that answers "what happened recently" does not need, and must
     * not have, unbounded memory.
     */
    @Test
    void theHistoryIsBoundedAndNewestFirst(@TempDir Path dir) {
        long[] now = {1_000L};
        IpBlocklist blocklist = new IpBlocklist(dir.resolve("b.yml"), new YamlStore(), CidrSet.of(List.of()),
                () -> now[0]);
        for (int i = 0; i < 150; i++) {
            blocklist.block("198.51.100." + (i % 250), 10, "auto " + i, false);
            now[0] += 20;
            blocklist.list();                               // each one lapses before the next is applied
        }

        List<IpBlocklist.Lapsed> ended = blocklist.recentlyEnded();
        assertEquals(100, ended.size(), "bounded");
        assertTrue(ended.get(0).endedAt() > ended.get(ended.size() - 1).endedAt(), "newest first");
    }
}
