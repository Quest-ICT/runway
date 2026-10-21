package mx.quest.runway.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import mx.quest.runway.spi.model.Realm;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

/** {@link AccountStateFilter} in isolation: drop, refresh, pass-through — and only this realm's session key. */
class AccountStateFilterTest {

    private static final String KEY = "TEST_REALM_CONTEXT";

    private final HttpSessionSecurityContextRepository repository = repository();
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/__app");
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    private static HttpSessionSecurityContextRepository repository() {
        HttpSessionSecurityContextRepository repo = new HttpSessionSecurityContextRepository();
        repo.setSpringSecurityContextKey(KEY);
        return repo;
    }

    private static User user(boolean enabled, String role) {
        return new User("u1", "alice", "hash", "Alice", "", enabled, false, Realm.APPLICATION, Set.of(role), List.of());
    }

    private void signIn(User user) {
        RunwayUserDetails principal = new RunwayUserDetails(user);
        Authentication auth = UsernamePasswordAuthenticationToken.authenticated(principal, null,
                principal.getAuthorities());
        SecurityContext context = new SecurityContextImpl(auth);
        SecurityContextHolder.setContext(context);
        repository.saveContext(context, request, response);
        request.getSession().setAttribute("OTHER_REALM_CONTEXT", "kept");
    }

