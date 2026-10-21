package mx.quest.runway.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import mx.quest.runway.platform.TestPlatformSettings;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.site.SiteStatus;
import mx.quest.runway.site.SiteStore;
import mx.quest.runway.spi.model.Realm;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.storage.StorageProperties;
import mx.quest.runway.storage.YamlStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/**
 * Login names are unique on each sign-in form. Within one store file
 * ({@link UserStore#save}), between a site's users and the tenant superadmins assigned to that site (both
 * directions: {@link SiteUserStore#save}, {@link TenantMasterStore#save}), and among the application users
 * including the pinned superadmin. Only a save that <em>introduces</em> a name is refused, so a clash already on
 * disk never blocks a password reset; and sign-in resolves such a clash in favour of the superadmin
 * ({@link SiteUserDetailsService}).
 */
class UsernameUniquenessTest {

    private record Wiring(StorageLayout layout, UserStore users, SiteUserStore siteUsers, TenantMasterStore masters,
                          SiteResolver resolver, SiteUserDetailsService login) {}

    private static Wiring wire(Path tmp) {
        StorageProperties props = new StorageProperties();
        props.setExternal(tmp);
        StorageLayout layout = StorageLayout.from(props);
        YamlStore yaml = new YamlStore();
        SiteStore sites = new SiteStore(layout, yaml);
        sites.save(new Site("acme", "Acme", List.of("acme.test"), Locale.ENGLISH, List.of(Locale.ENGLISH), null,
                SiteStatus.ACTIVE));
        sites.save(new Site("beta", "Beta", List.of("beta.test"), Locale.ENGLISH, List.of(Locale.ENGLISH), null,
                SiteStatus.ACTIVE));
        SiteResolver resolver = new SiteResolver(sites, TestPlatformSettings.empty());
        UserStore users = new UserStore(yaml);
        SiteUserStore siteUsers = new SiteUserStore(layout, users);
        TenantMasterStore masters = new TenantMasterStore(layout, users);
        return new Wiring(layout, users, siteUsers, masters, resolver,
                new SiteUserDetailsService(resolver, siteUsers, masters));
    }

    private static User siteUser(String id, String username) {
        return new User(id, username, "$argon2id$site", username, "", true, false, Realm.TENANT,
                Set.of(Roles.OPERATOR), List.of());
    }

    private static User master(String id, String username, String... sites) {
        return new User(id, username, "$argon2id$master", username, "", true, true, Realm.TENANT,
                Set.of(Roles.SUPERADMIN), List.of(sites));
    }

    private static User renamed(User user, String username) {
        return new User(user.principalId(), username, user.passwordHash(), user.displayName(), user.recoveryEmail(),
                user.enabled(), user.master(), user.realm(), user.roles(), user.sites());
    }

    // --- one file -----------------------------------------------------------------------------------

    @Test
    void aSecondUserWithTheSameNameInAnyCaseIsRefused(@TempDir Path tmp) {
        Wiring w = wire(tmp);
        w.siteUsers().save("acme", siteUser("u1", "editor"));
        for (String variant : new String[] {"editor", "Editor", "EDITOR", "  editor "}) {
            UsernameTakenException e = assertThrows(UsernameTakenException.class,
                    () -> w.siteUsers().save("acme", siteUser("u2", variant)));
            assertEquals(variant.trim(), e.username());
        }
        assertEquals(1, w.siteUsers().list("acme").size(), "nothing was written");
        // The same name on ANOTHER site is a different sign-in form: allowed.
        assertDoesNotThrow(() -> w.siteUsers().save("beta", siteUser("b1", "editor")));
    }

    @Test
    void aRenameOntoAnotherUsersNameIsRefusedButACaseChangeOfOnesOwnIsNot(@TempDir Path tmp) {
        Wiring w = wire(tmp);
        User alice = siteUser("u1", "alice");
        w.siteUsers().save("acme", alice);
        w.siteUsers().save("acme", siteUser("u2", "bob"));
        assertThrows(UsernameTakenException.class, () -> w.siteUsers().save("acme", renamed(alice, "BOB")));
        assertDoesNotThrow(() -> w.siteUsers().save("acme", renamed(alice, "Alice")));
        assertEquals("Alice", w.siteUsers().findByPrincipalId("acme", "u1").orElseThrow().username());
    }

