package mx.quest.runway.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import mx.quest.runway.spi.model.Realm;
import org.junit.jupiter.api.Test;

/** Descriptor binding: full round-trip, unknown keys tolerated, library-only, and realm validation. */
class PluginDescriptorParseTest {

    private static PluginDescriptor read(String yaml) {
        return PluginDescriptor.read(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void aFullDescriptorBindsAndValidates() {
        PluginDescriptor d = read("""
                id: contact
                name: Contact Form
                version: 1.6.0
                mainClass: mx.quest.runway.plugins.contact.ContactPluginConfig
                enabled: true
                essential: false
                futureKey: ignored-forward-and-backward
                requires: ["mail-provider >= 2.1"]
                conflicts: ["legacy-mailer"]
                siteSettings:
                  - { key: enabled, type: boolean, default: true, label: contact.site.enabled }
                  - { key: smtp.password, type: secret, label: contact.site.pw }
                permissions:
                  - { id: settings, realm: tenant, label: contact.perm.settings, defaultRoles: [OPERATOR] }
                menu:
                  - { realm: tenant, region: menu, order: 50, label: contact.menu, path: /__admin/contact, permission: settings }
                dashboardCards:
                  - { realm: application, order: 30, fragment: contact-card, permission: settings }
                """);
        d.validate(); // must not throw

        assertEquals("contact", d.id());
        assertEquals(PluginVersion.parse("1.6.0"), d.pluginVersion());
        assertFalse(d.isLibraryOnly());
        assertTrue(d.isEnabled());
        assertFalse(d.isEssential());

        assertEquals(1, d.requirements().size());
        PluginRequirement req = d.requirements().get(0);
        assertEquals("mail-provider", req.id());
        assertEquals(PluginRequirement.Op.AT_LEAST, req.op());
        assertEquals(PluginVersion.parse("2.1"), req.version());
        assertEquals(java.util.List.of("legacy-mailer"), d.conflicts());

        assertEquals(2, d.siteSettings().size());
        assertEquals(Boolean.TRUE, d.siteSettings().get(0).defaultValue(), "the YAML 'default' key binds");
        assertEquals("secret", d.siteSettings().get(1).type());

        assertEquals(Realm.TENANT, d.permissions().get(0).realm(), "realm bound case-insensitively");
        assertEquals("settings", d.permissions().get(0).id());
        assertEquals(java.util.List.of("OPERATOR"), d.permissions().get(0).defaultRoles());
        assertEquals(Realm.TENANT, d.menu().get(0).realm());
        assertEquals(50, d.menu().get(0).order());
        assertEquals(Realm.APPLICATION, d.dashboardCards().get(0).realm());
    }

    @Test
    void anUnknownKeyIsToleratedBothDirections() {
        // A future descriptor read by today's kernel must not blow up.
        PluginDescriptor d = read("id: x\nversion: 1.0.0\nbrandNewKey: {a: 1, b: [2,3]}\n");
        assertEquals("x", d.id());
    }

    @Test
    void aMissingMainClassIsALibraryProvider() {
        PluginDescriptor lib = read("id: lucene-provider\nversion: 10.4.0\n");
        assertTrue(lib.isLibraryOnly());
        lib.validate();
        PluginDescriptor coded = read("id: y\nversion: 1.0.0\nmainClass: a.B\n");
        assertFalse(coded.isLibraryOnly());
    }

    @Test
    void absentEnabledDefaultsToEnabled() {
        assertTrue(read("id: x\nversion: 1.0.0\n").isEnabled());
        assertFalse(read("id: x\nversion: 1.0.0\nenabled: false\n").isEnabled());
    }

    @Test
    void aPermissionMissingItsRealmFailsValidation() {
        PluginDescriptor d = read("""
                id: bad
                version: 1.0.0
                permissions:
                  - { id: settings, label: x }
                """);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, d::validate);
        assertTrue(e.getMessage().contains("realm"), e.getMessage());
    }

    @Test
    void anUnsafeIdFailsValidation() {
        assertThrows(IllegalArgumentException.class, () -> read("id: ../escape\nversion: 1.0.0\n").validate());
    }

    @Test
    void widgetSettingsBindPerWidgetType() {
        PluginDescriptor d = read("""
                id: carousel-manager
                version: 1.0.0
                widgetSettings:
                  carousel:
                    - { key: interval, type: int, default: 5, label: carousel.interval, range: 1..60 }
                    - { key: slides, type: text, label: carousel.slides }
                """);
        d.validate(); // must not throw
        assertEquals(1, d.widgetSettings().size());
        java.util.List<PluginSettingDef> schema = d.widgetSettings().get("carousel");
        assertEquals(2, schema.size());
        assertEquals("interval", schema.get(0).key());
        assertEquals("int", schema.get(0).type());
        assertEquals("1..60", schema.get(0).range());
        assertEquals("text", schema.get(1).type());
    }

    @Test
    void aWidgetSettingMissingItsKeyFailsValidation() {
        PluginDescriptor d = read("""
                id: bad
                version: 1.0.0
                widgetSettings:
                  carousel:
                    - { type: int, label: x }
                """);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, d::validate);
        assertTrue(e.getMessage().contains("no key"), e.getMessage());
    }

