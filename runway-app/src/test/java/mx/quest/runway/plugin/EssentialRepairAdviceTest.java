package mx.quest.runway.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.env.MapPropertySource;

/**
 * A refused essential plugin tells the operator what to do (since 1.0.0-rc1). With SPI 1.0.0 an old protection
 * descriptor (no {@code requiresSpi}) is blocked, and a blocked essential plugin puts every tenant page in fail-closed
 * mode ({@code 503}). The operator meets that in two places — the plugins screen's row (the resolution reason) and
 * the boot log's fail-closed line — so both must name the fix: the copy of the plugin bundled with the running
 * release. The advice a feature plugin gets ("install another version, or remove it") is wrong here: no other
 * release of protection exists outside a Runway bundle, and removing it runs the sites without their abuse floor.
 */
class EssentialRepairAdviceTest {

    private static final PluginVersion SPI_ONE = PluginVersion.parse("1.0.0");

    private static LoadedPlugin coded(String id, String floor) {
        String yaml = "id: " + id + "\nversion: 1.0.0\nmainClass: x.Main\n"
                + (floor == null ? "" : "requiresSpi: \"" + floor + "\"\n");
        return new LoadedPlugin(PluginDescriptor.read(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8))),
                Path.of("plugins", id));
    }

    /**
     * The advice names the real paths: the release's own seed folder first (the installer keeps it in
     * the release directory, so no .bin is needed), the target folder, the installer re-run, the .bin fallback for a
     * release without seed/ or a hand-replaced kernel jar, and the rollback.
     */
    @Test
    void theRepairAdviceNamesTheBundledCopyAndWhereItGoes() {
        String advice = EssentialAllowlist.repairAdvice("protection");
        assertTrue(advice.contains("copy seed/plugins/protection/ over external/plugins/protection/, both in the "
                + "Runway home (/opt/runway on a default install;"), advice);
        assertTrue(advice.indexOf("copy seed/plugins/protection/") < advice.indexOf("--extract"),
                "the installed copy comes first, the .bin is the fallback: " + advice);
        assertTrue(advice.contains("after a rollback its seed/ is the older release's copy"), advice);
        assertTrue(advice.contains("re-run that release's installer"), advice);
        assertTrue(advice.contains("every upgrade refreshes it"), advice);
        assertTrue(advice.contains("no seed/ folder there (a release installed before 1.0.0-rc1) or you replaced the "
                + "kernel jar by hand"), "when seed/ is not the running release's copy: " + advice);
        assertTrue(advice.contains("./runway-<version>.bin --extract DIR"), advice);
        assertTrue(advice.contains("DIR/runway-<version>/seed/plugins/protection/ instead"), advice);
        assertTrue(advice.contains("plugin.yml.bak and *.jar.bak pair in external/plugins/protection/"),
                "the rollback case: " + advice);
        assertTrue(advice.contains("restart") && advice.contains("503"), advice);
        assertFalse(advice.contains("remove"), "never advise removing the security floor: " + advice);
        assertFalse(advice.contains("not copied"), "the old 'no seed/ on the host' claim is gone: " + advice);
    }

    /** The 1.0.0-rc1 blocker's exact shape: an old protection with no floor, on SPI 1.0.0. */
    @Test
    void aPreContractEssentialPluginIsToldToTakeTheBundledCopyNotToRemoveIt() {
        String reason = PluginResolution.resolve(List.of(coded("protection", null)), SPI_ONE).blocked().get("protection");
        assertTrue(reason.startsWith("was built for the pre-1.0 plugin contract (declares no requiresSpi;"), reason);
        assertTrue(reason.endsWith(EssentialAllowlist.repairAdvice("protection")), reason);
        assertFalse(reason.contains("or remove it"), reason);
    }

    /** A feature plugin keeps its own advice: another release of it exists, and removing it is a real option. */
    @Test
    void aFeaturePluginKeepsTheGenericAdvice() {
        String reason = PluginResolution.resolve(List.of(coded("blog", null)), SPI_ONE).blocked().get("blog");
        assertTrue(reason.endsWith("Install the release of this plugin made for Runway 1.0, or remove it."), reason);
        assertFalse(reason.contains("seed/plugins"), reason);
    }

    /** A protection newer than the kernel (a rollback over a refreshed folder): the same fix, not "upgrade Runway". */
    @Test
    void anEssentialPluginNeedingANewerSpiGetsTheRepairAdvice() {
        String reason = PluginResolution.resolve(List.of(coded("protection", ">=1.4.0")), SPI_ONE)
                .blocked().get("protection");
        assertTrue(reason.startsWith("needs Runway SPI 1.4.0 or later; this Runway provides 1.0.0. "), reason);
        assertTrue(reason.endsWith(EssentialAllowlist.repairAdvice("protection")), reason);
        assertFalse(reason.contains("earlier version"), reason);

        String feature = PluginResolution.resolve(List.of(coded("blog", ">=1.4.0")), SPI_ONE).blocked().get("blog");
        assertTrue(feature.endsWith("Upgrade Runway, or install an earlier version of this plugin."), feature);
    }

    /** A protection from an older MAJOR line on a newer kernel: the same fix. */
    @Test
    void anEssentialPluginFromAnOlderMajorLineGetsTheRepairAdvice() {
        String reason = PluginResolution.resolve(List.of(coded("protection", ">=1.0.0")), PluginVersion.parse("2.0.0"))
                .blocked().get("protection");
        assertTrue(reason.startsWith("was built for Runway SPI 1.x; this Runway provides 2.0.0"), reason);
        assertTrue(reason.endsWith(EssentialAllowlist.repairAdvice("protection")), reason);

        String feature = PluginResolution.resolve(List.of(coded("blog", ">=1.0.0")), PluginVersion.parse("2.0.0"))
                .blocked().get("blog");
        assertTrue(feature.endsWith("Install a version of this plugin built for SPI 2.x."), feature);
    }

    /** The fail-closed log line: a blocked plugin's line carries the refusal (which ends with the fix). */
    @Test
    void theFailClosedLineOfABlockedEssentialPluginCarriesItsReason() {
        String line = PluginContextInitializer.essentialFailureMessage("protection", "quarantined: it broke …");
        assertTrue(line.startsWith("essential plugin 'protection' is installed but failed to load — entering "
                + "fail-closed degraded mode (tenant pages answer 503). Blocked: quarantined: it broke …"), line);
    }

    /** Disabled, malformed or unregistrable: no refusal reason exists, so the line itself gives the fix. */
    @Test
    void theFailClosedLineOfAnUnloadedEssentialPluginGivesTheRepairAdvice() {
        String line = PluginContextInitializer.essentialFailureMessage("protection", null);
        assertTrue(line.contains("It is disabled (enabled: false), its plugin.yml is malformed"), line);
        assertTrue(line.endsWith(EssentialAllowlist.repairAdvice("protection")), line);
    }

    /**
     * End to end through the real initializer: an install upgraded without refreshing protection (its descriptor
     * from before the mandatory floor) boots fail-closed, and the boot log's error line says why and what to do.
     */
    @Test
    void anOldProtectionOnDiskFailsClosedWithAnActionableLogLine(@TempDir Path external) throws IOException {
        Path dir = Files.createDirectories(external.resolve("plugins").resolve("protection"));
        Files.writeString(dir.resolve("plugin.yml"),
                "id: protection\nversion: 1.4.1\nessential: true\nmainClass: mx.quest.protection.ProtectionConfig\n",
                StandardCharsets.UTF_8);

        Logger logger = (Logger) LoggerFactory.getLogger(PluginContextInitializer.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                    PluginContextInitializer.EXTERNAL_PROPERTY, external.toString(),
                    PluginContextInitializer.APP_MASTER_PROPERTY, "app-master")));
            new PluginContextInitializer().initialize(context);

            PluginRuntime runtime = PluginContextInitializer.runtimeOf(context).orElseThrow();
            assertTrue(runtime.essentialFailed(), "an old protection must fail closed, never boot open");
            assertTrue(runtime.blocked().get("protection").endsWith(EssentialAllowlist.repairAdvice("protection")),
                    "the plugins screen prints this reason in the row: " + runtime.blocked());

            List<String> errors = appender.list.stream().filter(e -> e.getLevel() == Level.ERROR)
                    .map(ILoggingEvent::getFormattedMessage).toList();
            assertEquals(1, errors.size(), errors.toString());
            String line = errors.get(0);
            assertTrue(line.contains("entering fail-closed degraded mode (tenant pages answer 503)"), line);
            assertTrue(line.contains("Blocked: was built for the pre-1.0 plugin contract (declares no requiresSpi;"),
                    line);
            assertTrue(line.contains("re-run that release's installer")
                    && line.contains("copy seed/plugins/protection/ over external/plugins/protection/")
                    && line.contains("/opt/runway"), line);
        } finally {
            logger.detachAppender(appender);
        }
    }
}
