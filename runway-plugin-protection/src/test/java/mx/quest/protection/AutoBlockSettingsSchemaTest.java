package mx.quest.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * The auto-block thresholds are declared in {@code plugin.yml} <b>and</b> actually bind.
 *
 * <h2>Why both halves matter</h2>
 * Found on a live deployment: the four auto-escalation values existed and bound, but were <b>not declared</b> in the
 * plugin's settings schema. That is a worse state than simply missing, because it fails <i>later</i> and
 * <i>quietly</i> — an operator could hand-edit {@code block-seconds} into
 * {@code external/plugin-settings/protection.yml} and a restart would honour it, but the settings form writes
 * the <i>complete declared key set</i> on save, so the next unrelated save of that page silently dropped the
 * key and the block duration reverted to 900 s with no error and no log line.
 *
 * <p>So declaring the keys is only half the fix. A declared key whose path does not match the bound property is
 * equally silent: the form would accept a value, write it, and nothing would read it. This test pins the
 * <b>path</b> by binding a value and reading it back through {@link ProtectionProperties}.
 *
 * <p>It also pins the declared defaults against the Java defaults, so the form can never advertise one number
 * while the running system uses another.
 */
class AutoBlockSettingsSchemaTest {

    private static final String PREFIX = "runway.plugin.protection";

    private static String descriptor() throws IOException {
        return Files.readString(Path.of("plugin.yml"));
    }

    private static ProtectionProperties bind(Map<String, Object> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bind(PREFIX, ProtectionProperties.class)
                .orElseGet(ProtectionProperties::new);
    }

    /**
     * The operator-visible question this whole change exists to answer: "how long am I blocked for, and can I
     * change it?" A declared key that does not reach the running value would leave that answer wrong while
     * looking right in the console.
     */
    @Test
    void theDeclaredAutoBlockKeysBindToTheRunningValues() {
        ProtectionProperties bound = bind(Map.of(
                PREFIX + ".rate-limit.auto-block.enabled", "false",
                PREFIX + ".rate-limit.auto-block.breach-threshold", "17",
                PREFIX + ".rate-limit.auto-block.window-seconds", "23",
                PREFIX + ".rate-limit.auto-block.block-seconds", "42"));

        ProtectionProperties.AutoBlock autoBlock = bound.getRateLimit().getAutoBlock();
        assertEquals(false, autoBlock.isEnabled(), "the enable switch must reach the limiter");
        assertEquals(17, autoBlock.getBreachThreshold());
        assertEquals(23L, autoBlock.getWindowSeconds());
        assertEquals(42L, autoBlock.getBlockSeconds(), "the DURATION is the value an operator most needs to set");
    }

    /** Every key the form offers must exist in the descriptor, or it is never rendered at all. */
    @Test
    void allFourAutoBlockKeysAreDeclaredInTheDescriptor() throws IOException {
        String yml = descriptor();
        for (String key : new String[] {
                "rate-limit.auto-block.enabled",
                "rate-limit.auto-block.breach-threshold",
                "rate-limit.auto-block.window-seconds",
                "rate-limit.auto-block.block-seconds"}) {
            assertTrue(declaresKey(yml, key), "settings schema must declare: " + key);
        }
    }

    /**
     * The declared defaults must equal the Java defaults. If they drift, the console shows one number while the
     * running system uses another — and because the form writes the complete key set on save, merely opening
     * and saving the page would silently change behaviour to whatever the descriptor claimed.
     */
    @Test
    void theDeclaredDefaultsMatchTheRunningDefaults() throws IOException {
        String yml = descriptor();
        ProtectionProperties.AutoBlock defaults = new ProtectionProperties().getRateLimit().getAutoBlock();

        assertEquals(true, defaults.isEnabled());
        assertTrue(yml.contains("key: rate-limit.auto-block.enabled,          type: boolean, default: true"),
                "descriptor default must match the Java default (true)");
        assertEquals(60, defaults.getBreachThreshold());
        assertTrue(yml.contains("default: 60,  range: \"1..100000\""), "threshold default must match (60)");
        assertEquals(60L, defaults.getWindowSeconds());
        assertEquals(900L, defaults.getBlockSeconds());
        assertTrue(yml.contains("default: 900, range: \"1..604800\""), "duration default must match (900)");
    }

