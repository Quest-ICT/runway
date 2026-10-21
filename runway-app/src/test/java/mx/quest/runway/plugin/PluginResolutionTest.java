package mx.quest.runway.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Conflicts-first then requires-to-fixpoint: blocks with reasons, cascades transitively, blocks cycles. */
class PluginResolutionTest {

    private static LoadedPlugin plugin(String id, String version, List<String> requires, List<String> conflicts) {
        String yaml = "id: " + id + "\nversion: " + version + "\n";
        if (!requires.isEmpty()) {
            yaml += "requires: [" + requires.stream().map(r -> "\"" + r + "\"").collect(Collectors.joining(", ")) + "]\n";
        }
        if (!conflicts.isEmpty()) {
            yaml += "conflicts: [" + conflicts.stream().map(c -> "\"" + c + "\"").collect(Collectors.joining(", ")) + "]\n";
        }
        PluginDescriptor d = PluginDescriptor.read(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
        return new LoadedPlugin(d, Path.of("/tmp/plugins", id));
    }

    private static LoadedPlugin p(String id, String version) {
        return plugin(id, version, List.of(), List.of());
    }

    private static List<String> ids(List<LoadedPlugin> plugins) {
        return plugins.stream().map(LoadedPlugin::id).toList();
    }

    @Test
    void noConstraintsAllLoadable() {
        PluginResolution.Result r = PluginResolution.resolve(List.of(p("a", "1.0.0"), p("b", "1.0.0")));
        assertEquals(List.of("a", "b"), ids(r.loadable()));
        assertTrue(r.blocked().isEmpty());
    }

    @Test
    void aMissingRequirementBlocksWithAReason() {
        PluginResolution.Result r = PluginResolution.resolve(List.of(plugin("a", "1.0.0", List.of("b"), List.of())));
        assertTrue(r.loadable().isEmpty());
        assertTrue(r.blocked().get("a").contains("not installed"), r.blocked().get("a"));
    }

    @Test
    void aVersionMismatchBlocksButASatisfiedMinimumLoads() {
        PluginResolution.Result mismatch = PluginResolution.resolve(List.of(
                plugin("a", "1.0.0", List.of("b >= 2.0"), List.of()), p("b", "1.0.0")));
        assertEquals(List.of("b"), ids(mismatch.loadable()));
        assertTrue(mismatch.blocked().get("a").contains("1.0.0 is installed"), mismatch.blocked().get("a"));

        PluginResolution.Result ok = PluginResolution.resolve(List.of(
                plugin("a", "1.0.0", List.of("b >= 2.0"), List.of()), p("b", "2.5.0")));
        assertEquals(List.of("a", "b"), ids(ok.loadable()));
    }

    @Test
    void blockingCascadesTransitively() {
        // A -> B -> C, with C absent: B blocks (missing C), A blocks (B blocked).
        PluginResolution.Result r = PluginResolution.resolve(List.of(
                plugin("a", "1.0.0", List.of("b"), List.of()),
                plugin("b", "1.0.0", List.of("c"), List.of())));
        assertTrue(r.loadable().isEmpty());
        assertTrue(r.blocked().get("b").contains("not installed"));
        assertTrue(r.blocked().get("a").contains("blocked"), r.blocked().get("a"));
    }

    @Test
    void aRequiresCycleBlocksTheWholeCycle() {
        PluginResolution.Result r = PluginResolution.resolve(List.of(
                plugin("a", "1.0.0", List.of("b"), List.of()),
                plugin("b", "1.0.0", List.of("a"), List.of())));
        assertTrue(r.loadable().isEmpty(), "a requires: cycle can never be ordered, so it blocks");
        assertTrue(r.blocked().containsKey("a") && r.blocked().containsKey("b"));
    }

    @Test
    void mutualConflictBlocksBothOneSidedStepsDeclarerAside() {
        PluginResolution.Result mutual = PluginResolution.resolve(List.of(
                plugin("a", "1.0.0", List.of(), List.of("b")),
                plugin("b", "1.0.0", List.of(), List.of("a"))));
        assertTrue(mutual.loadable().isEmpty());
        assertEquals(2, mutual.blocked().size());

        PluginResolution.Result oneSided = PluginResolution.resolve(List.of(
                plugin("a", "1.0.0", List.of(), List.of("b")), p("b", "1.0.0")));
        assertEquals(List.of("b"), ids(oneSided.loadable()), "only the declarer steps aside");
    }

    @Test
    void orderPutsAProviderBeforeItsConsumer() {
        LoadedPlugin consumer = plugin("a", "1.0.0", List.of("b"), List.of());
        LoadedPlugin provider = p("b", "1.0.0");
        assertEquals(List.of("b", "a"), ids(PluginLoader.order(List.of(consumer, provider))));
    }

    @Test
    void resolutionIsReproducible() {
        List<LoadedPlugin> input = List.of(
                plugin("a", "1.0.0", List.of("b >= 9.0"), List.of()), p("b", "1.0.0"));
        assertEquals(PluginResolution.resolve(input).blocked(), PluginResolution.resolve(input).blocked());
    }

    // ---- the kernel SPI floor -------------------------------------------------------------------------

    private static LoadedPlugin needingSpi(String id, String floor) {
        String yaml = "id: " + id + "\nversion: 1.0.0\nrequiresSpi: \"" + floor + "\"\n";
        return new LoadedPlugin(
                PluginDescriptor.read(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8))),
                Path.of("/tmp/plugins", id));
    }

    /**
     * A plugin needing a newer SPI is BLOCKED at startup instead of crashing mid-request.
     *
     * <p>Without this it loads, registers, and throws {@code NoSuchMethodError} the first time the affected
     * path runs — possibly on a public page request, long after startup, naming a method the operator has
     * never heard of. The message has to say which side to move, because the crash never does.
     */
    @Test
    void aPluginNeedingANewerSpiIsBlockedWithAnActionableReason() {
        PluginResolution.Result r = PluginResolution.resolve(
                List.of(needingSpi("newer", ">=9.9.9")), PluginVersion.parse("0.3.4"));

        assertTrue(r.loadable().isEmpty());
        String reason = r.blocked().get("newer");
        assertTrue(reason.contains("9.9.9") && reason.contains("0.3.4"), reason);
        assertTrue(reason.contains("Upgrade Runway") && reason.contains("earlier version"),
                "the operator must be told which side to move: " + reason);
    }

    /** The platform still boots with its other plugins — degrade, do not crash. */
    @Test
    void aFloorBlockedPluginDoesNotTakeTheOthersWithIt() {
        PluginResolution.Result r = PluginResolution.resolve(
                List.of(needingSpi("newer", ">=9.9.9"), p("fine", "1.0.0")), PluginVersion.parse("0.3.4"));

        assertEquals(List.of("fine"), ids(r.loadable()));
    }

    /** A satisfied floor loads — on the exact version and on a later one. */
    @Test
    void aSatisfiedFloorLoadsOnThatVersionAndOnLaterOnes() {
        assertTrue(PluginResolution.resolve(List.of(needingSpi("ok", ">=0.3.4")), PluginVersion.parse("0.3.4"))
                .blocked().isEmpty(), "the floor is a minimum, so equal satisfies it");
        assertTrue(PluginResolution.resolve(List.of(needingSpi("ok", ">=0.3.4")), PluginVersion.parse("0.4.0"))
                .blocked().isEmpty(), "an OLDER plugin on a NEWER kernel is always safe — nothing is removed");
    }

    /**
     * No {@code requiresSpi:} means no floor.
     *
     * <p>Pinned deliberately: this is the compatibility guarantee that lets the feature ship without touching a
     * single existing plugin. If it ever regressed, every plugin in the wild would block at once.
     */
    @Test
    void aDescriptorWithNoFloorLoadsExactlyAsBefore() {
        PluginResolution.Result r = PluginResolution.resolve(
                List.of(p("legacy", "1.0.0")), PluginVersion.parse("0.0.1"));

        assertTrue(r.blocked().isEmpty());
        assertEquals(List.of("legacy"), ids(r.loadable()));
    }

    /** A plugin requiring a floor-blocked one is blocked too — the floor cascades like any other block. */
    @Test
    void blockingOnTheFloorCascadesToDependents() {
        PluginResolution.Result r = PluginResolution.resolve(
                List.of(needingSpi("provider", ">=9.9.9"), plugin("consumer", "1.0.0", List.of("provider"),
                        List.of())), PluginVersion.parse("0.3.4"));

        assertTrue(r.loadable().isEmpty());
        assertTrue(r.blocked().containsKey("consumer"), r.blocked().toString());
    }

    /**
     * A malformed floor is MALFORMED, not ignored.
     *
     * <p>A floor that fails open is worse than none: it reads as protection that is not there. A ceiling
     * (`<=`) is refused for the same reason it is out of scope — it would let a plugin turn a guarantee into a
     * liability by refusing a kernel that would have worked.
     */
    @Test
    void aMalformedFloorFailsTheDescriptorRatherThanBeingIgnored() {
        for (String bad : List.of("0.3.4", "<=0.3.4", ">= 0.3.x", "latest")) {
            String yaml = "id: bad\nversion: 1.0.0\nrequiresSpi: \"" + bad + "\"\n";
            PluginDescriptor d = PluginDescriptor.read(
                    new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
            assertThrows(IllegalArgumentException.class, d::validate, "must reject requiresSpi: " + bad);
        }
    }

    /** The no-arg resolve uses the kernel's real SPI level — the wiring, not just the parameterised form. */
    @Test
    void theDefaultResolveUsesTheRunningKernelsSpiVersion() {
        assertTrue(PluginResolution.resolve(List.of(needingSpi("future", ">=99.0.0"))).blocked()
                        .get("future").contains(mx.quest.runway.spi.RunwaySpi.VERSION),
                "the shipped SPI version must be what the check compares against");
    }

    // --- MAJOR ceiling, essential dependency refusal, pre-blocked plugins --------------------

    @Test
    void aPluginBuiltForAnOlderMajorLineIsBlockedOnANewerMajorKernel() {
        PluginResolution.Result r = PluginResolution.resolve(List.of(needingSpi("old", ">=1.2.0")),
                PluginVersion.parse("2.0.0"));
        assertTrue(r.loadable().isEmpty());
        assertTrue(r.blocked().get("old").contains("built for Runway SPI 1.x"), r.blocked().get("old"));
    }

    @Test
    void aZeroXFloorCountsAsOneXCompatible() {
        assertTrue(PluginResolution.resolve(List.of(needingSpi("pre", ">=0.3.4")), PluginVersion.parse("1.7.0"))
                .blocked().isEmpty(), "a 0.x floor is 1.x-compatible");
        assertTrue(PluginResolution.resolve(List.of(needingSpi("pre", ">=0.3.4")), PluginVersion.parse("2.0.0"))
                .blocked().get("pre").contains("built for Runway SPI 1.x"), "but not 2.x-compatible");
    }

    @Test
    void aPluginBuiltForTheSameMajorLineLoadsAndNoFloorMeansNoCeiling() {
        assertTrue(PluginResolution.resolve(List.of(needingSpi("cur", ">=2.0.0")), PluginVersion.parse("2.3.1"))
                .blocked().isEmpty());
        assertTrue(PluginResolution.resolve(List.of(p("legacy", "1.0.0")), PluginVersion.parse("3.0.0"))
                .blocked().isEmpty(), "a library-only plugin: no floor = no ceiling (the mandatory floor is for code)");
    }

    @Test
    void noPluginMayRequireAnEssentialPluginAndTheBlockCascades() {
        PluginResolution.Result r = PluginResolution.resolve(List.of(
                p("protection", "1.4.1"),
                plugin("captcha-user", "1.0.0", List.of("protection >= 1.2.0"), List.of()),
                plugin("downstream", "1.0.0", List.of("captcha-user"), List.of())));
        assertEquals(List.of("protection"), ids(r.loadable()));
        assertTrue(r.blocked().get("captcha-user").contains("essential plugin 'protection'"),
                r.blocked().get("captcha-user"));
        assertTrue(r.blocked().get("downstream").contains("itself blocked"), r.blocked().get("downstream"));
    }

    @Test
    void aPreBlockedPluginIsRefusedWithItsReasonAndItsDependentsCascade() {
        PluginResolution.Result r = PluginResolution.resolve(List.of(p("bad", "1.0.0"),
                plugin("user", "1.0.0", List.of("bad"), List.of()), p("fine", "1.0.0")),
                java.util.Map.of("bad", "quarantined: boom"));
        assertEquals(List.of("fine"), ids(r.loadable()));
        assertEquals("quarantined: boom", r.blocked().get("bad"));
        assertTrue(r.blocked().get("user").contains("itself blocked"));
    }

    // --- SPI 1.0.0: the mandatory floor ------------------------------------------------------------

    private static LoadedPlugin coded(String id, String floorOrNull) {
        String yaml = "id: " + id + "\nversion: 1.0.0\nmainClass: x." + id + ".Config\n"
                + (floorOrNull == null ? "" : "requiresSpi: \"" + floorOrNull + "\"\n");
        return new LoadedPlugin(PluginDescriptor.read(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8))),
                Path.of("/tmp/plugins", id));
    }

    private static LoadedPlugin library(String id) {
        String yaml = "id: " + id + "\nversion: 10.4.0\n";
        return new LoadedPlugin(PluginDescriptor.read(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8))),
                Path.of("/tmp/plugins", id));
    }

    /**
     * The migrated releases declare {@code >=0.4.0}, {@code >=0.5.0} or {@code >=0.6.0}; all load on 1.0.0 and on
     * any later 1.x — the pre-1.0 service tier IS the 1.0 contract. The plugins built for the old contract declare
     * nothing or a 0.3.x floor: all blocked, with the actionable reason.
     */
    @Test
    void onSpiOneTheMigratedFloorsLoadAndThePreContractOnesAreBlocked() {
        for (String spi : List.of("1.0.0", "1.4.2")) {
            PluginResolution.Result r = PluginResolution.resolve(List.of(
                    coded("carousel-manager", ">=0.4.0"), coded("contact", ">=0.5.0"), coded("search", ">=0.6.0"),
                    coded("protection", ">=1.0.0"), library("lucene-provider"),
                    coded("old-contact", null), coded("old-discoverability", ">=0.3.4"), coded("old-blog", ">=0.3.3")),
                    PluginVersion.parse(spi));
            assertEquals(List.of("carousel-manager", "contact", "search", "protection", "lucene-provider"),
                    ids(r.loadable()), "on SPI " + spi + ": " + r.blocked());
            assertEquals(java.util.Set.of("old-contact", "old-discoverability", "old-blog"), r.blocked().keySet());
            assertTrue(r.blocked().get("old-contact").startsWith("was built for the pre-1.0 plugin contract "
                    + "(declares no requiresSpi;"), r.blocked().get("old-contact"));
            assertTrue(r.blocked().get("old-discoverability").contains("declares requiresSpi >=0.3.4"),
                    r.blocked().get("old-discoverability"));
            assertTrue(r.blocked().get("old-blog").contains("Install the release of this plugin made for Runway 1.0"),
                    r.blocked().get("old-blog"));
        }
    }

    /** The boundary itself: {@code 0.4.0} is accepted, {@code 0.3.9} (and anything below) is not. */
    @Test
    void theMandatoryFloorBoundaryIsZeroFourZero() {
        PluginVersion one = PluginVersion.parse("1.0.0");
        assertTrue(PluginResolution.resolve(List.of(coded("edge", ">=0.4.0")), one).blocked().isEmpty());
        assertTrue(PluginResolution.resolve(List.of(coded("below", ">=0.3.9")), one).blocked().get("below")
                .contains("pre-1.0 plugin contract"));
        assertEquals("0.4.0", PluginResolution.MINIMUM_FLOOR.toString());
    }

    /** Only from SPI 1.x: the explicit-SPI form against a 0.x kernel keeps the old rules (no floor = loads). */
    @Test
    void theMandatoryFloorAppliesFromSpiOneOnly() {
        assertTrue(PluginResolution.resolve(List.of(coded("legacy", null)), PluginVersion.parse("0.6.0"))
                .blocked().isEmpty());
        assertTrue(PluginResolution.resolve(List.of(coded("legacy", null)), PluginVersion.parse("1.0.0"))
                .blocked().containsKey("legacy"));
    }

    /** The default resolve runs on the shipped SPI (1.0.0), so the mandatory floor is live. */
    @Test
    void theRunningKernelAppliesTheMandatoryFloor() {
        assertTrue(PluginResolution.resolve(List.of(coded("legacy", null))).blocked().containsKey("legacy"));
        assertTrue(PluginResolution.resolve(List.of(coded("migrated", ">=0.6.0"))).blocked().isEmpty());
    }

    /**
     * A pre-1.0 plugin is told the actionable reason even when the scanner (or a stale quarantine marker) also
     * refused it — and its dependents cascade.
     */
    @Test
    void theMandatoryFloorReasonWinsOverAPreBlockAndCascades() {
        PluginResolution.Result r = PluginResolution.resolve(List.of(coded("old", null),
                plugin("user", "1.0.0", List.of("old"), List.of())), PluginVersion.parse("1.0.0"),
                java.util.Map.of("old", "uses Runway's internal classes …"));
        assertTrue(r.blocked().get("old").startsWith("was built for the pre-1.0 plugin contract"), r.blocked().toString());
        assertTrue(r.blocked().get("user").contains("itself blocked"), r.blocked().toString());
    }

    /**
     * The essential plugin is not exempt: one built for the old contract is blocked (and the loader then fails closed,
     * as for any essential plugin that does not load). The shipped one declares {@code >=1.0.0}.
     */
    @Test
    void theEssentialPluginIsNotExemptFromTheFloor() {
        assertTrue(PluginResolution.resolve(List.of(coded("protection", null)), PluginVersion.parse("1.0.0"))
                .blocked().containsKey("protection"));
    }
}
