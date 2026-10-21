package mx.quest.runway.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import mx.quest.runway.plugin.PluginDescriptor;
import mx.quest.runway.plugin.PluginSettingDef;
import mx.quest.runway.plugin.PluginSettingGroup;
import mx.quest.runway.plugin.PluginSettingsService.RenderedSetting;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * The settings-form layout: sections, advanced disclosure, dependent fields, enum selects, what "inherit"
 * currently means, the platform-off notice, and help links — and that every presentation mistake degrades to
 * the plain field instead of failing.
 */
class SettingsFormTest {

    private static final Function<String, String> NO_APP = key -> null;

    // ---- builders ---------------------------------------------------------------------------------------

    private static PluginSettingDef def(String key, String type, Object dflt) {
        return new PluginSettingDef(key, type, dflt, null, null);
    }

    private static PluginSettingDef def(String key, String type, Object dflt, String range, String group,
                                        Boolean advanced, String showWhen, String helpAnchor, String inheritsFrom,
                                        String requiresPlatform) {
        return new PluginSettingDef(key, type, dflt, "lbl." + key, range, "help." + key, group, advanced, showWhen,
                helpAnchor, inheritsFrom, requiresPlatform);
    }

    private static RenderedSetting r(PluginSettingDef def, String value) {
        return new RenderedSetting(def, value, false);
    }

    private static PluginDescriptor descriptor(List<PluginSettingGroup> groups, String helpUrl,
                                               List<PluginSettingDef> appSettings) {
        return new PluginDescriptor("p", "P", "1.0.0", null, null, null, null, null, null, appSettings, null, null,
                null, null, null, groups, helpUrl);
    }

    private static List<SettingSection> build(List<RenderedSetting> rendered, List<PluginSettingGroup> groups,
                                              String helpUrl, Function<String, String> app) {
        return SettingsForm.build(rendered, descriptor(groups, helpUrl, null), app);
    }

    private static SettingField only(List<SettingSection> sections, String key) {
        for (SettingSection section : sections) {
            for (SettingField f : section.fields()) {
                if (f.key().equals(key)) {
                    return f;
                }
            }
            for (SettingField f : section.advanced()) {
                if (f.key().equals(key)) {
                    return f;
                }
            }
        }
        throw new AssertionError("no field " + key);
    }

    private static List<String> keys(List<SettingField> fields) {
        List<String> out = new ArrayList<>();
        fields.forEach(f -> out.add(f.key()));
        return out;
    }

    // ---- sections ---------------------------------------------------------------------------------------

    @Test
    void aDescriptorWithoutPresentationKeysIsOneUntitledSectionInDeclarationOrder() {
        List<SettingSection> sections = build(List.of(r(def("b", "string", null), ""),
                r(def("a", "boolean", true), "true"), r(def("c", "int", 3), "3")), List.of(), null, NO_APP);
        assertEquals(1, sections.size());
        SettingSection only = sections.get(0);
        assertNull(only.label(), "the ungrouped section has no heading");
        assertEquals(List.of("b", "a", "c"), keys(only.fields()), "declaration order is kept");
        assertTrue(only.advanced().isEmpty());
        assertFalse(only.advancedOpen());
    }

    @Test
    void groupsRenderInDeclaredOrderAfterUngroupedFieldsAndEmptyGroupsAreSkipped() {
        List<PluginSettingGroup> groups = List.of(new PluginSettingGroup("second", "g.second", "g.second.help", null),
                new PluginSettingGroup("empty", "g.empty", null, null),
                new PluginSettingGroup("first", null, null, null));
        List<SettingSection> sections = build(List.of(
                r(def("x", "string", null, null, "first", null, null, null, null, null), ""),
                r(def("loose", "string", null), ""),
                r(def("y", "string", null, null, "second", null, null, null, null, null), ""),
                r(def("z", "string", null, null, "first", null, null, null, null, null), "")), groups, null, NO_APP);
        assertEquals(3, sections.size(), "untitled + two groups with fields; the empty group is not shown");
        assertNull(sections.get(0).label());
        assertEquals(List.of("loose"), keys(sections.get(0).fields()));
        assertEquals("g.second", sections.get(1).label());
        assertEquals("g.second.help", sections.get(1).help());
        assertEquals(List.of("y"), keys(sections.get(1).fields()));
        assertEquals("first", sections.get(2).label(), "a group without a label is headed by its id");
        assertEquals(List.of("x", "z"), keys(sections.get(2).fields()));
    }

