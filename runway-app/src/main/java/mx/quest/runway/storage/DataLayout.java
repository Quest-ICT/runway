package mx.quest.runway.storage;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * The <b>data-layout marker</b>: {@code <external>/layout.yml}, a one-key file ({@code version: <n>}) recording
 * which version of the on-disk layout the {@code external/} folder (and the {@code sites/} tree under it) is in.
 *
 * <p>It exists so that a later change to the layout has something to branch on. Before it, nothing on disk said
 * which Runway wrote it, so the first incompatible change could neither migrate reliably nor stop an older
 * build from writing old-shaped files into a newer tree. The contract (see
 * {@code docs/CONFIGURATION-AND-DATA-STABILITY.md}):
 *
 * <ul>
 *   <li><b>Absent</b> — the folder predates the marker (every install before it is layout {@code 1} by
 *       definition) or is brand new. The marker is written with {@link #SUPPORTED_VERSION}, when writing is
 *       allowed (see {@link #verify}).</li>
 *   <li><b>At or below {@link #SUPPORTED_VERSION}</b> — readable by this build; nothing is written. (A future
 *       build that raises the version is the one that migrates an older tree and then rewrites the marker.)</li>
 *   <li><b>Above {@link #SUPPORTED_VERSION}</b> — written by a newer Runway. This build <b>refuses to start</b>:
 *       an older kernel must never write into a layout it does not understand (downgrade protection).</li>
 *   <li><b>Unreadable / malformed</b> (not YAML, not a mapping, {@code version} missing, not a whole number — a
 *       quoted run of digits counts as one — or below 1) — refuses to start too: guessing the version of an unknown tree is exactly what the marker
 *       exists to avoid.</li>
 * </ul>
 *
 * <p>Unknown extra keys in the file are tolerated, like every other YAML store (an older build reading a marker
 * a newer one annotated still sees the version). The file is written atomically ({@link AtomicFiles}) with a
 * comment header saying what it is.
 *
 * <p>Pure and Spring-free: {@link DataLayoutInitializer} is the startup hook that calls it before anything else
 * in the application touches the folder.
 */
public final class DataLayout {

    /** The highest data-layout version this build reads and writes. Raising it is a deliberate, documented act. */
    public static final int SUPPORTED_VERSION = 1;

    /** The marker's file name, at the root of the external folder. */
    public static final String FILE_NAME = "layout.yml";

    private static final String VERSION_KEY = "version";

    private DataLayout() {
    }

    /** What {@link #verify} found (or did). */
    public enum Outcome {
        /** The marker was absent and has now been written with {@link #SUPPORTED_VERSION}. */
        WRITTEN,
        /** The marker was absent and writing was not allowed (a fresh WIZARD boot) — nothing to protect yet. */
        ABSENT_NOT_WRITTEN,
        /** The marker was absent and could not be written (logged by the caller; the next start retries). */
        ABSENT_WRITE_FAILED,
        /** The marker is present with a version this build supports. */
        SUPPORTED
    }

    /**
     * The result of a check.
     *
     * @param outcome what happened
     * @param version the layout version in force ({@link #SUPPORTED_VERSION} when absent)
     * @param marker  the marker's path
     * @param failure the write failure for {@link Outcome#ABSENT_WRITE_FAILED}, else {@code null}
     */
    public record Result(Outcome outcome, int version, Path marker, IOException failure) {
    }

    /** The marker path for an external folder. */
    public static Path markerFile(Path external) {
        return Objects.requireNonNull(external, "external").resolve(FILE_NAME);
    }

    /**
     * Checks the external folder's layout marker and, when it is absent and {@code mayWrite} is true, writes it.
     *
     * @param external the external folder root (need not exist)
     * @param mayWrite whether an absent marker may be created (creating the folder if needed)
     * @return what was found or done
     * @throws DataLayoutException if the marker declares a newer layout than {@link #SUPPORTED_VERSION} or cannot
     *                             be read as a valid marker — the caller must not start
     */
    public static Result verify(Path external, boolean mayWrite) {
        Path marker = markerFile(external);
        if (!Files.exists(marker)) {
            if (!mayWrite) {
                return new Result(Outcome.ABSENT_NOT_WRITTEN, SUPPORTED_VERSION, marker, null);
            }
            try {
                write(marker, SUPPORTED_VERSION);
                return new Result(Outcome.WRITTEN, SUPPORTED_VERSION, marker, null);
            } catch (IOException e) {
                return new Result(Outcome.ABSENT_WRITE_FAILED, SUPPORTED_VERSION, marker, e);
            }
        }
        int version = read(marker);
        if (version > SUPPORTED_VERSION) {
            throw DataLayoutException.newer(marker, version, SUPPORTED_VERSION);
        }
        return new Result(Outcome.SUPPORTED, version, marker, null);
    }

    /**
     * Reads the version a marker declares.
     *
     * @throws DataLayoutException if the file is unreadable or not a valid marker; a version too large for an
     *                             {@code int} is reported as {@linkplain DataLayoutException#newer newer}
     */
    static int read(Path marker) {
        Object raw;
        try (InputStream in = Files.newInputStream(marker)) {
            Map<?, ?> map = Yaml.read(in, Map.class);
            if (map == null) {
                throw DataLayoutException.malformed(marker, "the file is empty");
            }
            raw = map.get(VERSION_KEY);
        } catch (DataLayoutException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw DataLayoutException.malformed(marker,
                    "it is not a readable YAML mapping (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")");
        }
        if (raw == null) {
            throw DataLayoutException.malformed(marker, "it has no '" + VERSION_KEY + "' key");
        }
        BigInteger value;
        if (raw instanceof Integer || raw instanceof Long || raw instanceof Short || raw instanceof Byte) {
            value = BigInteger.valueOf(((Number) raw).longValue());
        } else if (raw instanceof BigInteger big) {
            value = big;
        } else if (raw instanceof String text && text.strip().matches("[0-9]{1,30}")) {
            value = new BigInteger(text.strip()); // a hand-quoted "1" is unambiguous; accept it
        } else {
            throw DataLayoutException.malformed(marker,
                    "'" + VERSION_KEY + "' is not a whole number (found: " + raw + ")");
        }
        if (value.signum() <= 0) {
            throw DataLayoutException.malformed(marker, "'" + VERSION_KEY + "' must be 1 or more (found: " + value + ")");
        }
        if (value.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
            throw DataLayoutException.newer(marker, Integer.MAX_VALUE, SUPPORTED_VERSION);
        }
        return value.intValue();
    }

    /** Atomically writes a marker declaring {@code version}, with a comment header saying what the file is. */
    static void write(Path marker, int version) throws IOException {
        String body = """
                # Runway data-layout marker. Written by Runway on first start; do not edit or delete it.
                # It records the version of the on-disk layout of this folder (the external/ tree and its sites/),
                # so that a Runway release which cannot read this layout refuses to start instead of writing into it.
                # See CONFIGURATION-AND-DATA-STABILITY.md in the Runway documentation.
                version: %d
                """.formatted(version);
        AtomicFiles.write(marker, body.getBytes(StandardCharsets.UTF_8));
    }
}