    @Test
    void absentWidgetSettingsIsAnEmptyMap() {
        assertTrue(read("id: x\nversion: 1.0.0\n").widgetSettings().isEmpty());
    }

    @Test
    void theSettingsFormPresentationKeysBind() {
        PluginDescriptor d = read("""
                id: x
                version: 1.0.0
                helpUrl: /__plugin/x/static/help.html
                settingGroups:
                  - { id: seo, label: x.g.seo, help: x.g.seo.help, helpAnchor: seo }
                  - { id: ai, label: x.g.ai }
                settings:
                  - { key: ai-default, type: enum, default: allow, range: "allow,disallow" }
                siteSettings:
                  - { key: on, type: boolean, default: false, group: seo, help: x.h.on }
                  - { key: detail, type: string, group: seo, showWhen: on, helpAnchor: detail }
                  - { key: policy, type: enum, default: inherit, range: "inherit,allow", group: ai, inheritsFrom: "app:ai-default" }
                  - { key: custom, type: text, group: ai, advanced: true, requiresPlatform: ai-default }
                """);
        assertEquals("/__plugin/x/static/help.html", d.helpUrl());
        assertEquals(2, d.settingGroups().size());
        assertEquals(new PluginSettingGroup("seo", "x.g.seo", "x.g.seo.help", "seo"), d.settingGroups().get(0));
        assertNull(d.settingGroups().get(1).help());
        PluginSettingDef on = d.siteSettings().get(0);
        assertEquals("seo", on.group());
        assertEquals("x.h.on", on.help());
        assertFalse(on.isAdvanced());
        assertEquals("on", d.siteSettings().get(1).showWhen());
        assertEquals("detail", d.siteSettings().get(1).helpAnchor());
        assertEquals("app:ai-default", d.siteSettings().get(2).inheritsFrom());
        assertTrue(d.siteSettings().get(3).isAdvanced());
        assertEquals("ai-default", d.siteSettings().get(3).requiresPlatform());
    }

    @Test
    void aDescriptorWithoutPresentationKeysHasNoGroupsAndNoHelpUrl() {
        PluginDescriptor d = read("id: x\nversion: 1.0.0\nsiteSettings:\n  - { key: a, type: string }\n");
        assertTrue(d.settingGroups().isEmpty());
        assertNull(d.helpUrl());
        PluginSettingDef a = d.siteSettings().get(0);
        assertNull(a.group());
        assertNull(a.help());
        assertFalse(a.isAdvanced());
    }

    @Test
    void anEmptySettingGroupsItemIsDroppedNotAFailedLoad() {
        // Empty `-` items (a YAML null). A scalar such as `- ~` reaches Jackson as text and fails like any other
        // wrongly typed key would — that is a malformed descriptor, not a null.
        PluginDescriptor d = read("id: x\nversion: 1.0.0\nsettingGroups:\n  -\n  - { id: a, label: x.a }\n  -\n");
        assertEquals(1, d.settingGroups().size(), "a presentation key must never stop a plugin loading");
        assertEquals("a", d.settingGroups().get(0).id());
    }
}
