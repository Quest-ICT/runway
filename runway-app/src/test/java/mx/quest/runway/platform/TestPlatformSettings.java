package mx.quest.runway.platform;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;

/**
 * Test helper: a real {@link PlatformSettingsStore} over a throwaway temp external dir. It reads as
 * {@link PlatformSettings#EMPTY} (no {@code platform.yml} yet) and writes are harmless, so unit tests that
 * only need a collaborator can use {@link #empty()}, while a test exercising the default-site fallback can
 * {@code update(...)} a value on it.
 */
public final class TestPlatformSettings {

    private TestPlatformSettings() {
    }

    /** A fresh, empty, temp-backed platform-settings store. */
    public static PlatformSettingsStore empty() {
        try {
            StorageProperties props = new StorageProperties();
            props.setExternal(Files.createTempDirectory("runway-platform-test"));
            return new PlatformSettingsStore(StorageLayout.from(props), new YamlStore());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
