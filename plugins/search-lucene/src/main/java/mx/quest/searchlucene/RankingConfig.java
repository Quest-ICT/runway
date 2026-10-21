package mx.quest.searchlucene;

import java.util.Map;

/**
 * The per-tenant ranking tuning for one site, resolved from the plugin's per-site settings
 * ({@code sites/<id>/plugins/search-lucene.yml}, read live per query through the SPI's {@code PluginSettings}, which
 * applies the descriptor's declared defaults under the stored values). It
 * carries the ~8 knobs the {@code siteSettings:} schema exposes; each is read defensively (a missing, blank, or
 * malformed value falls back to the historical default, and every value is clamped to a sane range) so a
 * hand-edited or partial file can never produce a broken query — <b>degrade-don't-crash</b>.
 *
 * <p>All values are resolved <b>once per query</b> in {@link #from(Map)} and then used as plain immutable data by
 * the query builder — the config map is never touched during query construction.
 *
 * @param fuzzy            whether typo-tolerant {@code FuzzyQuery} clauses are added (default {@code true})
 * @param fuzzyBoost       weight of a fuzzy term match relative to an exact one (default {@code 0.4})
 * @param titleBoost       term-match boost on the title field in the any-terms tier (default {@code 8.0})
 * @param descriptionBoost term-match boost on the description field in the any-terms tier (default {@code 4.0})
 * @param tierPhraseTitle  constant score for an exact query phrase found in the title (default {@code 4000})
 * @param tierPhraseBody   constant score for an exact query phrase found in the body (default {@code 2000})
 * @param tierNearPhrase   constant score for a near (slop=1) query phrase in the body (default {@code 800})
 * @param tierAllTerms     constant score for a document containing all query terms anywhere (default {@code 300})
 */
record RankingConfig(boolean fuzzy, float fuzzyBoost, float titleBoost, float descriptionBoost,
                     float tierPhraseTitle, float tierPhraseBody, float tierNearPhrase, float tierAllTerms) {

    static final boolean DEF_FUZZY = true;
    static final float DEF_FUZZY_BOOST = 0.4f;
    static final float DEF_TITLE_BOOST = 8.0f;
    static final float DEF_DESCRIPTION_BOOST = 4.0f;
    static final float DEF_TIER_PHRASE_TITLE = 4000f;
    static final float DEF_TIER_PHRASE_BODY = 2000f;
    static final float DEF_TIER_NEAR_PHRASE = 800f;
    static final float DEF_TIER_ALL_TERMS = 300f;

    /** Upper bound applied to every boost/tier score — generous, but stops a fat-fingered value from overflowing
     *  the score math or drowning every other signal. */
    private static final float MAX = 1_000_000f;

    /** The historical defaults — used when a site has no per-tenant config at all (or an empty map). */
    static RankingConfig defaults() {
        return from(Map.of());
    }

    /**
     * Resolves the ranking config from a site's plugin config map. Each knob is read with
     * {@link #decimal}/{@link #flag}, which coerce {@link Number}/{@link String} values, ignore blanks and
     * garbage, and fall back to the default; boosts and tier scores are clamped to {@code [0, MAX]}.
     *
     * @param cfg the effective per-tenant values ({@code SettingsView.values()}; may be empty; never {@code null})
     */
    static RankingConfig from(Map<String, Object> cfg) {
        return new RankingConfig(
                flag(cfg, "fuzzy", DEF_FUZZY),
                decimal(cfg, "fuzzyBoost", DEF_FUZZY_BOOST),
                decimal(cfg, "titleBoost", DEF_TITLE_BOOST),
                decimal(cfg, "descriptionBoost", DEF_DESCRIPTION_BOOST),
                decimal(cfg, "tierPhraseTitle", DEF_TIER_PHRASE_TITLE),
                decimal(cfg, "tierPhraseBody", DEF_TIER_PHRASE_BODY),
                decimal(cfg, "tierNearPhrase", DEF_TIER_NEAR_PHRASE),
                decimal(cfg, "tierAllTerms", DEF_TIER_ALL_TERMS));
    }

    /** Reads a non-negative decimal knob, clamped to {@code [0, MAX]}; missing/blank/garbage ⇒ {@code def}. */
    private static float decimal(Map<String, Object> cfg, String key, float def) {
        Object v = cfg.get(key);
        float raw;
        if (v instanceof Number n) {
            raw = n.floatValue();
        } else if (v instanceof String s && !s.isBlank()) {
            try {
                raw = Float.parseFloat(s.trim());
            } catch (NumberFormatException ex) {
                return def;
            }
        } else {
            return def;
        }
        if (Float.isNaN(raw) || Float.isInfinite(raw)) {
            return def;
        }
        return Math.max(0f, Math.min(MAX, raw));
    }

    /** Reads a boolean knob; accepts {@link Boolean} and the strings true/on/yes/1 (case-insensitive). */
    private static boolean flag(Map<String, Object> cfg, String key, boolean def) {
        Object v = cfg.get(key);
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof String s && !s.isBlank()) {
            String t = s.trim().toLowerCase(java.util.Locale.ROOT);
            return t.equals("true") || t.equals("on") || t.equals("yes") || t.equals("1");
        }
        return def;
    }
}
