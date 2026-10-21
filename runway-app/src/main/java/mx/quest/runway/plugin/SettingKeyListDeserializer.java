package mx.quest.runway.plugin;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

/**
 * Reads a list of setting keys from {@code plugin.yml} <b>without ever failing the descriptor</b> — the tolerance
 * the settings-form keys get, applied to {@link PluginSettingDef#secretBoundTo()}. Accepted shapes: a YAML list
 * ({@code [smtpHost, smtpPort]}), a single key ({@code smtpHost}), or one comma-separated string
 * ({@code "smtpHost, smtpPort"}). Blank entries and non-scalar items (a nested map or list) are dropped, entries
 * are trimmed and de-duplicated in order, and any other shape (a map, a number) reads as the empty list. A typo
 * must not stop a plugin from loading; at worst a binding is missing, which is today's behaviour.
 */
public final class SettingKeyListDeserializer extends ValueDeserializer<List<String>> {

    @Override
    public List<String> deserialize(JsonParser parser, DeserializationContext context) {
        return keys(context.readTree(parser));
    }

    @Override
    public Object getNullValue(DeserializationContext context) {
        return List.of();
    }

    static List<String> keys(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (node.isArray()) {
            for (JsonNode item : node.values()) {
                if (item != null && item.isValueNode() && !item.isNull()) {
                    add(out, item.asString());
                }
            }
        } else if (node.isString()) {
            for (String part : node.asString().split(",")) {
                add(out, part);
            }
        }
        return List.copyOf(out);
    }

    private static void add(List<String> out, String key) {
        String trimmed = key == null ? "" : key.trim();
        if (!trimmed.isEmpty() && !out.contains(trimmed)) {
            out.add(trimmed);
        }
    }
}
