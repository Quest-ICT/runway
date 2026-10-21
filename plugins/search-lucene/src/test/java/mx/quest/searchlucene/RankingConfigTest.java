package mx.quest.searchlucene;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Defensive resolution of the per-tenant ranking knobs: defaults, coercion, clamping, and garbage tolerance. */
class RankingConfigTest {

    @Test
    void emptyConfigYieldsTheHistoricalDefaults() {
        RankingConfig c = RankingConfig.defaults();
        assertTrue(c.fuzzy());
        assertEquals(RankingConfig.DEF_FUZZY_BOOST, c.fuzzyBoost());
        assertEquals(RankingConfig.DEF_TITLE_BOOST, c.titleBoost());
        assertEquals(RankingConfig.DEF_DESCRIPTION_BOOST, c.descriptionBoost());
        assertEquals(RankingConfig.DEF_TIER_PHRASE_TITLE, c.tierPhraseTitle());
        assertEquals(RankingConfig.DEF_TIER_PHRASE_BODY, c.tierPhraseBody());
        assertEquals(RankingConfig.DEF_TIER_NEAR_PHRASE, c.tierNearPhrase());
        assertEquals(RankingConfig.DEF_TIER_ALL_TERMS, c.tierAllTerms());
    }

    @Test
    void numericAndStringValuesAreBothCoerced() {
        RankingConfig c = RankingConfig.from(Map.of("titleBoost", 12.5, "descriptionBoost", "3.25"));
        assertEquals(12.5f, c.titleBoost());
        assertEquals(3.25f, c.descriptionBoost());
    }

    @Test
    void booleanFlagAcceptsBooleanAndTruthyStrings() {
        assertTrue(RankingConfig.from(Map.of("fuzzy", true)).fuzzy());
        assertFalse(RankingConfig.from(Map.of("fuzzy", false)).fuzzy());
        assertTrue(RankingConfig.from(Map.of("fuzzy", "on")).fuzzy());
        assertTrue(RankingConfig.from(Map.of("fuzzy", "1")).fuzzy());
        assertFalse(RankingConfig.from(Map.of("fuzzy", "off")).fuzzy());
    }

    @Test
    void garbageAndBlanksFallBackToDefaults() {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("titleBoost", "not-a-number");
        cfg.put("fuzzyBoost", "");
        cfg.put("tierPhraseBody", "NaN");
        RankingConfig c = RankingConfig.from(cfg);
        assertEquals(RankingConfig.DEF_TITLE_BOOST, c.titleBoost());
        assertEquals(RankingConfig.DEF_FUZZY_BOOST, c.fuzzyBoost());
        assertEquals(RankingConfig.DEF_TIER_PHRASE_BODY, c.tierPhraseBody());
    }

    @Test
    void negativeValuesAreClampedToZeroAndHugeValuesAreCapped() {
        RankingConfig c = RankingConfig.from(Map.of("titleBoost", -5.0, "tierAllTerms", 9.9e12));
        assertEquals(0f, c.titleBoost(), "negative boost clamped to 0");
        assertTrue(c.tierAllTerms() <= 1_000_001f, "a huge value is capped, not left to overflow the score math");
    }
}
