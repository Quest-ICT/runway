package mx.quest.database.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NamesTest {

    @Test
    void theNamespaceIsALetterThenUpToElevenLettersOrDigitsWithNoUnderscore() {
        assertThat(Names.namespace("rw")).isEqualTo("rw");
        assertThat(Names.namespace("a12345678901")).isEqualTo("a12345678901");
        for (String bad : new String[] {"", "1rw", "r_w", "RW", "a123456789012", "rw-1", "rw;", null}) {
            assertThatThrownBy(() -> Names.namespace(bad)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void siteIdsThatFoldAlikeStillGetDistinctSchemasThroughTheGeneration() {
        List<String> ids = List.of("acme.com", "acme_com", "a.b", "a_b", "A-B", "a-b", "Shop", "shop", "x".repeat(70));
        Set<String> names = new HashSet<>();
        long gen = 1;
        for (String id : ids) {
            String name = Names.siteSchema("rw", id, gen++, 63);
            assertThat(name).matches("rw_s_[a-z0-9_]+_[0-9]+").hasSizeLessThanOrEqualTo(63);
            assertThat(Names.isSiteSchema("rw", name, 63)).isTrue();
            names.add(name);
        }
        assertThat(names).hasSize(ids.size());
        assertThat(Names.siteSchema("rw", "Shop", 7, 63)).isEqualTo("rw_s_shop_7");
    }

    @Test
    void aLongSiteIdIsCutSoTheWholeNameFitsEachEngine() {
        String id = "very-long-site-id-".repeat(10);
        assertThat(Names.siteSchema("abcdefghijkl", id, Long.MAX_VALUE, 63)).hasSize(63);
        assertThat(Names.siteSchema("abcdefghijkl", id, Long.MAX_VALUE, 64)).hasSize(64);
    }

    @Test
    void onlyThisInstallsNamesPassTheGuardBeforeDdl() {
        assertThat(Names.isOwnSchema("rw", "rw_app", 63)).isTrue();
        assertThat(Names.isOwnSchema("rw", "rw_s_acme_1", 63)).isTrue();
        assertThat(Names.isSiteSchema("rw", "rw_app", 63)).isFalse();
        for (String bad : new String[] {"rwx_app", "rw2_s_a_1", "public", "rw_s_", "rw_s_A_1", "rw_s_a-1",
                "rw_s_a;drop", "rw_other", "rw_s_" + "a".repeat(60), "mysql", "rw_app2"}) {
            assertThat(Names.isOwnSchema("rw", bad, 63)).as(bad).isFalse();
        }
        assertThatThrownBy(() -> Names.requireOwnSchema("rw", "public", 63))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void prefixesEndInADoubleUnderscoreAndLongOnesAreCutWithAHash() {
        assertThat(Names.pluginPrefix("runway-demo-database")).isEqualTo("runway_demo_database__");
        assertThat(Names.pluginPrefix("Blog")).isEqualTo("blog__");
        String longer = Names.pluginPrefix("a-really-long-plugin-identifier");
        assertThat(longer).hasSize(Names.PREFIX_MAX).endsWith("__").startsWith("a_really_long_p_");
        assertThat(Names.pluginPrefix("a-really-long-plugin-identifier-2")).isNotEqualTo(longer);
    }

    @Test
    void prefixesThatCouldOwnTheSameNameClash() {
        assertThat(Names.prefixesClash(Names.pluginPrefix("a-b"), Names.pluginPrefix("a_b"))).isTrue();
        assertThat(Names.prefixesClash(Names.pluginPrefix("Blog"), Names.pluginPrefix("blog"))).isTrue();
        assertThat(Names.prefixesClash(Names.pluginPrefix("a"), Names.pluginPrefix("a-"))).isTrue();
        assertThat(Names.prefixesClash(Names.pluginPrefix("runway"), Names.pluginPrefix("runway-demo"))).isFalse();
        assertThat(Names.isReservedPrefix(Names.pluginPrefix("rw"))).isTrue();
        assertThat(Names.isReservedPrefix(Names.pluginPrefix("rw-x"))).isTrue();
        assertThat(Names.isReservedPrefix(Names.pluginPrefix("rwx"))).isFalse();
    }
}
