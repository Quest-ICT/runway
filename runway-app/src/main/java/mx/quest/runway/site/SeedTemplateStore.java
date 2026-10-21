package mx.quest.runway.site;

import jakarta.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import mx.quest.runway.storage.PathSafety;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.YamlStore;
import mx.quest.runway.storage.Zips;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The application-level <b>named seed-template catalog</b> — the set of templates a newly created site can be
 * <em>born from</em>. Each template is a folder under {@code external/seed/site-templates/<id>/} holding
 * per-file <b>overrides</b> of the five starter files ({@code _master.*}, {@code index.*}); a file absent from a
 * template's folder <b>inherits the platform default</b> baked into the classpath ({@code seed/site/<name>}).
 * So a template is a (possibly partial) override of the platform baseline, and {@link #read} resolves each file
 * as override-or-default. Template metadata (display name + description) lives in the folder's
 * {@code template.yml}.
 *
 * <p>The reserved id {@value #DEFAULT_ID} is the built-in template every install ships with: it always exists,
 * its display name is localized, and it <b>cannot be deleted</b> (only reset, which reverts files to the
 * classpath default). Every other template is operator-created — via {@link #createTemplate} (copying an
 * existing one) or {@link #importZip} — and freely editable/deletable.
 *
 * <p>Security: file names are a fixed allowlist ({@link #FILES}); template ids are validated
 * {@linkplain PathSafety#requireSafeId path-safe}. Import matches only allowlisted <em>basenames</em> and writes
 * to a {@code resolveWithin}-confined path, so a crafted archive can never escape the catalog root (no zip-slip).
 * Editing a template changes only <b>future</b> sites — existing sites already own their copy (self-containment).
 *
 * <p>Backward compatibility: an {@code external/seed/site/} override from the pre-catalog single-seed screen is
 * migrated once into {@code site-templates/default/} at startup ({@link #migrateLegacySeedDir}).
 */
@Component
public class SeedTemplateStore {

    /**
     * A request the catalog refuses. {@code code} names the rule, so the console can say it in the operator's
     * language ({@code op.sitetpl.error.<code>}, with {@link #args()} as its arguments); the message stays English
     * for logs and for callers that show it as is.
     */
    public static final class Invalid extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private final String code;
        private final String[] args;

        Invalid(String code, String message, String... args) {
            super(message);
            this.code = code;
            this.args = args.clone();
        }

        Invalid(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
            this.args = new String[] {String.valueOf(cause.getMessage())};
        }

        public String code() {
            return code;
        }

        /** The values the message names (an id, a limit, a file list), in order. */
        public Object[] args() {
            return args.clone();
        }
    }

    private static final Logger log = LoggerFactory.getLogger(SeedTemplateStore.class);

    /** Classpath root of the platform-default seed files. */
    static final String CLASSPATH_ROOT = "seed/site/";
    /** Per-template metadata file (display name + description); never seeded into a site. */
    static final String META_FILE = "template.yml";
    /** The reserved, always-present, non-deletable built-in template id. */
    public static final String DEFAULT_ID = "default";
    /** Ids reserved because they are literal route segments under {@code /__app/site-templates}. */
    private static final Set<String> RESERVED_IDS = Set.of("new", "import");
    /** Per-file decompression cap for imports — seed files are tiny text; this guards against a zip bomb. */
    private static final long MAX_TEMPLATE_FILE_BYTES = 8L * 1024 * 1024;

    /**
     * The fixed, ordered set of seed files, each paired with the i18n key describing its role. The set is
     * <b>closed</b>: {@link TenantSeeder} copies exactly these and no other name may be read, saved or reset.
     *
     * <p><b>Why a fixed list and not a per-page pattern.</b> This allowlist is a security control, not just a
     * manifest: {@linkplain #importZip zip import} admits an entry only when its <em>basename</em> appears here,
     * which is what stops an uploaded archive writing arbitrary paths into the seed tree. A pattern such as
     * {@code <slug>.html} would reopen that to any name and would need slug validation plus a page-count bound
     * to get back to where a fixed list already is. It also keeps the Site-templates editor honest: every file
     * has a translated label describing its role, which only works while the set is enumerable.
     *
     * <p><b>The cost, stated plainly:</b> adding a third starter page is a code change, not configuration. That
     * is the deliberate trade — a closed set is worth more here than the convenience, and a tenant is free to
     * create as many pages as it likes <em>after</em> birth. This list governs only what a site is born with.
     */
    public static final List<SeedFile> FILES = List.of(
            new SeedFile("_master.html",   "op.sitetpl.file._master.html"),
            new SeedFile("_master.th.xml", "op.sitetpl.file._master.th.xml"),
            new SeedFile("index.html",     "op.sitetpl.file.index.html"),
            new SeedFile("index.th.xml",   "op.sitetpl.file.index.th.xml"),
            new SeedFile("index.meta.yml", "op.sitetpl.file.index.meta.yml"),
            new SeedFile("about.html",     "op.sitetpl.file.about.html"),
            new SeedFile("about.th.xml",   "op.sitetpl.file.about.th.xml"),
            new SeedFile("about.meta.yml", "op.sitetpl.file.about.meta.yml"));

    private final StorageLayout layout;
    private final YamlStore yaml;

    public SeedTemplateStore(StorageLayout layout, YamlStore yaml) {
        this.layout = layout;
        this.yaml = yaml;
    }

    /** One seed file: its {@code name} and the i18n key describing what it is. */
    public record SeedFile(String name, String descriptionKey) {}

    /** A file's state within a template: its metadata plus whether the template overrides it (else it inherits). */
    public record SeedTemplateView(String name, String descriptionKey, boolean overridden) {}

    /** A catalog entry: id, display name, description, and whether it is the reserved built-in default. */
    public record SiteTemplate(String id, String name, String description, boolean builtIn) {}

    /** On-disk {@code template.yml} shape. */
    public record TemplateMeta(String name, String description) {}

    // ---- one-time migration ------------------------------------------------

    /**
     * Moves a legacy {@code external/seed/site/} override (the pre-catalog single-seed location) into
     * {@code site-templates/default/} once, so an operator's earlier edits survive the upgrade. Best-effort: a
     * failure is logged and the platform still boots (the default template falls back to the classpath).
     */
    @PostConstruct
    void migrateLegacySeedDir() {
        Path legacy = layout.seedSiteDir();
        Path target = layout.seedTemplateDir(DEFAULT_ID);
        if (!Files.isDirectory(legacy) || Files.exists(target)) {
            return;
        }
        try {
            Files.createDirectories(target);
            int moved = 0;
            for (SeedFile f : FILES) {
                Path from = legacy.resolve(f.name());
                if (Files.isRegularFile(from)) {
                    Files.move(from, target.resolve(f.name()));
                    moved++;
                }
            }
            log.info("migrated {} legacy seed override(s) from {} to the 'default' template", moved, legacy);
        } catch (IOException e) {
            log.warn("could not migrate the legacy seed override at {} (the default template still works)", legacy, e);
        }
    }

    // ---- template catalog --------------------------------------------------

    /** The catalog: the built-in {@code default} first, then operator templates by id. */
    public List<SiteTemplate> templates() {
        List<SiteTemplate> out = new ArrayList<>();
        out.add(template(DEFAULT_ID));
        Path root = layout.seedTemplatesDir();
        if (Files.isDirectory(root)) {
            try (var stream = Files.list(root)) {
                stream.filter(Files::isDirectory)
                        .map(p -> p.getFileName().toString())
                        .filter(id -> !id.equals(DEFAULT_ID) && PathSafety.isSafeId(id))
                        .sorted(Comparator.naturalOrder())
                        .forEach(id -> out.add(template(id)));
            } catch (IOException e) {
                throw new UncheckedIOException("could not list the site-template catalog", e);
            }
        }
        return out;
    }

    /**
     * Whether {@code templateId} names an existing template ({@code default} always exists). Total by contract:
     * an unsafe id returns {@code false} (never throws), so callers — the best-effort seeder, the read handlers,
     * the create path — degrade gracefully instead of blowing up on a hand-crafted id.
     */
    public boolean templateExists(String templateId) {
        return DEFAULT_ID.equals(templateId)
                || (PathSafety.isSafeId(templateId) && Files.isDirectory(layout.seedTemplateDir(templateId)));
    }

    /** Catalog metadata for one template (name/description from its {@code template.yml}, blank for a bare default). */
    public SiteTemplate template(String templateId) {
        TemplateMeta meta = meta(templateId);
        return new SiteTemplate(templateId, meta.name(), meta.description(), DEFAULT_ID.equals(templateId));
    }

    private TemplateMeta meta(String templateId) {
        Path file = layout.seedTemplateDir(templateId).resolve(META_FILE);
        try {
            return yaml.read(file, TemplateMeta.class).orElse(new TemplateMeta(null, null));
        } catch (IOException e) {
            return new TemplateMeta(null, null);
        }
    }

    /**
     * Creates a new template {@code id} by copying every effective file from {@code copyFromId} (blank ⇒
     * {@code default}) into its folder, then writing {@code template.yml}.
     *
     * @throws IllegalArgumentException if the id is unsafe, reserved, already exists, or the source is missing
     */
    public void createTemplate(String id, String name, String description, String copyFromId) {
        String templateId = PathSafety.requireSafeId(id == null ? "" : id.trim());
        if (DEFAULT_ID.equals(templateId) || RESERVED_IDS.contains(templateId)) {
            throw new Invalid("reservedId", "reserved template id: " + templateId, templateId);
        }
        if (templateExists(templateId)) {
            throw new Invalid("exists", "a template already exists: " + templateId, templateId);
        }
        String from = (copyFromId == null || copyFromId.isBlank()) ? DEFAULT_ID : copyFromId.trim();
        if (!templateExists(from)) {
            throw new Invalid("noSource", "no such source template: " + from, from);
        }
        Path dir = layout.seedTemplateDir(templateId);
        try {
            Files.createDirectories(dir);
            for (SeedFile f : FILES) {
                Files.write(dir.resolve(f.name()), read(from, f.name()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not create template '" + templateId + "'", e);
        }
        writeMeta(templateId, name, description);
    }

    /**
     * Deletes a template and all its files.
     *
     * @throws IllegalArgumentException if it is the built-in {@code default} or does not exist
     */
    public void deleteTemplate(String templateId) {
        if (DEFAULT_ID.equals(templateId)) {
            throw new Invalid("defaultUndeletable", "the built-in default template cannot be deleted");
        }
        if (!templateExists(templateId)) {
            throw new Invalid("noSuchTemplate", "no such template: " + templateId, templateId);
        }
        deleteRecursively(layout.seedTemplateDir(templateId));
    }

    /**
     * Renames template {@code templateId} and replaces its description. The id stays: sites record the id they were
     * born from, and it names the folder. A blank name falls back to the id.
     *
     * @throws IllegalArgumentException for the built-in {@code default} (its name is the localized "Platform
     *                                  default") or an unknown template
     */
    public void updateDetails(String templateId, String name, String description) {
        if (DEFAULT_ID.equals(templateId)) {
            throw new Invalid("defaultNameFixed", "the built-in default template's name cannot be changed");
        }
        if (!templateExists(templateId)) {
            throw new Invalid("noSuchTemplate", "no such template: " + templateId, templateId);
        }
        if (name != null && name.trim().length() > MAX_NAME) {
            throw new Invalid("nameTooLong", "the name is longer than " + MAX_NAME + " characters",
                    String.valueOf(MAX_NAME));
        }
        if (description != null && description.trim().length() > MAX_DESCRIPTION) {
            throw new Invalid("descriptionTooLong",
                    "the description is longer than " + MAX_DESCRIPTION + " characters",
                    String.valueOf(MAX_DESCRIPTION));
        }
        writeMeta(templateId, name, description);
    }

    /** Longest template name accepted on edit. */
    static final int MAX_NAME = 128;
    /** Longest template description accepted on edit. */
    static final int MAX_DESCRIPTION = 1000;

    private void writeMeta(String templateId, String name, String description) {
        String display = (name == null || name.isBlank()) ? templateId : name.trim();
        String desc = (description == null) ? "" : description.trim();
        try {
            yaml.write(layout.seedTemplateDir(templateId).resolve(META_FILE), new TemplateMeta(display, desc));
        } catch (IOException e) {
            throw new UncheckedIOException("could not write metadata for template '" + templateId + "'", e);
        }
    }

    // ---- per-file operations (scoped to a template) ------------------------

    /** The list-screen view of every seed file within {@code templateId}, with its override state. */
    public List<SeedTemplateView> files(String templateId) {
        List<SeedTemplateView> out = new ArrayList<>(FILES.size());
        for (SeedFile f : FILES) {
            out.add(new SeedTemplateView(f.name(), f.descriptionKey(), isOverridden(templateId, f.name())));
        }
        return out;
    }

    /** Whether {@code name} is one of the seed files (the closed allowlist). */
    public boolean isSeedFile(String name) {
        return FILES.stream().anyMatch(f -> f.name().equals(name));
    }

    /** Whether {@code templateId} carries its own copy of {@code name} (else it inherits the platform default). */
    public boolean isOverridden(String templateId, String name) {
        require(name);
        return Files.isRegularFile(layout.seedTemplateDir(templateId).resolve(name));
    }

    /**
     * The <b>effective</b> bytes for {@code name} in {@code templateId}: the template's own copy when present,
     * else the platform default from the classpath. This is what {@link TenantSeeder} writes into a new site.
     *
     * @throws IllegalArgumentException if {@code name} is not a seed file
     * @throws UncheckedIOException     if neither the template's copy nor the classpath default can be read
     */
    public byte[] read(String templateId, String name) {
        require(name);
        Path own = layout.seedTemplateDir(templateId).resolve(name);
        try {
            if (Files.isRegularFile(own)) {
                return Files.readAllBytes(own);
            }
            return readDefault(name);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read '" + name + "' of template '" + templateId + "'", e);
        }
    }

    /** The effective content of {@code name} in {@code templateId} as UTF-8 text (for the editor). */
    public String readText(String templateId, String name) {
        return new String(read(templateId, name), StandardCharsets.UTF_8);
    }

    /** The platform default (classpath) bytes for {@code name}, ignoring every template — used by "reset". */
    public byte[] readDefault(String name) {
        require(name);
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(CLASSPATH_ROOT + name)) {
            if (in == null) {
                throw new UncheckedIOException(new IOException("seed default not on classpath: " + name));
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("could not read default seed '" + name + "'", e);
        }
    }

    /**
     * Writes {@code name} into {@code templateId}, creating its folder on first use. Content is stored verbatim
     * (UTF-8) — template whitespace is significant, so it is never trimmed. Applies to the next site created.
     *
     * @throws IllegalArgumentException if {@code name} is not a seed file
     */
    public void save(String templateId, String name, String content) {
        require(name);
        try {
            Path dir = layout.seedTemplateDir(templateId);
            Files.createDirectories(dir);
            Files.write(dir.resolve(name), content.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("could not save '" + name + "' of template '" + templateId + "'", e);
        }
    }

    /**
     * Removes {@code templateId}'s own copy of {@code name}, so it inherits the platform default again.
     *
     * @return {@code true} if a copy existed and was removed
     * @throws IllegalArgumentException if {@code name} is not a seed file
     */
    public boolean reset(String templateId, String name) {
        require(name);
        try {
            return Files.deleteIfExists(layout.seedTemplateDir(templateId).resolve(name));
        } catch (IOException e) {
            throw new UncheckedIOException("could not reset '" + name + "' of template '" + templateId + "'", e);
        }
    }

    /** Resets every file of {@code templateId} to the platform default; returns how many overrides were removed. */
    public int resetAll(String templateId) {
        int removed = 0;
        for (SeedFile f : FILES) {
            if (reset(templateId, f.name())) {
                removed++;
            }
        }
        return removed;
    }

    // ---- zip import / export ----------------------------------------------

    /**
     * Exports {@code templateId} as a zip: the five effective files plus {@code template.yml}, under a single
     * {@code <id>/} wrapping directory. The bytes round-trip through {@link #importZip}.
     */
    public byte[] exportZip(String templateId) {
        if (!templateExists(templateId)) {
            throw new Invalid("noSuchTemplate", "no such template: " + templateId, templateId);
        }
        SiteTemplate t = template(templateId);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            String prefix = templateId + "/";
            for (SeedFile f : FILES) {
                zip.putNextEntry(new ZipEntry(prefix + f.name()));
                zip.write(read(templateId, f.name()));
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry(prefix + META_FILE));
            String display = (t.name() == null || t.name().isBlank()) ? templateId : t.name();
            String desc = t.description() == null ? "" : t.description();
            zip.write(("name: " + yamlScalar(display) + "\ndescription: " + yamlScalar(desc) + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        } catch (IOException e) {
            throw new UncheckedIOException("could not export template '" + templateId + "'", e);
        }
        return bytes.toByteArray();
    }

    /**
     * Imports a template from a zip under a new {@code id}. Only allowlisted seed-file <b>basenames</b> are read
     * (any wrapping directory is ignored, {@code template.yml} and stray entries are skipped), so no archive entry
     * can escape the catalog folder. All five seed files must be present. The display name/description come from
     * the form, not the archive.
     *
     * @throws IllegalArgumentException if the id is unsafe/reserved/taken, or a required file is missing
     */
    public SiteTemplate importZip(String id, String name, String description, byte[] zipBytes) {
        String templateId = PathSafety.requireSafeId(id == null ? "" : id.trim());
        if (DEFAULT_ID.equals(templateId) || RESERVED_IDS.contains(templateId)) {
            throw new Invalid("reservedId", "reserved template id: " + templateId, templateId);
        }
        if (templateExists(templateId)) {
            throw new Invalid("exists", "a template already exists: " + templateId, templateId);
        }
        Map<String, byte[]> found = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String base = basename(entry.getName());
                if (isSeedFile(base) && !found.containsKey(base)) {
                    found.put(base, Zips.readBounded(zip, MAX_TEMPLATE_FILE_BYTES));
                }
            }
        } catch (IOException e) {
            throw new Invalid("badArchive", "could not read the template archive: " + e.getMessage(), e);
        }
        List<String> missing = FILES.stream().map(SeedFile::name).filter(n -> !found.containsKey(n)).toList();
        if (!missing.isEmpty()) {
            throw new Invalid("missingFiles", "the archive is missing required files: " + String.join(", ", missing),
                    String.join(", ", missing));
        }
        Path dir = layout.seedTemplateDir(templateId);
        try {
            Files.createDirectories(dir);
            for (SeedFile f : FILES) {
                Files.write(dir.resolve(f.name()), found.get(f.name()));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not write imported template '" + templateId + "'", e);
        }
        writeMeta(templateId, name, description);
        return template(templateId);
    }

    // ---- helpers -----------------------------------------------------------

    private void require(String name) {
        if (!isSeedFile(name)) {
            throw new Invalid("notSeedFile", "not a seed template file: " + name, name);
        }
    }

    private static String basename(String entryName) {
        String cleaned = entryName.replace('\\', '/');
        int slash = cleaned.lastIndexOf('/');
        return slash < 0 ? cleaned : cleaned.substring(slash + 1);
    }

    /** Minimal YAML scalar quoting for the exported {@code template.yml} (double-quote + escape). */
    private static String yamlScalar(String value) {
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    private static void deleteRecursively(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException("could not delete " + p, e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("could not delete template folder " + dir, e);
        }
    }
}
