package mx.quest.runway.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * The root logger's Default and a refusal by the logging system: ROOT cannot inherit, so Default gives it the
 * configured {@code logging.level.root} or INFO (never null, which Logback refuses for the root logger); and a level
 * the logging system refuses is not stored, so the screen and the store keep telling the truth.
 */
class LogLevelServiceTest {

    private static StandardEnvironment environment(Map<String, Object> properties) {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test", properties));
        return env;
    }

    @Test
    void rootBackToDefaultIsInfoWhenNothingIsConfigured() {
        LoggingSystem logging = mock(LoggingSystem.class);
        StandardEnvironment env = environment(Map.of());
        PlatformSettingsStore platform = TestPlatformSettings.empty();
        LogLevelService service = new LogLevelService(logging, platform, new HostSettings(env), env);

        service.set("ROOT", "WARN");
        assertEquals("WARN", platform.get().logLevels().get("ROOT"));
        service.set("ROOT", "");

        verify(logging).setLogLevel(LoggingSystem.ROOT_LOGGER_NAME, LogLevel.WARN);
        verify(logging).setLogLevel(LoggingSystem.ROOT_LOGGER_NAME, LogLevel.INFO);
        assertTrue(platform.get().logLevels().isEmpty(), "Default removes the stored level");
    }

    @Test
    void rootDefaultReadsTheLowerCaseConfigurationKey() {
        StandardEnvironment env = environment(Map.of("logging.level.root", "warn"));
        LogLevelService service = new LogLevelService(mock(LoggingSystem.class), TestPlatformSettings.empty(),
                new HostSettings(env), env);
        assertEquals(LogLevel.WARN, service.configured("ROOT"));
        assertNull(service.configured("mx.quest.runway.page"), "any other logger still inherits");
    }

    @Test
    void aLevelTheLoggingSystemRefusesIsNotStored() {
        LoggingSystem logging = mock(LoggingSystem.class);
        doThrow(new IllegalArgumentException("refused")).when(logging).setLogLevel(anyString(), any());
        StandardEnvironment env = environment(Map.of());
        PlatformSettingsStore platform = TestPlatformSettings.empty();
        LogLevelService service = new LogLevelService(logging, platform, new HostSettings(env), env);

        assertThrows(IllegalArgumentException.class, () -> service.set("mx.quest.runway.page", "DEBUG"));
        assertTrue(platform.get().logLevels().isEmpty(), "nothing is kept for a level that was not applied");
    }

    @Test
    void rootInAnyCaseIsTheRootLoggerAndDefaultRemovesIt() {
        LoggingSystem logging = mock(LoggingSystem.class);
        StandardEnvironment env = environment(Map.of());
        PlatformSettingsStore platform = TestPlatformSettings.empty();
        LogLevelService service = new LogLevelService(logging, platform, new HostSettings(env), env);

        service.set("root", "WARN");
        assertEquals(Map.of("ROOT", "WARN"), platform.get().logLevels(), "stored under one spelling");
        service.set("Root", "");

        verify(logging).setLogLevel(LoggingSystem.ROOT_LOGGER_NAME, LogLevel.WARN);
        verify(logging).setLogLevel(LoggingSystem.ROOT_LOGGER_NAME, LogLevel.INFO);
        assertTrue(platform.get().logLevels().isEmpty());
    }

    @Test
    void aHandWrittenLowerCaseRootNeitherBeatsTheConsolesNorSurvivesDefault() {
        LoggingSystem logging = mock(LoggingSystem.class);
        StandardEnvironment env = environment(Map.of());
        PlatformSettingsStore platform = TestPlatformSettings.empty();
        platform.update(p -> p.withLogLevels(Map.of("ROOT", "INFO", "root", "OFF")));
        LogLevelService service = new LogLevelService(logging, platform, new HostSettings(env), env);

        service.applyStored();
        verify(logging).setLogLevel(LoggingSystem.ROOT_LOGGER_NAME, LogLevel.INFO);
        verify(logging, never()).setLogLevel(LoggingSystem.ROOT_LOGGER_NAME, LogLevel.OFF);

        service.set("ROOT", "");
        assertTrue(platform.get().logLevels().isEmpty(), "Default removes every spelling of root");
    }

    @Test
    void aLevelThatCannotBeStoredIsTakenBack() {
        LoggingSystem logging = mock(LoggingSystem.class);
        StandardEnvironment env = environment(Map.of());
        PlatformSettingsStore platform = mock(PlatformSettingsStore.class);
        when(platform.get()).thenReturn(TestPlatformSettings.empty().get());
        when(platform.update(any())).thenThrow(new java.io.UncheckedIOException(new java.io.IOException("read-only")));
        LogLevelService service = new LogLevelService(logging, platform, new HostSettings(env), env);

        assertThrows(java.io.UncheckedIOException.class, () -> service.set("mx.quest.runway.backup", "DEBUG"));
        InOrder order = inOrder(logging);
        order.verify(logging).setLogLevel("mx.quest.runway.backup", LogLevel.DEBUG);
        order.verify(logging).setLogLevel("mx.quest.runway.backup", null);
    }

    @Test
    void aHandWrittenEmptyLevelDoesNotMakeThePlatformFileUnreadable() {
        java.util.Map<String, String> levels = new java.util.HashMap<>();
        levels.put("ROOT", null);
        levels.put("mx.quest", "DEBUG");
        PlatformSettingsStore platform = TestPlatformSettings.empty();
        platform.update(p -> p.withLogLevels(levels));
        assertEquals(Map.of("mx.quest", "DEBUG"), platform.get().logLevels());
    }
}
