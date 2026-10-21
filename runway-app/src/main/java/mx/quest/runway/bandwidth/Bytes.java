package mx.quest.runway.bandwidth;

import java.util.Locale;

/** Human-readable byte sizes (B / KB / MB / GB / TB, base-1024) for the bandwidth screens. */
public final class Bytes {

    private static final String[] UNITS = {"B", "KB", "MB", "GB", "TB", "PB"};

    private Bytes() {
    }

    /** Formats {@code bytes} with one decimal above 1 KB (e.g. {@code 1.5 MB}); {@code 0 B} for zero/negative. */
    public static String human(long bytes) {
        if (bytes <= 0) {
            return "0 B";
        }
        int unit = 0;
        double value = bytes;
        while (value >= 1024 && unit < UNITS.length - 1) {
            value /= 1024;
            unit++;
        }
        return unit == 0
                ? bytes + " B"
                : String.format(Locale.ROOT, "%.1f %s", value, UNITS[unit]);
    }
}
