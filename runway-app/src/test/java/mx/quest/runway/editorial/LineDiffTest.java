package mx.quest.runway.editorial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The review screen's bounded line diff. */
class LineDiffTest {

    private static List<String> render(LineDiff.Result result) {
        List<String> out = new ArrayList<>();
        for (LineDiff.Row row : result.rows()) {
            out.add(switch (row.kind()) {
                case SAME -> " " + row.text();
                case ADDED -> "+" + row.text();
                case REMOVED -> "-" + row.text();
                case FOLD -> "~" + row.text();
            });
        }
        return out;
    }

    @Test
    void equalTextsHaveNoRows() {
        LineDiff.Result r = LineDiff.diff("a\nb", "a\nb");
        assertFalse(r.changed());
        assertTrue(r.rows().isEmpty());
        assertFalse(LineDiff.diff(null, "").changed(), "null is empty");
    }

    @Test
    void aChangedLineIsARemovalAndAnAddition() {
        assertEquals(List.of(" a", "-b", "+B", " c"), render(LineDiff.diff("a\nb\nc", "a\nB\nc")));
    }

    @Test
    void insertionsAndDeletionsAnywhere() {
        assertEquals(List.of("+x", " a", " b"), render(LineDiff.diff("a\nb", "x\na\nb")));
        assertEquals(List.of(" a", " b", "+x"), render(LineDiff.diff("a\nb", "a\nb\nx")));
        assertEquals(List.of(" a", "-b", " c"), render(LineDiff.diff("a\nb\nc", "a\nc")));
        assertEquals(List.of("+a"), render(LineDiff.diff("", "a")));
        assertEquals(List.of("-a"), render(LineDiff.diff("a", "")));
        assertEquals(List.of(" a", "-b", "+x", " c", "+y"), render(LineDiff.diff("a\nb\nc", "a\nx\nc\ny")));
    }

    @Test
    void longUnchangedRunsAreFoldedToContext() {
        StringBuilder before = new StringBuilder();
        StringBuilder after = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            before.append("l").append(i).append('\n');
            after.append(i == 10 ? "CHANGED" : "l" + i).append('\n');
        }
        List<String> rows = render(LineDiff.diff(before.toString(), after.toString()));
        // 10 unchanged before the change: 7 folded + 3 context; then -/+; then 3 context + folded rest.
        assertEquals("~7", rows.get(0));
        assertEquals(List.of(" l7", " l8", " l9", "-l10", "+CHANGED", " l11", " l12", " l13"), rows.subList(1, 9));
        assertEquals("~7", rows.get(9), "the trailing run (l14..l19 plus the final empty line) folds");
    }

    @Test
    void crlfAndLfCompareAsLines() {
        // Only the line endings differ: no line is reported added or removed (the unchanged run just folds).
        assertFalse(LineDiff.diff("a\r\nb", "a\nb").rows().stream()
                .anyMatch(r -> r.kind() == LineDiff.Kind.ADDED || r.kind() == LineDiff.Kind.REMOVED));
    }

    @Test
    void anOversizedDiffIsReportedNotComputed() {
        StringBuilder a = new StringBuilder();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 2100; i++) {
            a.append("a").append(i).append('\n');
            b.append("b").append(i).append('\n');
        }
        LineDiff.Result r = LineDiff.diff(a.toString(), b.toString());
        assertTrue(r.changed());
        assertTrue(r.tooLarge());
        assertTrue(r.rows().isEmpty());
    }
}