    @Test
    void anExistingOnDiskClashDoesNotBlockAPasswordResetOfEitherAccount(@TempDir Path tmp) throws IOException {
        Wiring w = wire(tmp);
        Path file = w.layout().siteDir("acme").resolve("users.yml");
        Files.createDirectories(file.getParent());
        // As an older version could have written it: two records answering to the same name.
        new YamlStore().write(file, new UserFile(List.of(siteUser("u1", "dup"), siteUser("u2", "DUP"))));
        w.users().evict(file);
        assertDoesNotThrow(() -> w.siteUsers().save("acme", siteUser("u1", "dup").withPasswordHash("$new1")));
        assertDoesNotThrow(() -> w.siteUsers().save("acme", siteUser("u2", "DUP").withEnabled(false)));
        assertEquals("$new1", w.siteUsers().findByPrincipalId("acme", "u1").orElseThrow().passwordHash());
    }

    // --- site users against the superadmins assigned to the site ---------------------------------------

    @Test
    void aSiteUserCannotTakeTheNameOfASuperadminAssignedToTheSite(@TempDir Path tmp) {
        Wiring w = wire(tmp);
        w.masters().save(master("owner-id", "Owner", "acme"));
        w.masters().save(master("global-id", "Platform", "*"));
        for (String squat : new String[] {"owner", "OWNER", " Owner ", "platform"}) {
            assertThrows(UsernameTakenException.class, () -> w.siteUsers().save("acme", siteUser("s1", squat)),
                    squat);
        }
        assertTrue(w.siteUsers().list("acme").isEmpty());
        // On beta, "owner" signs in to nothing else — free; the "*" superadmin covers beta too.
        assertDoesNotThrow(() -> w.siteUsers().save("beta", siteUser("b1", "owner")));
        assertThrows(UsernameTakenException.class, () -> w.siteUsers().save("beta", siteUser("b2", "PLATFORM")));
    }

    @Test
    void aSuperadminCannotBeCreatedOrAssignedWhereASiteUserHoldsItsName(@TempDir Path tmp) {
        Wiring w = wire(tmp);
        w.siteUsers().save("acme", siteUser("s1", "carol"));
        assertThrows(UsernameTakenException.class, () -> w.masters().save(master("m1", "Carol", "acme")));
        assertThrows(UsernameTakenException.class, () -> w.masters().save(master("m1", "carol", "*")));

        User dave = master("m2", "carol2", "beta");
        w.masters().save(dave);
        w.siteUsers().save("acme", siteUser("s2", "carol2")); // dave is not on acme: free
        assertThrows(UsernameTakenException.class, () -> w.masters().save(master("m2", "carol2", "beta", "acme")),
                "assigning to a site where a user holds the name");
        assertThrows(UsernameTakenException.class, () -> w.masters().save(master("m2", "carol2", "*")));
        assertEquals(List.of("beta"), w.masters().findByPrincipalId("m2").orElseThrow().sites(), "unchanged");
    }

    @Test
    void twoSuperadminsCannotShareAName(@TempDir Path tmp) {
        Wiring w = wire(tmp);
        w.masters().save(master("m1", "boss", "acme"));
        assertThrows(UsernameTakenException.class, () -> w.masters().save(master("m2", "BOSS", "beta")));
    }

    @Test
    void anExistingCrossFileClashDoesNotBlockTheSuperadminsPasswordReset(@TempDir Path tmp) throws IOException {
        Wiring w = wire(tmp);
        w.masters().save(master("owner-id", "owner", "acme"));
        Path file = w.layout().siteDir("acme").resolve("users.yml");
        new YamlStore().write(file, new UserFile(List.of(siteUser("s1", "Owner")))); // the beta13-era squat
        w.users().evict(file);
        assertDoesNotThrow(() -> w.masters().save(master("owner-id", "owner", "acme").withPasswordHash("$fresh")));
        assertDoesNotThrow(() -> w.masters().save(master("owner-id", "owner", "acme", "beta")),
                "a new assignment to a site WITHOUT a clash is fine");
    }

