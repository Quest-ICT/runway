package mx.quest.runway.platform;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import mx.quest.runway.plugin.PluginDescriptor;
import mx.quest.runway.plugin.PluginSettingDef;
import mx.quest.runway.plugin.PluginSettingGroup;

/**
 * The kernel's own console-managed settings, declared in {@code kernel-settings.yml} in the same shape as a plugin's
 * {@code settings:} so the same settings form renders them (sections, help, Advanced, show-when, ranges).
 *
 * <h2>Why</h2>
 * Every editable parameter is in the Web UI, in its corresponding section, unless strictly
 * impossible. Until 1.0.0 these keys could be set only in {@code external/application.yml} on the host.
 *
 * <h2>Where a value lives</h2>
 * The console saves to {@code settings:} in {@code external/platform.yml} ({@link PlatformSettings#settings()});
 * {@link KernelSettingsInitializer} loads it at startup <b>below</b> the host's {@code external/application.yml},
 * environment variables and command-line arguments, so a value set on the host wins and the form shows that field
 * locked ({@link HostSettings}). One home per setting. Values bind at startup, so a change
 * applies after a restart; the form says which saved values are not running yet.
 *
 * <h2>Screens</h2>
 * A group's id is {@code <screen>.<name>}: {@code server} groups make Settings → Server settings, and the
 * {@code backups}, {@code bandwidth}, {@code activity} and {@code plugins} groups are the policy card of that screen.
 */
public final class KernelSettings {

    /** The classpath schema. */
    static final String RESOURCE = "kernel-settings.yml";

    private static final PluginDescriptor SCHEMA = load();

    private KernelSettings() {
    }

    private static PluginDescriptor load() {
        try (InputStream in = KernelSettings.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing classpath resource " + RESOURCE);
            }
            return PluginDescriptor.read(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The schema as a descriptor: its groups and settings, for the shared settings form. */
    public static PluginDescriptor descriptor() {
        return SCHEMA;
    }

    /** Every declared setting, by property name. */
    public static Map<String, PluginSettingDef> all() {
        Map<String, PluginSettingDef> out = new LinkedHashMap<>();
        for (PluginSettingDef def : SCHEMA.settings()) {
            out.put(def.key(), def);
        }
        return out;
    }

    /** The settings shown on {@code screen}, in declared order; empty for an unknown screen. */
    public static List<PluginSettingDef> forScreen(String screen) {
        List<PluginSettingDef> out = new ArrayList<>();
        for (PluginSettingDef def : SCHEMA.settings()) {
            if (screenOf(def.group()).equals(screen)) {
                out.add(def);
            }
        }
        return out;
    }

    /** The screens that have settings: {@code server}, {@code backups}, {@code bandwidth}, … */
    public static List<String> screens() {
        List<String> out = new ArrayList<>();
        for (PluginSettingGroup group : SCHEMA.settingGroups()) {
            String screen = screenOf(group.id());
            if (!out.contains(screen)) {
                out.add(screen);
            }
        }
        return out;
    }

    /** The declaration of {@code key}, if the kernel declares it. */
    public static Optional<PluginSettingDef> find(String key) {
        return Optional.ofNullable(all().get(key));
    }

    static String screenOf(String groupId) {
        if (groupId == null) {
            return "";
        }
        int dot = groupId.indexOf('.');
        return dot < 0 ? groupId : groupId.substring(0, dot);
    }
}
