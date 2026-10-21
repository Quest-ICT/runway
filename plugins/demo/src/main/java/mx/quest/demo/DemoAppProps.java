package mx.quest.demo;

import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * The demo's <b>app-level</b> settings, bound from {@code external/plugin-settings/runway-demo.yml} (keys under
 * {@code runway.plugin.runway-demo.*}) — the plugin.yml {@code settings:} schema. App-level settings are PLATFORM
 * concerns and apply on <b>restart</b>; a plugin reads them through its own Spring {@code @ConfigurationProperties}
 * binding (descriptor contract), never through host machinery. Field defaults mirror the schema defaults so the
 * plugin behaves sensibly when the file is absent.
 *
 * <p>Registered by the {@code @Bean @ConfigurationProperties("runway.plugin.runway-demo")} method
 * {@code DemoPluginConfig#runwayDemoAppProps}, <b>not</b> by {@code @EnableConfigurationProperties}: that annotation
 * names the bean {@code "<prefix>-<class name>"}, which breaks the kernel's rule that every plugin bean name starts
 * with the plugin id ({@code runwayDemo…}).
 *
 * <p>The {@code analytics.write-key} is a {@code secret}-typed <i>app-level</i> setting: in the property map it is the
 * {@code ENC(...)} ciphertext the host stores, so this demo uses it as presence only. (A <i>per-site</i> secret is
 * read in clear with {@code PluginSettings.secret} — see {@link DemoAdminModel#siteTokenConfigured}.)
 */
public class DemoAppProps {

    @NestedConfigurationProperty
    private final Banner banner = new Banner();
    @NestedConfigurationProperty
    private final Cache cache = new Cache();
    @NestedConfigurationProperty
    private final Generator generator = new Generator();
    @NestedConfigurationProperty
    private final Analytics analytics = new Analytics();

    public Banner getBanner() {
        return banner;
    }

    public Cache getCache() {
        return cache;
    }

    public Generator getGenerator() {
        return generator;
    }

    public Analytics getAnalytics() {
        return analytics;
    }

    /** {@code banner.enabled} / {@code banner.text} — a site-wide chrome banner (text is verbatim/multi-line). */
    public static class Banner {
        private boolean enabled = true;
        private String text = "";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = (text == null) ? "" : text;
        }
    }

    /** {@code cache.max-entries} — a platform tuning knob (int, range-checked by the console form). */
    public static class Cache {
        private int maxEntries = 500;

        public int getMaxEntries() {
            return maxEntries;
        }

        public void setMaxEntries(int maxEntries) {
            this.maxEntries = maxEntries;
        }
    }

    /** {@code generator.label} — the value emitted in the {@code <meta name="generator">} head tag. */
    public static class Generator {
        private String label = "Runway Demo";

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = (label == null || label.isBlank()) ? "Runway Demo" : label;
        }
    }

    /** {@code analytics.write-key} — a {@code secret}; opaque ENC(...) ciphertext in the property map. */
    public static class Analytics {
        private String writeKey = "";

        public String getWriteKey() {
            return writeKey;
        }

        public void setWriteKey(String writeKey) {
            this.writeKey = (writeKey == null) ? "" : writeKey;
        }

        public boolean hasKey() {
            return !writeKey.isBlank();
        }
    }
}