    @Test
    void anUnknownGroupFallsBackToTheUntitledSection() {
        List<SettingSection> sections = build(List.of(
                r(def("x", "string", null, null, "nobody-declared-me", null, null, null, null, null), "")),
                List.of(), null, NO_APP);
        assertEquals(1, sections.size());
        assertNull(sections.get(0).label());
        assertEquals(List.of("x"), keys(sections.get(0).fields()));
    }

    @Test
    void noUntitledSectionWhenEveryFieldIsGrouped() {
        List<SettingSection> sections = build(List.of(
                r(def("x", "string", null, null, "g", null, null, null, null, null), "")),
                List.of(new PluginSettingGroup("g", "G", null, null)), null, NO_APP);
        assertEquals(1, sections.size());
        assertEquals("G", sections.get(0).label());
    }

    // ---- advanced ---------------------------------------------------------------------------------------

    @Test
    void advancedFieldsGoInTheDisclosureWhichStartsClosedWhileTheyHoldTheirDefaults() {
        List<SettingSection> sections = build(List.of(
                r(def("basic", "string", null), ""),
                r(def("adv1", "string", "d", null, null, true, null, null, null, null), "d"),
                r(def("adv2", "text", null, null, null, true, null, null, null, null), "")), List.of(), null, NO_APP);
        SettingSection s = sections.get(0);
        assertEquals(List.of("basic"), keys(s.fields()));
        assertEquals(List.of("adv1", "adv2"), keys(s.advanced()));
        assertFalse(s.advancedOpen(), "nothing set away from its default — keep it tucked away");
    }

    @Test
    void theDisclosureOpensWhenAnAdvancedFieldIsInEffect() {
        List<SettingSection> sections = build(List.of(
                r(def("custom", "text", null, null, null, true, null, null, null, null), "User-agent: *")),
                List.of(), null, NO_APP);
        assertTrue(sections.get(0).advancedOpen(),
                "a value that replaces behaviour (e.g. a custom robots.txt) must never be out of sight");
    }

    @Test
    void aStoredSecretAlsoOpensTheDisclosure() {
        PluginSettingDef secret = def("token", "secret", null, null, null, true, null, null, null, null);
        List<SettingSection> sections = build(List.of(new RenderedSetting(secret, "", true)), List.of(), null, NO_APP);
        assertTrue(sections.get(0).advancedOpen());
    }

    // ---- showWhen ---------------------------------------------------------------------------------------

    @Test
    void aDependentFieldIsHiddenWhileItsSwitchIsOffAndShownWhileOn() {
        PluginSettingDef gate = def("feature", "boolean", false);
        PluginSettingDef dependent = def("detail", "string", null, null, null, null, "feature", null, null, null);
        SettingField off = only(build(List.of(r(gate, "false"), r(dependent, "kept")), List.of(), null, NO_APP), "detail");
        assertEquals("feature", off.showWhen());
        assertTrue(off.hidden());
        assertEquals("kept", off.value(), "hidden is not cleared — the stored value is still in the form");
        SettingField on = only(build(List.of(r(gate, "true"), r(dependent, "")), List.of(), null, NO_APP), "detail");
        assertEquals("feature", on.showWhen());
        assertFalse(on.hidden());
    }

    @Test
    void aShowWhenThatCannotWorkIsIgnoredRatherThanHidingTheFieldForever() {
        PluginSettingDef text = def("words", "string", null);
        PluginSettingDef onMissing = def("a", "string", null, null, null, null, "no-such-key", null, null, null);
        PluginSettingDef onNonBoolean = def("b", "string", null, null, null, null, "words", null, null, null);
        PluginSettingDef onItself = def("c", "boolean", false, null, null, null, "c", null, null, null);
        List<SettingSection> sections = build(List.of(r(text, "x"), r(onMissing, ""), r(onNonBoolean, ""),
                r(onItself, "false")), List.of(), null, NO_APP);
        for (String key : List.of("a", "b", "c")) {
            SettingField f = only(sections, key);
            assertNull(f.showWhen(), key + ": no usable switch, so no dependency");
            assertFalse(f.hidden(), key + ": and never hidden");
        }
    }