    private Authentication run(Optional<User> current) throws Exception {
        AtomicReference<Authentication> seen = new AtomicReference<>();
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                seen.set(SecurityContextHolder.getContext().getAuthentication());
            }
        };
        new AccountStateFilter(principal -> current, repository).doFilter(request, response, chain);
        return seen.get();
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void anUnchangedRecordLeavesTheAuthenticationAlone() throws Exception {
        User alice = user(true, Roles.SYSTEM_OPERATOR);
        signIn(alice);
        Authentication before = SecurityContextHolder.getContext().getAuthentication();
        assertSame(before, run(Optional.of(alice)), "same token object — nothing rebuilt");
    }

    @Test
    void aDisabledRecordSignsTheRequestOutAndRemovesOnlyThisRealmsAttribute() throws Exception {
        signIn(user(true, Roles.SYSTEM_OPERATOR));
        assertNull(run(Optional.of(user(false, Roles.SYSTEM_OPERATOR))), "the chain continues unauthenticated");
        assertNull(request.getSession().getAttribute(KEY), "this realm's context is gone from the session");
        assertEquals("kept", request.getSession().getAttribute("OTHER_REALM_CONTEXT"),
                "the other realm survives: the session itself is not invalidated");
    }

    @Test
    void aMissingRecordSignsTheRequestOut() throws Exception {
        signIn(user(true, Roles.SYSTEM_OPERATOR));
        assertNull(run(Optional.empty()));
        assertNull(request.getSession().getAttribute(KEY));
    }

    @Test
    void aRecordOfAnotherRealmIsTreatedAsMissing() throws Exception {
        signIn(user(true, Roles.SYSTEM_OPERATOR));
        User tenantTwin = new User("u1", "alice", "hash", "Alice", "", true, false, Realm.TENANT,
                Set.of(Roles.ADMIN), List.of());
        assertNull(run(Optional.of(tenantTwin)), "realms never cross, even through a refresh");
    }

    @Test
    void aChangedRecordRebuildsThePrincipalAndPersistsIt() throws Exception {
        signIn(user(true, Roles.SYSTEM_OPERATOR));
        User promoted = user(true, Roles.SYSTEM_ADMIN);
        Authentication after = run(Optional.of(promoted));
        assertNotNull(after);
        assertEquals(promoted, ((RunwayUserDetails) after.getPrincipal()).user());
        assertEquals(List.of("ROLE_" + Roles.SYSTEM_ADMIN),
                after.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList());
        SecurityContext stored = (SecurityContext) request.getSession().getAttribute(KEY);
        assertEquals(promoted, ((RunwayUserDetails) stored.getAuthentication().getPrincipal()).user(),
                "the refreshed principal is what the next request loads");
    }

    @Test
    void aNonRunwayPrincipalPassesThroughUntouched() throws Exception {
        Authentication scrape = new TestingAuthenticationToken("prometheus", null, "ROLE_METRICS_SCRAPE");
        SecurityContextHolder.setContext(new SecurityContextImpl(scrape));
        assertSame(scrape, run(Optional.empty()), "the lookup is never consulted for a foreign principal");
    }

    @Test
    void anAnonymousRequestPassesThrough() throws Exception {
        assertNull(run(Optional.empty()));
    }

    // --- a password change ends the account's other sessions -----------------------------------------------

    @Test
    void aChangedPasswordHashSignsTheRequestOutAndRemovesOnlyThisRealmsAttribute() throws Exception {
        User alice = user(true, Roles.SYSTEM_OPERATOR);
        signIn(alice);
        assertNull(run(Optional.of(alice.withPasswordHash("a-new-hash"))),
                "a session that signed in with the old password is signed out, not refreshed");
        assertNull(request.getSession().getAttribute(KEY));
        assertEquals("kept", request.getSession().getAttribute("OTHER_REALM_CONTEXT"));
    }

    /** Runs the filter with a chain that plays the controller: it saves {@code updated} and rebinds. */
    private boolean runSelfChange(User current, User updated) throws Exception {
        boolean[] rebound = new boolean[1];
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                rebound[0] = AccountStateFilter.rebindIfSelf((jakarta.servlet.http.HttpServletRequest) req, updated);
            }
        };
        new AccountStateFilter(principal -> Optional.of(current), repository).doFilter(request, response, chain);
        return rebound[0];
    }

    @Test
    void theSessionThatChangedItsOwnPasswordIsReboundAndStaysSignedIn() throws Exception {
        User alice = user(true, Roles.SYSTEM_OPERATOR);
        signIn(alice);
        User changed = alice.withPasswordHash("a-new-hash");
        assertTrue(runSelfChange(alice, changed), "rebound");
        SecurityContext stored = (SecurityContext) request.getSession().getAttribute(KEY);
        assertEquals(changed, ((RunwayUserDetails) stored.getAuthentication().getPrincipal()).user(),
                "the session now carries the new hash");
        assertEquals("kept", request.getSession().getAttribute("OTHER_REALM_CONTEXT"));

        // The next request of THIS session sees a matching hash: still signed in.
        SecurityContextHolder.setContext(stored);
        Authentication next = run(Optional.of(changed));
        assertNotNull(next, "the changing session survives its own password change");
    }

    @Test
    void rebindingIsRefusedForAnotherIdentityOrAnotherRealm() throws Exception {
        User alice = user(true, Roles.SYSTEM_OPERATOR);
        signIn(alice);
        User bob = new User("u2", "bob", "new", "Bob", "", true, false, Realm.APPLICATION,
                Set.of(Roles.SYSTEM_OPERATOR), List.of());
        assertFalse(runSelfChange(alice, bob), "an administrator resetting someone else keeps their own session as is");
        assertEquals(alice, ((RunwayUserDetails) ((SecurityContext) request.getSession().getAttribute(KEY))
                .getAuthentication().getPrincipal()).user());

        User tenantTwin = new User("u1", "alice", "new", "Alice", "", true, false, Realm.TENANT,
                Set.of(Roles.ADMIN), List.of());
        assertFalse(runSelfChange(alice, tenantTwin), "the same id in the other realm is a different identity");
    }

    @Test
    void rebindingOutsideARealmChainIsANoOp() {
        User alice = user(true, Roles.SYSTEM_OPERATOR);
        signIn(alice);
        assertFalse(AccountStateFilter.rebindIfSelf(new MockHttpServletRequest(), alice.withPasswordHash("x")),
                "no rebinder on the request: nothing to save through");
        assertFalse(AccountStateFilter.rebindIfSelf(request, null));
    }
}
