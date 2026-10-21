package mx.quest.runway.web;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Watches a single file for content changes and invokes a callback (debounced) when it is written or
 * replaced. Watches the file's <em>parent directory</em> — the only thing a {@link WatchService} can register
 * — and filters to the target file name, so it also catches atomic replace-by-rename (the common shape a
 * certificate-renew hook uses).
 *
 * <p>General-purpose and side-effect-free beyond invoking {@code onChange}; the TLS use is
 * {@link TlsCertificateReloader}. A short debounce coalesces the burst of events a single write produces and
 * lets a half-written file settle before the callback runs. The callback runs on the watcher's own daemon
 * thread; exceptions it throws are logged and swallowed so a bad reload never kills the watcher.
 */
public final class KeystoreWatcher {

    private static final Logger log = LoggerFactory.getLogger(KeystoreWatcher.class);

    private final Path file;
    private final Path dir;
    private final Path fileName;
    private final Runnable onChange;
    private final long debounceMillis;

    private volatile boolean running;
    private WatchService watchService;
    private Thread thread;

    public KeystoreWatcher(Path file, Runnable onChange) {
        this(file, onChange, 500);
    }

    KeystoreWatcher(Path file, Runnable onChange, long debounceMillis) {
        this.file = file.toAbsolutePath();
        this.dir = this.file.getParent();
        this.fileName = this.file.getFileName();
        this.onChange = onChange;
        this.debounceMillis = debounceMillis;
    }

    /** Starts watching on a daemon thread. A second call is a no-op. */
    public synchronized void start() {
        if (running || dir == null) {
            return;
        }
        try {
            watchService = FileSystems.getDefault().newWatchService();
            dir.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_CREATE);
        } catch (IOException e) {
            log.warn("cannot watch keystore directory {} for changes: {}", dir, e.toString());
            return;
        }
        running = true;
        thread = new Thread(this::loop, "runway-tls-keystore-watch");
        thread.setDaemon(true);
        thread.start();
        log.info("watching TLS keystore {} for changes (hot reload on renewal)", file);
    }

    /** Stops the watcher and releases the {@link WatchService}. */
    public synchronized void stop() {
        running = false;
        if (watchService != null) {
            try {
                watchService.close(); // unblocks take() with ClosedWatchServiceException
            } catch (IOException ignored) {
                // closing best-effort
            }
        }
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void loop() {
        while (running) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (ClosedWatchServiceException | InterruptedException e) {
                return; // stopped
            }
            boolean hit = false;
            for (var event : key.pollEvents()) {
                Object context = event.context();
                if (context instanceof Path p && p.getFileName().equals(fileName)) {
                    hit = true;
                }
            }
            key.reset();
            if (hit) {
                debounceAndFire();
            }
        }
    }

    /** Coalesce the burst from one write, let a partial write settle, then fire the callback once. */
    private void debounceAndFire() {
        try {
            TimeUnit.MILLISECONDS.sleep(debounceMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        // Drain any further events accumulated during the debounce so a single settled write fires once.
        if (watchService != null) {
            WatchKey pending = watchService.poll();
            if (pending != null) {
                pending.pollEvents();
                pending.reset();
            }
        }
        try {
            onChange.run();
        } catch (RuntimeException e) {
            log.warn("keystore change callback failed: {}", e.toString());
        }
    }
}