    @Test
    void aShowWhenCycleDegradesToPlainVisibleFieldsInsteadOfLockingThemAway() {
        // A on B and B on A, both off: hiding both would leave no visible checkbox that could ever reveal either.
        PluginSettingDef a = def("a", "boolean", false, null, null, null, "b", null, null, null);
        PluginSettingDef b = def("b", "boolean", false, null, null, null, "a", null, null, null);
        PluginSettingDef c = def("c", "boolean", false, null, null, null, "d", null, null, null);
        PluginSettingDef d = def("d", "boolean", false, null, null, null, "e", null, null, null);
        PluginSettingDef e = def("e", "boolean", false, null, null, null, "d", null, null, null);
        List<SettingSection> sections = build(List.of(r(a, "false"), r(b, "false"), r(c, "false"), r(d, "false"),
                r(e, "false")), List.of(), null, NO_APP);
        for (String key : List.of("a", "b", "c", "d", "e")) {
            assertNull(only(sections, key).showWhen(), key + ": a looping chain is unusable");
            assertFalse(only(sections, key).hidden(), key + ": and never hides the field");
        }
    }

    @Test
    void aFieldIsHiddenWhileAnySwitchUpItsChainIsOff() {
        PluginSettingDef master = def("master", "boolean", false);
        PluginSettingDef middle = def("middle", "boolean", true, null, null, null, "master", null, null, null);
        PluginSettingDef leaf = def("leaf", "string", null, null, null, null, "middle", null, null, null);
        List<SettingSection> off = build(List.of(r(master, "false"), r(middle, "true"), r(leaf, "")), List.of(), null, NO_APP);
        assertTrue(only(off, "middle").hidden());
        assertTrue(only(off, "leaf").hidden(), "its own switch is on, but that switch sits under one that is off");
        assertEquals("middle", only(off, "leaf").showWhen(), "the row still names its own switch for the script");
        List<SettingSection> on = build(List.of(r(master, "true"), r(middle, "true"), r(leaf, "")), List.of(), null, NO_APP);
        assertFalse(only(on, "leaf").hidden());
    }

    @Test
    void theAdvancedDisclosureFollowsTheSwitchItsFieldsShare() {
        PluginSettingDef gate = def("on", "boolean", false);
        PluginSettingDef x = def("x", "int", 1, null, null, true, "on", null, null, null);
        PluginSettingDef y = def("y", "int", 2, null, null, true, "on", null, null, null);
        SettingSection off = build(List.of(r(gate, "false"), r(x, "1"), r(y, "2")), List.of(), null, NO_APP).get(0);
        assertEquals("on", off.advancedShowWhen());
        assertTrue(off.advancedHidden(), "a disclosure whose every field is hidden must not open onto nothing");
        SettingSection onNow = build(List.of(r(gate, "true"), r(x, "1"), r(y, "2")), List.of(), null, NO_APP).get(0);
        assertFalse(onNow.advancedHidden());
        PluginSettingDef free = def("z", "int", 3, null, null, true, null, null, null, null);
        SettingSection mixed = build(List.of(r(gate, "false"), r(x, "1"), r(free, "3")), List.of(), null, NO_APP).get(0);
        assertNull(mixed.advancedShowWhen(), "fields on different switches (or none) — the disclosure stays put");
        assertFalse(mixed.advancedHidden());
    }

    @Test
    void anUntouchedListDoesNotOpenTheDisclosureJustBecauseItWasSavedWithSpaces() {
        PluginSettingDef list = def("hosts", "list", "a,b", null, null, true, null, null, null, null);
        assertFalse(build(List.of(r(list, "a, b")), List.of(), null, NO_APP).get(0).advancedOpen(),
                "saved 'a, b' is the default 'a,b'");
        assertTrue(build(List.of(r(list, "a, c")), List.of(), null, NO_APP).get(0).advancedOpen());
    }

    // ---- enum select + inherit --------------------------------------------------------------------------

    @Test
    void anEnumWithARangeIsASelectWithTheCurrentValueSelected() {
        SettingField f = only(build(List.of(r(def("t", "enum", "WebPage", "WebPage, Article", null, null, null, null,
                null, null), "Article")), List.of(), null, NO_APP), "t");
        assertEquals("select", f.inputKind());
        assertEquals(List.of(new SettingOption("WebPage", false, null), new SettingOption("Article", true, null)),
                f.options());
        assertFalse(f.showsRange(), "the select already lists the choices");
    }

