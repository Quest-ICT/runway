package mx.quest.runway.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Keeps a signed-in session honest about the account behind it. A session's principal is a <b>snapshot</b> of the
 * user record taken at login; without this filter a suspended, demoted or deleted console user would keep every
 * right they had until their session expired. On every request of the realm chain it re-reads the principal's
 * record from the store (a cached, in-memory lookup — no disk I/O on the hot path) and:
 *
 * <ul>
 *   <li><b>record gone or disabled</b> (deleted, suspended, or — for a tenant superadmin — no longer assigned to
 *       this site): the session's sign-in for <em>this realm</em> is dropped. The context is cleared and the empty
 *       context saved, which removes the realm's attribute from the {@code HttpSession}; the session itself is
 *       <em>not</em> invalidated, so a coexisting sign-in to the other realm (or another site) survives. The
 *       request continues anonymously, so a console URL redirects to the login page and a public page simply
 *       renders for a visitor.</li>
 *   <li><b>password changed</b> (the stored hash differs from the one the session signed in with — a reset by an
 *       administrator, a self-service change, or a recovery link): the sign-in is dropped exactly as above. A
 *       password change is how a user evicts whoever else holds a session on their account, so every
 *       <em>other</em> session must end. The session that changed its own password is kept by
 *       {@link #rebindIfSelf}, which the self-service paths call after saving.</li>
 *   <li><b>record otherwise changed</b> (tier, profile, site assignment): the principal is rebuilt from the
 *       current record, so a tier change takes effect on the next request rather than at the next login.</li>
 *   <li><b>unchanged</b>: nothing happens.</li>
 * </ul>
 *
 * <p>The password check needs no extra state: the session's principal already carries the hash it signed in with
 * (an opaque Argon2id PHC string — every new hash has a fresh salt, so even re-setting the same password changes
 * it). Nothing re-hashes on login, so the hash moves only when a password is set.
 *
 * <p><b>Placement.</b> Each realm chain registers it before the CSRF check and before
 * {@code PreAuthBodyLimitFilter}, so everything that asks "is this request signed in?" — the body budget first of all
 * — sees the account as it is now: a suspended user's open session gets the pre-authentication body cap on its very
 * next request, not the signed-in limit (since 1.0.0-rc1).
 *
 * <p>Only {@link RunwayUserDetails} principals are checked — the metrics bearer-token authentication and an
 * anonymous request pass through untouched. Login itself needs no help: {@link RunwayUserDetails#isEnabled()}
 * mirrors the record, so Spring's authentication provider refuses a suspended account.
 */
public final class AccountStateFilter extends OncePerRequestFilter {

    /**
     * Request attribute holding this request's {@link SessionRebinder}: set by the filter for every request of a
     * realm chain, read by {@link #rebindIfSelf}. Request-scoped, never stored in the session.
     */
    static final String REBINDER_ATTRIBUTE = AccountStateFilter.class.getName() + ".rebinder";

    /** Re-saves the realm's security context for the current request (captures the chain's own repository). */
    @FunctionalInterface
    interface SessionRebinder {
        void rebind(SecurityContext context);
    }

    private final Function<RunwayUserDetails, Optional<User>> currentRecord;
    private final SecurityContextRepository contextRepository;
    private final SecurityContextHolderStrategy holder = SecurityContextHolder.getContextHolderStrategy();

    /**
     * @param currentRecord     the realm's lookup: the CURRENT record for a session principal, or empty when it no
     *                          longer exists for this realm/scope (must never throw for a missing user)
     * @param contextRepository the realm chain's own context repository, so a change is persisted under the
     *                          realm's (and, for tenants, the site's) session key and nowhere else
     */
    public AccountStateFilter(Function<RunwayUserDetails, Optional<User>> currentRecord,
                              SecurityContextRepository contextRepository) {
        this.currentRecord = Objects.requireNonNull(currentRecord, "currentRecord");
        this.contextRepository = Objects.requireNonNull(contextRepository, "contextRepository");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        request.setAttribute(REBINDER_ATTRIBUTE,
                (SessionRebinder) context -> contextRepository.saveContext(context, request, response));
        Authentication authentication = holder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof RunwayUserDetails principal) {
            Optional<User> current = currentRecord.apply(principal)
                    .filter(user -> user.realm() == principal.realm());
            if (current.isEmpty() || !current.get().enabled()
                    || !current.get().passwordHash().equals(principal.user().passwordHash())) {
                SecurityContext empty = holder.createEmptyContext();
                holder.setContext(empty);
                contextRepository.saveContext(empty, request, response); // drops only this realm's attribute
            } else if (!current.get().equals(principal.user())) {
                SecurityContext context = rebuilt(authentication, current.get());
                holder.setContext(context);
                contextRepository.saveContext(context, request, response);
            }
        }
        chain.doFilter(request, response);
    }

    /**
     * Keeps the <b>current</b> session signed in after it changed its own password: when the request's signed-in
     * principal is {@code updated}'s identity (same principal id, same realm), its session is rebound to the new
     * record — so the next request sees a matching hash — while every other session of that account still ends
     * on its next request. Call it right after saving the record, on the request that made the change.
     *
     * <p>A no-op (returns {@code false}) when the request carries no realm chain (no rebinder), no
     * {@link RunwayUserDetails} principal, or a different identity — so it is safe to call after any password
     * save, including an administrator resetting somebody else's password.
     *
     * @param request the current request, which passed through this filter
     * @param updated the record just saved
     * @return whether the current session was rebound
     */
    public static boolean rebindIfSelf(HttpServletRequest request, User updated) {
        SecurityContextHolderStrategy strategy = SecurityContextHolder.getContextHolderStrategy();
        Authentication authentication = strategy.getContext().getAuthentication();
        if (updated == null || authentication == null
                || !(authentication.getPrincipal() instanceof RunwayUserDetails principal)
                || !principal.principalId().equals(updated.principalId()) || principal.realm() != updated.realm()
                || !(request.getAttribute(REBINDER_ATTRIBUTE) instanceof SessionRebinder rebinder)) {
            return false;
        }
        SecurityContext context = rebuilt(authentication, updated);
        strategy.setContext(context);
        rebinder.rebind(context);
        return true;
    }

    /**
     * Drops this request's sign-in for {@code self}'s realm, exactly as the filter does for a suspended account: the
     * realm's context is cleared and the empty context saved, so the realm's attribute leaves the session while the
     * session itself, and a sign-in to the other realm or another site, survives. Used when the account screen sees
     * too many wrong current passwords: the session is spent, and only a new sign-in (which the login protection
     * guards) gets back in.
     *
     * <p>A no-op (returns {@code false}) under the same conditions as {@link #rebindIfSelf}: no realm chain, no
     * {@link RunwayUserDetails} principal, or a different identity.
     *
     * @return whether the sign-in was dropped
     */
    public static boolean signOutIfSelf(HttpServletRequest request, User self) {
        SecurityContextHolderStrategy strategy = SecurityContextHolder.getContextHolderStrategy();
        Authentication authentication = strategy.getContext().getAuthentication();
        if (self == null || authentication == null
                || !(authentication.getPrincipal() instanceof RunwayUserDetails principal)
                || !principal.principalId().equals(self.principalId()) || principal.realm() != self.realm()
                || !(request.getAttribute(REBINDER_ATTRIBUTE) instanceof SessionRebinder rebinder)) {
            return false;
        }
        SecurityContext empty = strategy.createEmptyContext();
        strategy.setContext(empty);
        rebinder.rebind(empty);
        return true;
    }

    /** A fresh context whose principal is built from {@code record}, keeping the original authentication details. */
    private static SecurityContext rebuilt(Authentication authentication, User record) {
        RunwayUserDetails refreshed = new RunwayUserDetails(record);
        UsernamePasswordAuthenticationToken token = UsernamePasswordAuthenticationToken.authenticated(
                refreshed, null, refreshed.getAuthorities());
        token.setDetails(authentication.getDetails());
        SecurityContext context = SecurityContextHolder.getContextHolderStrategy().createEmptyContext();
        context.setAuthentication(token);
        return context;
    }
}
