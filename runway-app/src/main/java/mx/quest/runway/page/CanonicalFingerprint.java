package mx.quest.runway.page;

import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * An order-stable, self-delimiting text form of an in-memory value — a loaded config record, a settings map — for
 * folding into a page ETag what a render will actually read.
 *
 * <p>Why not {@code toString()}: a map built by {@code Map.of}/{@code Map.copyOf} iterates in an order salted per JVM
 * run, so the same value would print differently after a restart or on another cluster node, and identical content
 * would revalidate as changed. Here every map is folded by <b>sorted key</b>, every non-list collection by sorted
 * element, a record by its components in declaration order, and every component is length-prefixed, so a {@code ;}
 * or {@code :} inside a value cannot fake a boundary.
 *
 * <p>Supported shapes: {@code null}, {@link CharSequence}, {@link Number}, {@link Boolean}, {@link Enum},
 * {@link Locale}, {@link Map}, {@link List} (in order), any other {@link Collection} (sorted), arrays of objects,
 * records (recursively). Anything else folds its {@code String.valueOf} — deterministic for value types.
 */
final class CanonicalFingerprint {

    private CanonicalFingerprint() {
    }

    /** The canonical text form of {@code value} (see the class comment). */
    static String of(Object value) {
        StringBuilder out = new StringBuilder();
        append(out, value);
        return out.toString();
    }

    /** Hex SHA-256 of {@link #of(Object)} — a fixed-size token that never carries the values themselves. */
    static String digest(Object value) {
        return sha256Hex(of(value));
    }

    /** Hex SHA-256 of {@code text} (UTF-8). */
    static String sha256Hex(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is a mandatory JDK algorithm", impossible);
        }
    }

    private static void append(StringBuilder out, Object value) {
        if (value == null) {
            out.append('-');
        } else if (value instanceof CharSequence text) {
            scalar(out, 's', text.toString());
        } else if (value instanceof Boolean || value instanceof Number) {
            scalar(out, 'n', String.valueOf(value));
        } else if (value instanceof Enum<?> constant) {
            scalar(out, 'e', constant.name());
        } else if (value instanceof Locale locale) {
            scalar(out, 'l', locale.toLanguageTag());
        } else if (value instanceof Map<?, ?> map) {
            List<String> entries = new ArrayList<>(map.size());
            map.forEach((key, entry) -> entries.add(of(key) + of(entry)));
            entries.sort(null);
            group(out, 'm', entries);
        } else if (value instanceof List<?> list) {
            List<String> items = new ArrayList<>(list.size());
            list.forEach(item -> items.add(of(item)));
            group(out, 'a', items);
        } else if (value instanceof Collection<?> collection) {
            List<String> items = new ArrayList<>(collection.size());
            collection.forEach(item -> items.add(of(item)));
            items.sort(null);
            group(out, 'c', items);
        } else if (value instanceof Object[] array) {
            append(out, java.util.Arrays.asList(array));
        } else if (value.getClass().isRecord()) {
            record(out, value);
        } else {
            scalar(out, 'o', String.valueOf(value));
        }
    }

    private static void record(StringBuilder out, Object record) {
        RecordComponent[] components = record.getClass().getRecordComponents();
        List<String> fields = new ArrayList<>(components.length + 1);
        fields.add(of(record.getClass().getName()));
        for (RecordComponent component : components) {
            Object field;
            try {
                java.lang.reflect.Method accessor = component.getAccessor();
                accessor.setAccessible(true);
                field = accessor.invoke(record);
            } catch (ReflectiveOperationException | RuntimeException unreadable) {
                // Never a failure on the request path: fold the record's own text form instead (still deterministic
                // for the value records this is used with).
                scalar(out, 'o', String.valueOf(record));
                return;
            }
            fields.add(of(component.getName()) + of(field));
        }
        group(out, 'r', fields);
    }

    private static void scalar(StringBuilder out, char kind, String text) {
        out.append(kind).append(text.length()).append(':').append(text);
    }

    private static void group(StringBuilder out, char kind, List<String> parts) {
        out.append(kind).append(parts.size()).append('{');
        for (String part : parts) {
            out.append(part.length()).append(':').append(part);
        }
        out.append('}');
    }
}