    // --- sign-in with a clash already on disk --------------------------------------------------------

    @Test
    void signInPrefersTheSuperadminAndWarnsWhenASiteUserShadowsIt(@TempDir Path tmp) throws IOException {
        Wiring w = wire(tmp);
        w.masters().save(master("owner-id", "owner", "acme"));
        Path file = w.layout().siteDir("acme").resolve("users.yml");
        new YamlStore().write(file, new UserFile(List.of(siteUser("squatter", "OWNER"))));
        w.users().evict(file);

        Logger logger = (Logger) LoggerFactory.getLogger(SiteUserDetailsService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            w.resolver().bind(w.resolver().resolve("acme.test").orElseThrow());
            RunwayUserDetails loaded = (RunwayUserDetails) w.login().loadUserByUsername("Owner");
            assertEquals("owner-id", loaded.principalId(), "the superadmin, never the shadowing site user");
            ILoggingEvent warn = appender.list.stream().filter(e -> e.getLevel() == Level.WARN).findFirst()
                    .orElseThrow(() -> new AssertionError("the clash is logged at WARN"));
            String line = warn.getFormattedMessage();
            assertTrue(line.contains("acme") && line.contains("OWNER") && line.contains("owner"), line);
        } finally {
            logger.detachAppender(appender);
            w.resolver().clear();
        }
    }

    @Test
    void signInFindsTheAssignedSuperadminEvenWhenAnUnassignedRecordSharesItsName(@TempDir Path tmp)
            throws IOException {
        Wiring w = wire(tmp);
        Path file = w.layout().external().resolve("site-users.yml");
        // A legacy duplicate: the FIRST "boss" is not on acme; the second is.
        new YamlStore().write(file, new UserFile(List.of(master("beta-boss", "boss", "beta"),
                master("acme-boss", "BOSS", "acme"))));
        w.users().evict(file);
        w.resolver().bind(w.resolver().resolve("acme.test").orElseThrow());
        try {
            assertEquals("acme-boss", ((RunwayUserDetails) w.login().loadUserByUsername("boss")).principalId());
        } finally {
            w.resolver().clear();
        }
    }

    @Test
    void aSiteUserWithoutAClashStillSignsIn(@TempDir Path tmp) {
        Wiring w = wire(tmp);
        w.masters().save(master("owner-id", "owner", "beta"));
        w.siteUsers().save("acme", siteUser("s1", "owner")); // the beta superadmin does not sign in on acme
        w.resolver().bind(w.resolver().resolve("acme.test").orElseThrow());
        try {
            assertEquals("s1", ((RunwayUserDetails) w.login().loadUserByUsername("owner")).principalId());
        } finally {
            w.resolver().clear();
        }
    }

    // --- application realm ---------------------------------------------------------------------------

    @Test
    void anApplicationUserCannotTakeThePinnedSuperadminsOrAnotherOperatorsName(@TempDir Path tmp) {
        StorageProperties props = new StorageProperties();
        props.setExternal(tmp);
        RunwaySecurityProperties security = new RunwaySecurityProperties();
        security.setAppMasterId("root-id");
        AppUserStore app = new AppUserStore(StorageLayout.from(props), new UserStore(new YamlStore()), security);
        app.save(new User("root-id", "root", "$h", "Root", "", true, true, Realm.APPLICATION,
                Set.of(Roles.SYSTEM_SUPERADMIN), List.of()));
        app.save(new User("op-1", "olive", "$h", "Olive", "", true, false, Realm.APPLICATION,
                Set.of(Roles.SYSTEM_OPERATOR), List.of()));
        assertThrows(UsernameTakenException.class, () -> app.save(new User("op-2", "ROOT", "$h", "", "", true, false,
                Realm.APPLICATION, Set.of(Roles.SYSTEM_ADMIN), List.of())));
        assertThrows(UsernameTakenException.class, () -> app.save(new User("op-3", "Olive", "$h", "", "", true,
                false, Realm.APPLICATION, Set.of(Roles.SYSTEM_OPERATOR), List.of())));
        assertDoesNotThrow(app::validate, "the store still passes its fail-closed load check");
        assertEquals(2, app.list().size());
    }
}
