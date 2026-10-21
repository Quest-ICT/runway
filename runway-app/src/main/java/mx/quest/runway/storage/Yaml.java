package mx.quest.runway.storage;

import java.io.InputStream;
import java.io.OutputStream;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * The shared YAML codec for the storage layer (Jackson 3 / {@code tools.jackson}). Reads and
 * writes immutable records to and from YAML. Two settings are deliberate:
 *
 * <ul>
 *   <li><b>The underlying stream is not auto-closed</b> ({@link StreamWriteFeature#AUTO_CLOSE_TARGET}
 *       / {@link StreamReadFeature#AUTO_CLOSE_SOURCE} disabled) — so many records can be streamed
 *       into one multi-entry archive (a ZIP backup) without the codec closing the outer stream
 *       after the first entry (the feature names moved in Jackson 3).</li>
 *   <li><b>Unknown properties are tolerated</b> ({@link DeserializationFeature#FAIL_ON_UNKNOWN_PROPERTIES}
 *       disabled) — so a newer file read by an older binary (and vice-versa) does not blow up;
 *       stores evolve additively.</li>
 *   <li><b>Absent primitives take their Java default</b> ({@link DeserializationFeature#FAIL_ON_NULL_FOR_PRIMITIVES}
 *       disabled) — a hand-written YAML may omit a primitive field (e.g. a boolean flag); it then
 *       defaults rather than failing.</li>
 * </ul>
 *
 * <p>The mapper is immutable and thread-safe (built once).
 */
public final class Yaml {

    private Yaml() {
    }

    private static final ObjectMapper MAPPER = YAMLMapper.builder()
            .disable(StreamWriteFeature.AUTO_CLOSE_TARGET)
            .disable(StreamReadFeature.AUTO_CLOSE_SOURCE)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    /** Reads one value of {@code type} from {@code in}. Does not close {@code in}. */
    public static <T> T read(InputStream in, Class<T> type) {
        return MAPPER.readValue(in, type);
    }

    /** Writes {@code value} as YAML to {@code out}. Does not close {@code out}. */
    public static void write(OutputStream out, Object value) {
        MAPPER.writeValue(out, value);
    }
}