    @Test
    void aPipeSeparatedRangeOffersTheSameChoicesTheValidatorAccepts() {
        SettingField f = only(build(List.of(r(def("m", "enum", "strict", "strict|lenient", null, null, null, null,
                null, null), "lenient")), List.of(), null, NO_APP), "m");
        assertEquals(List.of(new SettingOption("strict", false, null), new SettingOption("lenient", true, null)),
                f.options());
    }

    @Test
    void anEnumWithoutARangeStaysAFreeTextInput() {
        SettingField f = only(build(List.of(r(def("t", "enum", null), "x")), List.of(), null, NO_APP), "t");
        assertEquals("text", f.inputKind());
        assertTrue(f.options().isEmpty());
    }

    @Test
    void aStoredValueOutsideTheRangeIsKeptAsTheSelectedFirstChoice() {
        SettingField f = only(build(List.of(r(def("t", "enum", null, "a,b", null, null, null, null, null, null),
                "legacy")), List.of(), null, NO_APP), "t");
        assertEquals(new SettingOption("legacy", true, null), f.options().get(0),
                "saving the form must not silently change a value the plugin no longer lists");
        assertEquals(3, f.options().size());
    }

    @Test
    void aBlankValueGetsABlankSelectedChoice() {
        SettingField f = only(build(List.of(r(def("t", "enum", null, "a,b", null, null, null, null, null, null), "")),
                List.of(), null, NO_APP), "t");
        assertEquals(new SettingOption("", true, null), f.options().get(0));
    }

    @Test
    void inheritFromAnAppSettingShowsTheRunningAppValue() {
        PluginSettingDef policy = def("policy", "enum", "inherit", "inherit,allow,disallow", null, null, null, null,
                "app:ai-default", null);
        SettingField f = only(build(List.of(r(policy, "inherit")), List.of(), null,
                key -> key.equals("ai-default") ? "disallow" : null), "policy");
        assertEquals(new SettingOption("inherit", true, "disallow"), f.options().get(0));
        assertNull(f.options().get(1).inherited(), "only the inherit choice carries a current value");
    }

    @Test
    void inheritFollowsASiblingThatItselfInheritsFromTheApp() {
        PluginSettingDef policy = def("policy", "enum", "inherit", "inherit,allow,disallow", null, null, null, null,
                "app:ai-default", null);
        PluginSettingDef training = def("training", "enum", "inherit", "inherit,allow,disallow", null, null, null,
                null, "policy", null);
        Function<String, String> app = key -> key.equals("ai-default") ? "allow" : null;
        List<SettingSection> chained = build(List.of(r(policy, "inherit"), r(training, "inherit")), List.of(), null, app);
        assertEquals("allow", only(chained, "training").options().get(0).inherited(), "inherit → inherit → app");
        List<SettingSection> direct = build(List.of(r(policy, "disallow"), r(training, "inherit")), List.of(), null, app);
        assertEquals("disallow", only(direct, "training").options().get(0).inherited(), "the sibling's own value");
    }

    @Test
    void anInheritChainThatLoopsOrBreaksShowsAPlainInherit() {
        PluginSettingDef a = def("a", "enum", "inherit", "inherit,x", null, null, null, null, "b", null);
        PluginSettingDef b = def("b", "enum", "inherit", "inherit,x", null, null, null, null, "a", null);
        PluginSettingDef c = def("c", "enum", "inherit", "inherit,x", null, null, null, null, "missing", null);
        PluginSettingDef d = def("d", "enum", "inherit", "inherit,x", null, null, null, null, "app:unknown", null);
        List<SettingSection> sections = build(List.of(r(a, "inherit"), r(b, "inherit"), r(c, "inherit"),
                r(d, "inherit")), List.of(), null, NO_APP);
        for (String key : List.of("a", "b", "c", "d")) {
            assertNull(only(sections, key).options().get(0).inherited(), key);
        }
    }

    // ---- requiresPlatform -------------------------------------------------------------------------------

    @Test
    void aFieldSaysSoWhenThePlatformSwitchItNeedsIsOff() {
        PluginSettingDef field = def("site.on", "boolean", false, null, null, null, null, null, null, "app.on");
        Map<String, String> app = new HashMap<>();
        app.put("app.on", "false");
        assertTrue(only(build(List.of(r(field, "true")), List.of(), null, app::get), "site.on").platformOff());
        app.put("app.on", "true");
        assertFalse(only(build(List.of(r(field, "true")), List.of(), null, app::get), "site.on").platformOff());
        assertFalse(only(build(List.of(r(field, "true")), List.of(), null, NO_APP), "site.on").platformOff(),
                "an unknown platform value is not claimed to be off");
    }

