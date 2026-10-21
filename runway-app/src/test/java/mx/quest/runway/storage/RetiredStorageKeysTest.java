package mx.quest.runway.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

/**
 * {@code runway.storage.stats} and {@code runway.storage.logs} were removed before 1.0. A configuration that still
 * sets one must neither fail the start nor be silently ignored: one WARN per key, naming it.
 */
class RetiredStorageKeysTest {

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attach() {
        logger = (Logger) LoggerFactory.getLogger(RetiredStorageKeys.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    private List<String> warnings() {
        return appender.list.stream().filter(e -> e.getLevel() == Level.WARN).map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @Test
    void eachRetiredKeyStillSetIsNamedInOneWarning() {
        new RetiredStorageKeys(new MockEnvironment()
                .withProperty("runway.storage.stats", "/data/stats")
                .withProperty("runway.storage.logs", "/var/log/runway")).afterPropertiesSet();

        List<String> warnings = warnings();
        assertEquals(2, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("'runway.storage.stats'") && warnings.get(0).contains("no effect"));
        assertTrue(warnings.get(1).contains("'runway.storage.logs'") && warnings.get(1).contains("no effect"));
    }

    @Test
    void aCleanConfigurationLogsNothing() {
        new RetiredStorageKeys(new MockEnvironment().withProperty("runway.storage.external", "/ext"))
                .afterPropertiesSet();
        assertTrue(warnings().isEmpty(), warnings().toString());
    }

    @Test
    void anEnvironmentVariableIsDetectedThroughRelaxedBinding() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new SystemEnvironmentPropertySource("test-env",
                Map.of("RUNWAY_STORAGE_LOGS", "/var/log/runway")));
        assertEquals(List.of("runway.storage.logs"), new RetiredStorageKeys(env).present());
    }

    @Test
    void theRetiredKeysStillBindHarmlesslySoAnOldConfigurationStarts() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("old", Map.of(
                "runway.storage.external", "/ext", "runway.storage.stats", "/s", "runway.storage.logs", "/l")));
        StorageProperties bound = Binder.get(env).bind("runway.storage", StorageProperties.class).get();
        assertEquals(Path.of("/ext"), bound.getExternal(), "the rest of runway.storage still binds");
    }
}
