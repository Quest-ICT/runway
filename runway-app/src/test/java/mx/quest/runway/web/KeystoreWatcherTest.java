package mx.quest.runway.web;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** KeystoreWatcher fires (debounced) when the watched file is rewritten, and stops cleanly. */
class KeystoreWatcherTest {

    @Test
    void rewritingTheWatchedFileFiresTheCallback(@TempDir Path dir) throws Exception {
        Path keystore = dir.resolve("runway.p12");
        Files.writeString(keystore, "v1", StandardCharsets.UTF_8);

        CountDownLatch fired = new CountDownLatch(1);
        AtomicInteger count = new AtomicInteger();
        KeystoreWatcher watcher = new KeystoreWatcher(keystore, () -> {
            count.incrementAndGet();
            fired.countDown();
        }, 50); // short debounce for the test
        watcher.start();
        try {
            // Give the watch service a moment to register before the first write.
            Thread.sleep(200);
            Files.writeString(keystore, "v2-renewed", StandardCharsets.UTF_8);
            assertTrue(fired.await(10, TimeUnit.SECONDS), "the callback should fire after the file changes");
            assertTrue(count.get() >= 1, "at least one reload");
        } finally {
            watcher.stop();
        }
    }

    @Test
    void aChangeToAnUnrelatedSiblingFileDoesNotFire(@TempDir Path dir) throws Exception {
        Path keystore = dir.resolve("runway.p12");
        Files.writeString(keystore, "v1", StandardCharsets.UTF_8);

        AtomicInteger count = new AtomicInteger();
        KeystoreWatcher watcher = new KeystoreWatcher(keystore, count::incrementAndGet, 50);
        watcher.start();
        try {
            Thread.sleep(200);
            Files.writeString(dir.resolve("other.txt"), "noise", StandardCharsets.UTF_8);
            Thread.sleep(600); // longer than the debounce — a fire would have happened by now
            assertTrue(count.get() == 0, "an unrelated file must not trigger a reload");
        } finally {
            watcher.stop();
        }
    }
}
