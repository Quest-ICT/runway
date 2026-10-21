package mx.quest.runway.editorial;

import java.util.ArrayList;
import java.util.List;

/**
 * A small, bounded line diff for the review screen: what a pending revision changes in a page's source, as rows of
 * kept / removed / added lines, with long runs of unchanged lines folded to a few lines of context.
 *
 * <p>Longest-common-subsequence over lines after trimming the common head and tail, so the usual review (a few
 * edited lines in a long page) costs almost nothing. The quadratic table is capped at {@value #MAX_CELLS} cells;
 * beyond that (two very different, very long files) the diff reports {@link Result#tooLarge()} and the screen
 * falls back to the side-by-side preview. Pure and thread-safe.
 */
public final class LineDiff {

    /** Upper bound on the LCS table (rows × columns) — about 16 MB of {@code int}s at most. */
    static final int MAX_CELLS = 4_000_000;
    /** Unchanged lines shown on each side of a change; longer unchanged runs are folded. */
    static final int CONTEXT = 3;

    private LineDiff() {
    }

    /** The kind of a diff row. */
    public enum Kind { SAME, REMOVED, ADDED, FOLD }

    /**
     * One row of the diff.
     *
     * @param kind the row kind ({@link Kind#FOLD} stands for {@code text} = the number of folded unchanged lines)
     * @param text the line (or the folded count)
     */
    public record Row(Kind kind, String text) {
        /** The CSS-friendly lower-case kind. */
        public String css() {
            return kind.name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /**
     * The diff of two texts.
     *
     * @param rows     the rows (empty when the texts are equal or the diff was too large)
     * @param changed  whether the texts differ at all
     * @param tooLarge whether the diff was skipped because it exceeded the size bound
     */
    public record Result(List<Row> rows, boolean changed, boolean tooLarge) {
        public Result {
            rows = List.copyOf(rows);
        }
    }

    /** Diffs {@code before} → {@code after} ({@code null} treated as empty). */
    public static Result diff(String before, String after) {
        String a = before == null ? "" : before;
        String b = after == null ? "" : after;
        if (a.equals(b)) {
            return new Result(List.of(), false, false);
        }
        String[] x = lines(a);
        String[] y = lines(b);
        int head = 0;
        while (head < x.length && head < y.length && x[head].equals(y[head])) {
            head++;
        }
        int tail = 0;
        while (tail < x.length - head && tail < y.length - head
                && x[x.length - 1 - tail].equals(y[y.length - 1 - tail])) {
            tail++;
        }
        int n = x.length - head - tail;
        int m = y.length - head - tail;
        if ((long) (n + 1) * (m + 1) > MAX_CELLS) {
            return new Result(List.of(), true, true);
        }
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                lcs[i][j] = x[head + i].equals(y[head + j]) ? lcs[i + 1][j + 1] + 1
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        List<Row> raw = new ArrayList<>(head + n + m + tail);
        for (int k = 0; k < head; k++) {
            raw.add(new Row(Kind.SAME, x[k]));
        }
        int i = 0;
        int j = 0;
        while (i < n || j < m) {
            if (i < n && j < m && x[head + i].equals(y[head + j])) {
                raw.add(new Row(Kind.SAME, x[head + i]));
                i++;
                j++;
            } else if (i < n && (j == m || lcs[i + 1][j] >= lcs[i][j + 1])) {
                raw.add(new Row(Kind.REMOVED, x[head + i])); // removals before additions, as diffs read
                i++;
            } else {
                raw.add(new Row(Kind.ADDED, y[head + j]));
                j++;
            }
        }
        for (int k = x.length - tail; k < x.length; k++) {
            raw.add(new Row(Kind.SAME, x[k]));
        }
        return new Result(fold(raw), true, false);
    }

    /** Folds runs of unchanged lines longer than twice the context into a single {@link Kind#FOLD} row. */
    private static List<Row> fold(List<Row> raw) {
        List<Row> out = new ArrayList<>();
        int k = 0;
        while (k < raw.size()) {
            if (raw.get(k).kind() != Kind.SAME) {
                out.add(raw.get(k++));
                continue;
            }
            int start = k;
            while (k < raw.size() && raw.get(k).kind() == Kind.SAME) {
                k++;
            }
            int end = k; // exclusive
            int keepHead = start == 0 ? 0 : CONTEXT;            // context after a preceding change
            int keepTail = end == raw.size() ? 0 : CONTEXT;     // context before a following change
            int length = end - start;
            if (length <= keepHead + keepTail + 1) {
                out.addAll(raw.subList(start, end));
            } else {
                out.addAll(raw.subList(start, start + keepHead));
                out.add(new Row(Kind.FOLD, Integer.toString(length - keepHead - keepTail)));
                out.addAll(raw.subList(end - keepTail, end));
            }
        }
        return out;
    }

    private static String[] lines(String text) {
        return text.isEmpty() ? new String[0] : text.split("\\R", -1);
    }
}