    // ---- the exemption list ----------------------------------------------------------------------

    /**
     * The allowlist is declared AND binds — the same two halves, on the key whose whole job is preventing a
     * self-lockout.
     *
     * <p>This one was missed when the auto-block keys above were fixed, and the omission was found the hard
     * way: a same-host SEO audit locked the admin IP out for 15 minutes and returned
     * 13 FAIL / 25 WARN, every one a {@code 429} rather than a defect, from a clean deployment — close to a
     * false regression report against the release.
     *
     * <p>It had always bound and reached {@code RateLimitEdgeGuard} — it was simply undeclared, which is the
     * worse state for exactly the reason this class was created: the settings form writes the complete
     * declared key set on save, so an operator who found the key in the source and set it would have lost it
     * on the next unrelated save of that page, and been locked out again with nothing to point at.
     */
    @Test
    void theAllowlistIsDeclaredAndBinds() throws IOException {
        assertTrue(declaresKey(descriptor(), "allowlist"),
                "an undeclared exemption key is silently wiped by the next save of the settings form");

        ProtectionProperties bound = bind(Map.of(
                PREFIX + ".allowlist[0]", "203.0.113.0/24",
                PREFIX + ".allowlist[1]", "198.51.100.7"));

        assertEquals(List.of("203.0.113.0/24", "198.51.100.7"), bound.getAllowlist(),
                "the declared key must reach the value the guard actually consults");
    }

    /** Absent means empty, not null — an empty list is "exempt nobody", never a startup failure. */
    @Test
    void anAbsentAllowlistIsEmptyRatherThanNull() {
        assertEquals(List.of(), new ProtectionProperties().getAllowlist());
        assertEquals(List.of(), bind(Map.of()).getAllowlist());
    }

    /**
     * The declared list actually exempts, end to end through the guard.
     *
     * <p>Asserted against the verdict rather than the property, because a value that binds and is never
     * consulted looks identical in configuration to one that works.
     */
    @Test
    void anAllowlistedAddressIsNeitherThrottledNorBlocked() {
        mx.quest.runway.web.CidrSet allow =
                mx.quest.runway.web.CidrSet.of(List.of("203.0.113.0/24"));
        java.nio.file.Path tmp;
        try {
            tmp = java.nio.file.Files.createTempDirectory("allowlist-test");
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        IpBlocklist blocklist = new IpBlocklist(tmp.resolve("b.yml"), new mx.quest.runway.storage.YamlStore(),
                allow, () -> 1_000L);
        // Even a bucket of one token, already spent, must not touch an allowlisted caller.
        RateLimitEdgeGuard guard =
                new RateLimitEdgeGuard(allow, blocklist, new TokenBucketRateLimiter(1, 0.0, 10, () -> 0L));

        for (int i = 0; i < 50; i++) {
            assertTrue(guard.check(new mx.quest.runway.protect.EdgeRequest("203.0.113.9", "GET", "/")).allowed(),
                    "an allowlisted address must never be throttled, however many requests it makes");
        }
        assertFalse(blocklist.isBlocked("203.0.113.9"), "…and must never be auto-blocked either");
    }

    /**
     * Whether the descriptor <b>declares</b> {@code key} — as an actual schema entry, not as a mention.
     *
     * <p>Written because the obvious {@code yml.contains("key: allowlist")} is <b>vacuous</b>: commenting the
     * declaration out leaves {@code # - { key: allowlist, …}} in the file, the substring still matches, and
     * the test passes while the key is undeclared. Proven by doing exactly that — the assertion did not
     * notice. This descriptor is heavily commented (the comments explain each key and therefore NAME it), so
     * substring matching here was always going to confirm the prose rather than the schema.
     */
    private static boolean declaresKey(String yml, String key) {
        for (String line : yml.split("\n")) {
            String t = line.trim();
            if (t.startsWith("#")) {
                continue;                       // prose that names a key is not a declaration
            }
            if (t.startsWith("- {") && t.replace(" ", "").contains("key:" + key.replace(" ", "") + ",")) {
                return true;
            }
        }
        return false;
    }
}
