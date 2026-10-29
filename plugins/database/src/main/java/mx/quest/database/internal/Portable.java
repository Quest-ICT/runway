package mx.quest.database.internal;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;

/**
 * The backup's portable column types and their encoding, the same for both products, so a backup taken on one
 * restores on the other.
 *
 * <ul>
 *   <li>Types: {@code BIGINT, INTEGER, SMALLINT, DECIMAL(p,s), DOUBLE, BOOLEAN, VARCHAR(n), TEXT, DATE, TIME,
 *       TIMESTAMP} (a wall-clock time), {@code TIMESTAMPTZ} (an instant), {@code BYTES, JSON}.</li>
 *   <li>Encoding in JSON: {@code BIGINT} and {@code DECIMAL} as strings; {@code INTEGER} and {@code SMALLINT} as
 *       numbers; {@code DOUBLE} as a number (a string for NaN and the infinities); {@code TIMESTAMPTZ} as ISO-8601
 *       UTC with {@code Z} and six fractional digits; {@code TIMESTAMP}, {@code DATE}, {@code TIME} in ISO-8601
 *       without an offset; {@code BYTES} in base64; {@code JSON} as a string holding the document; SQL NULL as
 *       {@code null} only.</li>
 *   <li>A column is read with the getter its type names, never {@code getObject} without a type, and a restored
 *       value is bound by the <b>target</b> column's type: types in the same family restore into each other
 *       (the integers; {@code VARCHAR}, {@code TEXT} and {@code JSON}; {@code TIMESTAMP} and {@code TIMESTAMPTZ},
 *       taking a wall-clock time as UTC, which is the zone both products' sessions are pinned to).</li>
 * </ul>
 */
final class Portable {

    static final String UNSUPPORTED = "UNSUPPORTED:";

    private static final DateTimeFormatter INSTANT = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'")
            .withZone(ZoneOffset.UTC);

    private Portable() {
    }

    /** The type's family: what it may restore into. */
    static String family(String type) {
        String base = base(type);
        return switch (base) {
            case "BIGINT", "INTEGER", "SMALLINT" -> "INTEGER";
            case "VARCHAR", "TEXT", "JSON" -> "TEXT";
            case "TIMESTAMP", "TIMESTAMPTZ" -> "TIMESTAMP";
            default -> base;
        };
    }

    /** The type without its parameters: {@code DECIMAL(10,2)} gives {@code DECIMAL}. */
    static String base(String type) {
        int paren = type.indexOf('(');
        return paren < 0 ? type : type.substring(0, paren);
    }

    /** Whether a backed-up column restores into a target column. */
    static boolean compatible(String archived, String target) {
        return !archived.startsWith(UNSUPPORTED) && !target.startsWith(UNSUPPORTED)
                && family(archived).equals(family(target));
    }

    static String instant(Instant value) {
        return INSTANT.format(value);
    }

    /**
     * Reads one column for a backup.
     *
     * @param zonedTimestamps whether the product reads {@code TIMESTAMPTZ} as an offset date-time (PostgreSQL) rather
     *                        than a UTC wall-clock time (MariaDB, whose sessions are pinned to UTC)
     */
    static Object read(ResultSet rs, int i, String type, boolean zonedTimestamps) throws SQLException {
        Object value = switch (base(type)) {
            case "BIGINT" -> {
                long v = rs.getLong(i);
                yield rs.wasNull() ? null : Long.toString(v);
            }
            case "INTEGER", "SMALLINT" -> {
                long v = rs.getLong(i);
                yield rs.wasNull() ? null : v;
            }
            case "DECIMAL" -> {
                BigDecimal v = rs.getBigDecimal(i);
                yield v == null ? null : v.toPlainString();
            }
            case "DOUBLE" -> {
                double v = rs.getDouble(i);
                yield rs.wasNull() ? null : Double.isFinite(v) ? (Object) v : Double.toString(v);
            }
            case "BOOLEAN" -> {
                boolean v = rs.getBoolean(i);
                yield rs.wasNull() ? null : v;
            }
            case "VARCHAR", "TEXT", "JSON" -> rs.getString(i);
            case "DATE" -> {
                LocalDate v = rs.getObject(i, LocalDate.class);
                yield v == null ? null : v.toString();
            }
            case "TIME" -> {
                LocalTime v = rs.getObject(i, LocalTime.class);
                yield v == null ? null : v.toString();
            }
            case "TIMESTAMP" -> {
                LocalDateTime v = rs.getObject(i, LocalDateTime.class);
                yield v == null ? null : v.toString();
            }
            case "TIMESTAMPTZ" -> {
                if (zonedTimestamps) {
                    OffsetDateTime v = rs.getObject(i, OffsetDateTime.class);
                    yield v == null ? null : instant(v.toInstant());
                }
                LocalDateTime v = rs.getObject(i, LocalDateTime.class);
                yield v == null ? null : instant(v.toInstant(ZoneOffset.UTC));
            }
            case "BYTES" -> {
                byte[] v = rs.getBytes(i);
                yield v == null ? null : Base64.getEncoder().encodeToString(v);
            }
            default -> throw new SQLException("column type cannot be backed up: " + type);
        };
        return value;
    }

