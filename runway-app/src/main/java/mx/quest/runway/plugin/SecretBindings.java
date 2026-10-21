package mx.quest.runway.plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * The {@link PluginSettingDef#secretBoundTo() secretBoundTo} rule, shared by the app-level and the per-tenant
 * settings save so the two realms cannot drift.
 *
 * <p><b>Why.</b> The form never shows a stored secret, and a blank secret field on resubmit keeps it, so nobody
 * has to retype a password to change an unrelated setting. But "may edit the settings" is not "may read the
 * secret": a user who can edit the settings and not see the password could point the SMTP host at a server they
 * run, leave the password blank, and let the plugin send them the stored password on its next login. A secret
 * declared bound to the settings that say <em>where it goes</em> is
 * therefore kept on a blank resubmit <b>only while all of them stay the same</b>; when one changes, the secret is
 * dropped and must be entered again — by someone who knows it.
 */
final class SecretBindings {

    private SecretBindings() {
    }

    /**
     * The keys of the secrets this save must drop, in schema order. A secret is dropped when all hold: it declares
     * at least one binding; its submitted field is blank or absent (a retyped secret is the new value and stays);
     * a value is stored for it now; and at least one bound key that the schema declares has a different
     * <em>effective</em> value after the save than before (value, else the declared default; booleans compared as
     * booleans, lists by their joined form, blanks as absent — so a re-save of identical values never drops
     * anything). A bound key the schema does not declare never matches.
     *
     * @param before the stored value of a key before the save ({@code null} = absent)
     * @param after  the value of a key the save is about to write ({@code null} = absent)
     */
    static List<String> toDrop(List<PluginSettingDef> schema, Map<String, String> submitted,
                               Function<String, Object> before, Function<String, Object> after) {
        Map<String, PluginSettingDef> byKey = new LinkedHashMap<>();
        schema.forEach(def -> byKey.putIfAbsent(def.key(), def));
        List<String> drop = new ArrayList<>();
        for (PluginSettingDef secret : schema) {
            List<String> bindings = secret.secretBindings();
            if (bindings.isEmpty()) {
                continue;
            }
            String typed = submitted == null ? null : submitted.get(secret.key());
            if (typed != null && !typed.isBlank()) {
                continue;
            }
            Object stored = before.apply(secret.key());
            if (stored == null || String.valueOf(stored).isBlank()) {
                continue;
            }
            for (String bound : bindings) {
                PluginSettingDef def = byKey.get(bound);
                if (def != null && !def.key().equals(secret.key())
                        && !Objects.equals(effective(def, before.apply(bound)), effective(def, after.apply(bound)))) {
                    drop.add(secret.key());
                    break;
                }
            }
        }
        return List.copyOf(drop);
    }

    private static String effective(PluginSettingDef def, Object value) {
        Object resolved = value != null ? value : def.defaultValue();
        if (SettingType.of(def.type()) == SettingType.BOOLEAN) {
            return String.valueOf(resolved != null && "true".equalsIgnoreCase(String.valueOf(resolved).trim()));
        }
        if (resolved == null) {
            return null;
        }
        String text = resolved instanceof List<?> list
                ? String.join(", ", list.stream().map(String::valueOf).map(String::trim).toList())
                : String.valueOf(resolved).trim();
        return text.isEmpty() ? null : text;
    }
}
