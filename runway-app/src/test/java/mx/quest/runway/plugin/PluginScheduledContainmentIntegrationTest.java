package mx.quest.runway.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mx.quest.runway.security.PasswordEncoders;
import mx.quest.runway.security.PasswordStrength;
import mx.quest.runway.security.Roles;
import mx.quest.runway.security.User;
import mx.quest.runway.security.UserStore;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A real plugin jar whose {@code @Scheduled} method throws on every run (every 10 ms), loaded by the production
 * loader. Its failures are contained under the containment policy — counted against the plugin on the {@code
 * scheduled} axis,
 * shown as its health — the task keeps its schedule, and the log is NOT flooded: Spring's default handler would print
 * a full stack trace per run (about a hundred a second here); the guard logs the first one and then at most one line
 * a minute.
 */
@SpringBootTest
@ExtendWith(OutputCaptureExtension.class)
class PluginScheduledContainmentIntegrationTest {

    private static final String CONFIG_SRC = """
            package fixtures.plugin.ticker;
            import org.springframework.context.annotation.Bean;
            import org.springframework.context.annotation.Configuration;
            @Configuration
            public class TickerConfig {
                @Bean public TickerTask tickerTask() { return new TickerTask(); }
            }
            """;

    private static final String TASK_SRC = """
            package fixtures.plugin.ticker;
            import java.util.concurrent.atomic.AtomicInteger;
            import org.springframework.scheduling.annotation.Scheduled;
            public class TickerTask {
                public static final AtomicInteger RUNS = new AtomicInteger();
                @Scheduled(fixedDelay = 10)
                public void tick() {
                    RUNS.incrementAndGet();
                    throw new IllegalStateException("ticker exploded");
                }
            }
            """;

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) throws IOException {
        Path external = Files.createTempDirectory("runway-k4-scheduled");
        if (PluginFixture.compilerAvailable()) {
            PluginFixture.build(external.resolve("plugins"), "ticker",
                    "id: ticker\nversion: 1.0.0\nrequiresSpi: \">=1.0.0\"\nmainClass: fixtures.plugin.ticker.TickerConfig\n",
                    Map.of("fixtures.plugin.ticker.TickerConfig", CONFIG_SRC,
                            "fixtures.plugin.ticker.TickerTask", TASK_SRC));
        }
        String hash = PasswordEncoders.argon2id(PasswordStrength.STANDARD).encode("Scheduled-Secret-9");
        new UserStore(new YamlStore()).save(external.resolve("app-users.yml"), new User("app-master", "root", hash,
                "Root", "", true, true, Realm.APPLICATION, Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        registry.add("runway.storage.external", external::toString);
        registry.add("runway.security.app-master-id", () -> "app-master");
    }

    @Autowired
    private ApplicationContext context;
    @Autowired
    private ContributionGuard guard;
    @Autowired
    private MeterRegistry meters;

    private long failures() {
        return guard.health("ticker").failures();
    }

    @Test
    void aThrowingPluginTaskIsContainedCountedAndDoesNotFloodTheLog(CapturedOutput output) throws Exception {
        assumeTrue(PluginFixture.compilerAvailable());
        java.util.Set<String> scheduled = new java.util.TreeSet<>();
        context.getBeansOfType(org.springframework.scheduling.config.ScheduledTaskHolder.class).values()
                .forEach(holder -> holder.getScheduledTasks().forEach(t -> scheduled.add(t.getTask().toString())));
        assertTrue(scheduled.contains("fixtures.plugin.ticker.TickerTask.tick"),
                "the plugin's method is scheduled (and the listing still names it): " + scheduled);
        long deadline = System.nanoTime() + 15_000_000_000L;
        while (failures() < 3 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        long start = failures();
        assertTrue(start >= 3, "the task keeps running on its schedule despite failing: " + start);
        int logLengthAtStart = output.getAll().length();
        long target = start + 30;
        while (failures() < target && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(failures() >= target, "thirty more runs, each failing: " + failures());
        String during = output.getAll().substring(logLengthAtStart);
        assertFalse(during.contains("ticker exploded"), "no stack trace or message per run while contained:\n"
                + during.substring(0, Math.min(2000, during.length())));
        assertFalse(during.contains("Unexpected error occurred in scheduled task"),
                "Spring's per-run error log is never reached");

        ContributionGuard.PluginHealth health = guard.health("ticker");
        assertTrue(health.lastError().contains("ticker exploded"), health.toString());
        assertTrue(meters.get(ContributionGuard.METRIC).tag("plugin", "ticker").tag("axis", "scheduled")
                .tag("key", "TickerTask.tick").counter().count() >= target);
        assertEquals(1, output.getAll().split("plugin 'ticker' scheduled 'TickerTask.tick' failed — substituted",
                -1).length - 1, "the first failure is logged once, with its stack trace");
    }
}
