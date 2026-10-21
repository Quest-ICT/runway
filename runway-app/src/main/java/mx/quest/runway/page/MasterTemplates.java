package mx.quest.runway.page;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import mx.quest.runway.storage.PathSafety;
import mx.quest.runway.storage.StorageLayout;
import org.springframework.stereotype.Component;

/**
 * Discovers and reads a tenant's <b>master templates</b> — the {@code _<name>.html} chrome files a page may
 * pull via its {@code template:}. Masters are {@code _}-prefixed, which {@link PathSafety#isSafeId} rejects
 * (and {@link PageStore#findAll} skips), so they are handled here with {@link PathSafety#resolveConfined}
 * (which permits a leading underscore while still confining traversal), never through the page store.
 *
 * <p>Error pages ({@code _404.html}, {@code _500.html} — {@code _} + the status code) live in the same
 * directory but are <b>not</b> masters, so they are filtered out of {@link #list}.
 */
@Component
public class MasterTemplates {

    private static final String TEMPLATES_DIR = "templates";
    private static final String HTML = ".html";
    /** {@code _} followed by only digits — an error-page template ({@code _404}, {@code _500}), not a master. */
    private static final Pattern ERROR_PAGE = Pattern.compile("_\\d+");

    private final StorageLayout layout;

    public MasterTemplates(StorageLayout layout) {
        this.layout = layout;
    }

    /** The tenant's master template names ({@code _master}, …), sorted; error pages excluded. */
    public List<String> list(String siteId) {
        if (!PathSafety.isSafeId(siteId)) {
            return List.of();
        }
        Path templates = layout.siteDir(siteId).resolve(TEMPLATES_DIR);
        if (!Files.isDirectory(templates)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(templates)) {
            return entries.map(p -> p.getFileName().toString())
                    .filter(name -> name.endsWith(HTML))
                    .map(name -> name.substring(0, name.length() - HTML.length()))
                    .filter(MasterTemplates::isMasterName)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("listing masters for " + siteId, e);
        }
    }

    /** The raw HTML of master {@code name} in the tenancy, or empty if the name is unsafe or absent. */
    public Optional<String> readHtml(String siteId, String name) {
        if (!PathSafety.isSafeId(siteId) || !isMasterName(name)) {
            return Optional.empty();
        }
        Path file = PathSafety.resolveConfined(layout.siteDir(siteId).resolve(TEMPLATES_DIR), name + HTML);
        try {
            return Files.isRegularFile(file)
                    ? Optional.of(Files.readString(file, StandardCharsets.UTF_8))
                    : Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException("reading master " + siteId + "/" + name, e);
        }
    }

    /** Whether {@code name} is a well-formed master name: {@code _} then a safe id, and not an error page. */
    public static boolean isMasterName(String name) {
        return name != null && name.length() > 1 && name.charAt(0) == '_'
                && PathSafety.isSafeId(name.substring(1)) && !ERROR_PAGE.matcher(name).matches();
    }
}
