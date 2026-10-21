package mx.quest.runway.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code platform.yml} is written by many screens and by the site-deletion listener. Two changes to
 * different fields made at the same moment must both survive, on disk and in the cache.
 */
class PlatformSettingsStoreTest {

    private static PlatformSettingsStore storeIn(Path dir) {
        StorageProperties props = new StorageProperties();
        props.setExternal(dir);
        return new PlatformSettingsStore(StorageLayout.from(props), new YamlStore());
    }

    private static PlatformSettings.EmbedHost youtube() {
        return new PlatformSettings.EmbedHost("youtube", "YouTube", "https://www.youtube.com", List.of("frame-src"));
    }

    /**
     * The losing interleaving, forced: one change has read the settings and is still building its result when a
     * second change to another field starts. The second must see the first's result, not the snapshot before it.
     */
    @Test
    void aChangeStartedWhileAnotherIsInFlightBuildsOnItsResult(@TempDir Path dir) throws Exception {
        PlatformSettingsStore store = storeIn(dir);
        CountDownLatch firstHasRead = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> embed = pool.submit(() -> store.update(current -> {
                firstHasRead.countDown();
                sleep(150); // still building while the other change starts
                return current.withEmbedHosts(List.of(youtube()));
            }));
            assertTrue(firstHasRead.await(5, TimeUnit.SECONDS));
            Future<?> levels = pool.submit(() -> store.update(current ->
                    current.withLogLevels(Map.of("mx.quest", "DEBUG"))));
            embed.get(5, TimeUnit.SECONDS);
            levels.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        for (PlatformSettings settings : List.of(store.get(), storeIn(dir).get())) { // the cache, then the file
            assertEquals(1, settings.embedHosts().size(), "the embed host survived");
            assertEquals(Map.of("mx.quest", "DEBUG"), settings.logLevels(), "and so did the log level");
        }
    }

    /** Free-running: many concurrent changes to three different fields; each one's last write is still there. */
    @Test
    void concurrentChangesToDifferentFieldsAllSurvive(@TempDir Path dir) throws Exception {
        PlatformSettingsStore store = storeIn(dir);
        int rounds = 40;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        List<Future<?>> writers = new ArrayList<>();
        try {
            writers.add(pool.submit(() -> {
                await(start);
                for (int i = 0; i < rounds; i++) {
                    long quota = i + 1;
                    store.update(current -> current.withSiteQuota("acme", quota));
                }
            }));
            writers.add(pool.submit(() -> {
                await(start);
                for (int i = 0; i < rounds; i++) {
                    String level = i % 2 == 0 ? "DEBUG" : "WARN";
                    store.update(current -> current.withLogLevels(Map.of("mx.quest", level)));
                }
            }));
            writers.add(pool.submit(() -> {
                await(start);
                for (int i = 0; i < rounds; i++) {
                    store.update(current -> current.withSettings(Map.of("runway.csp.mode", "enforce")));
                }
            }));
            start.countDown();
            for (Future<?> writer : writers) {
                writer.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        PlatformSettings onDisk = storeIn(dir).get();
        assertEquals(Long.valueOf(rounds), onDisk.siteQuotas().get("acme"));
        assertEquals(Map.of("mx.quest", "WARN"), onDisk.logLevels());
        assertEquals(Map.of("runway.csp.mode", "enforce"), onDisk.settings());
    }

    @Test
    void aChangeThatThrowsWritesNothing(@TempDir Path dir) {
        PlatformSettingsStore store = storeIn(dir);
        store.update(current -> current.withDefaultSiteId("home"));
        try {
            store.update(current -> {
                throw new IllegalArgumentException("refused");
            });
        } catch (IllegalArgumentException expected) {
            // the refusal reaches the caller
        }
        assertEquals("home", storeIn(dir).get().defaultSite().orElseThrow());
        assertEquals("home", store.get().defaultSite().orElseThrow());
    }

    /**
     * A change that leaves the settings as they are (every Sites edit whose cap did not change, a site
     * deletion with nothing of the site's in the file) rewrote {@code platform.yml} with the same bytes.
     */
    @Test
    void aChangeThatChangesNothingWritesNothing(@TempDir Path dir) throws Exception {
        PlatformSettingsStore store = storeIn(dir);
        store.update(current -> current.withSiteQuota("acme", 5L));
        Path file = dir.resolve("platform.yml");
        Object before = java.nio.file.Files.readAttributes(file,
                java.nio.file.attribute.BasicFileAttributes.class).fileKey();

        store.update(current -> current);
        store.update(current -> current.withSiteQuota("acme", 5L)); // a new object, equal to the old one
        Object after = java.nio.file.Files.readAttributes(file,
                java.nio.file.attribute.BasicFileAttributes.class).fileKey();
        assertEquals(before, after, "the file was not replaced");

        store.update(current -> current.withSiteQuota("acme", 6L));
        Object changed = java.nio.file.Files.readAttributes(file,
                java.nio.file.attribute.BasicFileAttributes.class).fileKey();
        assertTrue(!before.equals(changed), "a real change still writes");
        assertEquals(6L, storeIn(dir).get().siteQuotas().get("acme"));

        PlatformSettingsStore empty = storeIn(dir.resolve("none"));
        java.nio.file.Files.createDirectories(dir.resolve("none"));
        empty.update(current -> current);
        assertTrue(!java.nio.file.Files.exists(dir.resolve("none").resolve("platform.yml")),
                "nothing to store creates no file");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
