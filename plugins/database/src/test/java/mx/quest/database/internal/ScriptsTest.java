package mx.quest.database.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ScriptsTest {

    @Test
    void theIndexListsVersionsInOrderStartingAtOne() {
        assertThat(Scripts.parseIndex("# scripts\nV1__init.sql\n\nV2__more.sql\n"))
                .extracting(Scripts.Entry::version).containsExactly(1, 2);
        assertThatThrownBy(() -> Scripts.parseIndex("V2__init.sql\n")).hasMessageContaining("expected V1");
        assertThatThrownBy(() -> Scripts.parseIndex("V1__a.sql\nV1__b.sql\n")).hasMessageContaining("expected V2");
        assertThatThrownBy(() -> Scripts.parseIndex("V1__../x.sql\n")).hasMessageContaining("not V<n>");
        assertThatThrownBy(() -> Scripts.parseIndex("init.sql\n")).hasMessageContaining("not V<n>");
    }

    @Test
    void theChecksumIgnoresTheBomLineEndingsAndTrailingBlanksButNotComments() {
        String base = Scripts.checksum(Scripts.normalize("CREATE TABLE x (a int);\n-- note\n"));
        byte[] crlf = "﻿CREATE TABLE x (a int);   \r\n-- note\r\n\r\n".getBytes(StandardCharsets.UTF_8);
        assertThat(Scripts.checksum(Scripts.normalize(crlf))).isEqualTo(base);
        assertThat(Scripts.checksum(Scripts.normalize("CREATE TABLE x (a int);\n-- other\n"))).isNotEqualTo(base);
    }

    @Test
    void statementsAreSplitOnlyOnTheSeparatorLine() {
        String text = Scripts.normalize("CREATE FUNCTION f() AS $$ BEGIN; END $$;\n-- runway:next\n"
                + "-- only a comment\n-- runway:next\nCREATE TABLE t (a int);\n  -- runway:next  \n");
        assertThat(Scripts.split(text)).hasSize(2).first().asString().contains("BEGIN; END");
    }

    @Test
    void statementsThatMoveTheConnectionAreRefusedBehindCommentsToo() {
        for (String bad : new String[] {"USE other", "use other;", "-- hi\nUSE other", "/* x */ SET search_path = a",
                "SET SESSION sql_mode = ''", "set local search_path to a", "/*!USE other*/",
                "/*!50000 USE other */", "SELECT set_config('search_path', 'a', false)"}) {
            assertThatThrownBy(() -> Scripts.split(Scripts.normalize(bad))).as(bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(Scripts.split("SELECT 1 -- USE x")).hasSize(1);
        assertThat(Scripts.split("UPDATE t SET used = 1")).hasSize(1);
    }
}
