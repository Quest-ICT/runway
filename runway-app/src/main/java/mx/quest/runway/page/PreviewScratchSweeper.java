package mx.quest.runway.page;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import mx.quest.runway.storage.StorageLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Removes any leftover <b>preview scratch pages</b> ({@link PageAuthoringValidator#SCRATCH_SLUG_PREFIX}) at
 * startup. The unsaved-draft preview writes a per-request scratch page and deletes it in a {@code finally}, so
 * the normal path leaves nothing behind — but a JVM crash between the write and the cleanup could orphan one.
 * Those files are already invisible (excluded from {@link PageStore#findAll}) and non-routable (always drafts),
 * so this is housekeeping, not correctness: it just keeps the tenant templates directories tidy.
 */
@Component
public class PreviewScratchSweeper {

    private static final Logger log = LoggerFactory.getLogger(PreviewScratchSweeper.class);
    private static final int SITE_TEMPLATE_DEPTH = 3; // sites/<id>/templates/<file>

    private final StorageLayout layout;

    public PreviewScratchSweeper(StorageLayout layout) {
        this.layout = layout;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void sweep() {
        Path sites = layout.sites();
        if (!Files.isDirectory(sites)) {
            return; // no tenants yet (e.g. wizard mode) — nothing to sweep
        }
        int[] removed = {0};
        try (Stream<Path> walk = Files.walk(sites, SITE_TEMPLATE_DEPTH)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith(PageAuthoringValidator.SCRATCH_SLUG_PREFIX))
                    .filter(p -> p.getParent() != null
                            && "templates".equals(p.getParent().getFileName().toString()))
                    .forEach(p -> {
                        try {
                            if (Files.deleteIfExists(p)) {
                                removed[0]++;
                            }
                        } catch (IOException e) {
                            log.warn("could not delete leftover preview scratch file {}", p, e);
                        }
                    });
        } catch (IOException e) {
            log.warn("preview scratch sweep failed", e);
            return;
        }
        if (removed[0] > 0) {
            log.info("swept {} leftover preview scratch file(s) at startup", removed[0]);
        }
    }
}
