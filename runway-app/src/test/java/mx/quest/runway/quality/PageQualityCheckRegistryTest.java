package mx.quest.runway.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import mx.quest.runway.spi.PageQualityCheck;
import mx.quest.runway.spi.model.Finding;
import mx.quest.runway.spi.model.PageMeta;
import mx.quest.runway.spi.model.PageView;
import mx.quest.runway.spi.model.SiteView;
import org.junit.jupiter.api.Test;

/** The registry collects checks (fail-fast on a duplicate key) and aggregates findings; the built-in flags a missing title. */
class PageQualityCheckRegistryTest {

    private static PageQualityCheck check(String key, List<Finding> findings) {
        return new PageQualityCheck() {
            @Override
            public String key() {
                return key;
            }

            @Override
            public List<Finding> inspect(PageView page) {
                return findings;
            }
        };
    }

    private static PageView pageWithTitle(String title) {
        SiteView site = new SiteView("acme", "Acme", Locale.ENGLISH, List.of(), null);
        PageMeta meta = new PageMeta("home", title, "", List.of(), Locale.ENGLISH, true, false);
        return new PageView(site, meta, Locale.ENGLISH, "https://acme.example/", "<html></html>");
    }

    @Test
    void aDuplicateKeyFailsFast() {
        assertThrows(IllegalStateException.class, () -> new PageQualityCheckRegistry(
                List.of(check("dup", List.of()), check("dup", List.of()))));
    }

    @Test
    void inspectAggregatesAcrossChecks() {
        PageQualityCheckRegistry registry = new PageQualityCheckRegistry(List.of(
                check("a", List.of(Finding.warning("w", "one"))),
                check("b", List.of())));
        assertEquals(1, registry.inspect(pageWithTitle("Home")).size());
    }

    @Test
    void theBuiltinTitleCheckFlagsAMissingTitle() {
        TitlePresenceCheck builtin = new TitlePresenceCheck();
        assertEquals(1, builtin.inspect(pageWithTitle("")).size(), "a blank title is a warning");
        assertTrue(builtin.inspect(pageWithTitle("Welcome")).isEmpty(), "a present title is fine");
    }
}