    @Test
    void theRunningAppValueComesFromTheEnvironmentElseTheDeclaredDefault() {
        PluginDescriptor d = descriptor(List.of(), null, List.of(def("ai-default", "enum", "allow"),
                def("indexnow.enabled", "boolean", false)));
        MockEnvironment env = new MockEnvironment().withProperty("runway.plugin.p.indexnow.enabled", "true");
        Function<String, String> app = SettingsForm.appValues(d, env);
        assertEquals("true", app.apply("indexnow.enabled"), "the bound (running) value wins");
        assertEquals("allow", app.apply("ai-default"), "nothing bound — the declared default");
        assertNull(app.apply("undeclared"));
    }

    @Test
    void aPlatformSecretOrUndeclaredPropertyIsNeverExposedToASitePage() {
        PluginDescriptor d = descriptor(List.of(), null, List.of(def("api.token", "secret", null)));
        MockEnvironment env = new MockEnvironment()
                .withProperty("runway.plugin.p.api.token", "s3cret")
                .withProperty("runway.plugin.p.internal.thing", "hidden");
        Function<String, String> app = SettingsForm.appValues(d, env);
        assertNull(app.apply("api.token"), "a secret is never shown as an inherited value");
        assertNull(app.apply("internal.thing"), "a key the schema does not declare is not readable through it");
        PluginSettingDef policy = def("policy", "enum", "inherit", "inherit,x", null, null, null, null,
                "app:api.token", null);
        SettingField f = only(SettingsForm.build(List.of(r(policy, "inherit")), d, app), "policy");
        assertNull(f.options().get(0).inherited(), "the form shows a plain inherit");
    }

    // ---- help links -------------------------------------------------------------------------------------

    @Test
    void helpLinksJoinAPathOrWebUrlWithAPlainAnchor() {
        assertEquals("/__plugin/p/static/help.html#robots", SettingsForm.helpLink("/__plugin/p/static/help.html", "robots"));
        assertEquals("/h.html#ai", SettingsForm.helpLink("/h.html", "#ai"), "a leading # is tolerated");
        assertEquals("https://docs.example/p#x", SettingsForm.helpLink("https://docs.example/p", "x"));
        assertNull(SettingsForm.helpLink(null, "x"), "no help page — no links");
        assertNull(SettingsForm.helpLink("/h.html", null));
    }

    @Test
    void anythingButAPlainPathOrHttpUrlYieldsNoLinkAtAll() {
        assertNull(SettingsForm.helpLink("javascript:alert(1)//", "x"));
        assertNull(SettingsForm.helpLink("JavaScript:alert(1)", "x"));
        assertNull(SettingsForm.helpLink("//evil.example/h", "x"), "protocol-relative leaves the origin");
        assertNull(SettingsForm.helpLink("/\\evil.example/h", "x"), "browsers read /\\ as protocol-relative");
        assertNull(SettingsForm.helpLink("data:text/html,hi", "x"));
        assertNull(SettingsForm.helpLink("help.html", "x"), "a relative path resolves against the console page");
        assertNull(SettingsForm.helpLink("/h.html#already", "x"));
        assertNull(SettingsForm.helpLink("/h .html", "x"));
        assertNull(SettingsForm.helpLink("/h.html", "a\"onmouseover=x"), "the anchor is a plain id or nothing");
        assertNull(SettingsForm.helpLink("/h.html", "a b"));
        assertNull(SettingsForm.helpLink("/h.html", ""));
    }

    @Test
    void fieldAndSectionLinksUseTheDescriptorsHelpUrl() {
        List<SettingSection> sections = build(List.of(
                r(def("x", "string", null, null, "g", null, null, "x-anchor", null, null), "")),
                List.of(new PluginSettingGroup("g", "G", null, "g-anchor")), "/h.html", NO_APP);
        assertEquals("/h.html#g-anchor", sections.get(0).helpLink());
        assertEquals("/h.html#x-anchor", sections.get(0).fields().get(0).helpLink());
        assertEquals("help.x", sections.get(0).fields().get(0).help());
    }
}