    /**
     * Binds one restored value by the target column's type.
     *
     * @param postgres whether the target is PostgreSQL (JSON is bound untyped, instants as offset date-times)
     * @throws IllegalArgumentException when the value does not fit the type
     */
    static void bind(PreparedStatement ps, int i, String type, Object value, boolean postgres) throws SQLException {
        String base = base(type);
        if (value == null) {
            ps.setNull(i, sqlType(base));
            return;
        }
        switch (base) {
            case "BIGINT", "INTEGER", "SMALLINT" -> ps.setLong(i, integer(value));
            case "DECIMAL" -> ps.setBigDecimal(i, new BigDecimal(text(value)));
            case "DOUBLE" -> ps.setDouble(i, value instanceof Number n ? n.doubleValue()
                    : Double.parseDouble(text(value)));
            case "BOOLEAN" -> {
                if (!(value instanceof Boolean b)) {
                    throw new IllegalArgumentException("not a boolean");
                }
                ps.setBoolean(i, b);
            }
            case "VARCHAR", "TEXT" -> ps.setString(i, text(value));
            case "JSON" -> {
                if (postgres) {
                    ps.setObject(i, text(value), Types.OTHER);
                } else {
                    ps.setString(i, text(value));
                }
            }
            case "DATE" -> ps.setObject(i, LocalDate.parse(text(value)));
            case "TIME" -> ps.setObject(i, LocalTime.parse(text(value)));
            case "TIMESTAMP" -> ps.setObject(i, wallClock(text(value)));
            case "TIMESTAMPTZ" -> {
                Instant at = moment(text(value));
                if (postgres) {
                    ps.setObject(i, at.atOffset(ZoneOffset.UTC));
                } else {
                    ps.setObject(i, LocalDateTime.ofInstant(at, ZoneOffset.UTC));
                }
            }
            case "BYTES" -> ps.setBytes(i, Base64.getDecoder().decode(text(value)));
            default -> throw new IllegalArgumentException("column type cannot be restored: " + type);
        }
    }

    private static int sqlType(String base) {
        return switch (base) {
            case "BIGINT" -> Types.BIGINT;
            case "INTEGER" -> Types.INTEGER;
            case "SMALLINT" -> Types.SMALLINT;
            case "DECIMAL" -> Types.DECIMAL;
            case "DOUBLE" -> Types.DOUBLE;
            case "BOOLEAN" -> Types.BOOLEAN;
            case "DATE" -> Types.DATE;
            case "TIME" -> Types.TIME;
            case "TIMESTAMP" -> Types.TIMESTAMP;
            case "TIMESTAMPTZ" -> Types.TIMESTAMP_WITH_TIMEZONE;
            case "BYTES" -> Types.VARBINARY;
            case "JSON" -> Types.OTHER;
            default -> Types.VARCHAR;
        };
    }

    private static long integer(Object value) {
        if (value instanceof Integer || value instanceof Long || value instanceof Short) {
            return ((Number) value).longValue();
        }
        if (value instanceof BigInteger big) {
            return big.longValueExact();
        }
        if (value instanceof String s) {
            return Long.parseLong(s);
        }
        throw new IllegalArgumentException("not an integer");
    }

    private static String text(Object value) {
        if (value instanceof String s) {
            return s;
        }
        throw new IllegalArgumentException("not a string");
    }

    /** A wall-clock time; an archived instant ({@code …Z}) is taken in UTC. */
    private static LocalDateTime wallClock(String text) {
        return text.endsWith("Z") ? LocalDateTime.ofInstant(Instant.parse(text), ZoneOffset.UTC)
                : LocalDateTime.parse(text);
    }

    /** An instant; an archived wall-clock time is taken as UTC. */
    private static Instant moment(String text) {
        return text.endsWith("Z") ? Instant.parse(text) : LocalDateTime.parse(text).toInstant(ZoneOffset.UTC);
    }
}
