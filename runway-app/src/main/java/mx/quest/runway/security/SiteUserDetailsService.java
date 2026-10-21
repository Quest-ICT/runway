package mx.quest.runway.security;

import java.util.Objects;
import java.util.Optional;
import mx.quest.runway.site.Site;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.spi.model.Realm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * The tenant realm's {@link UserDetailsService} — and the <b>load-bearing tenant-isolation point</b>.
 * Per-tenancy usernames are unique only <em>within</em> a tenancy (site A and site B may both have an
 * {@code admin}), so resolution is scoped to the <b>currently bound site</b>
 * ({@link SiteResolver#current()}): a username authenticates only against
 *
 * <ol>
 *   <li>a tenant-master ({@link TenantMasterStore}) explicitly {@linkplain User#assignedTo(String)
 *       assigned} to the bound tenancy, then</li>
 *   <li>the bound tenancy's own sub-users ({@link SiteUserStore}, scoped to that site's
 *       {@code users.yml}).</li>
 * </ol>
 *
 * <p>So site B's {@code admin} can never authenticate while site A is bound. With no site bound (an
 * application-realm host, or an unconfigured request) tenant authentication is refused outright. As
 * defense in depth, only {@link Realm#TENANT} records are accepted. This relies on the security chain
 * running <b>after</b> {@code SiteContextFilter} (the filter-order fix), so the site is bound here.
 *
 * <p><b>The superadmin wins a name clash.</b> The stores refuse to create a site user named like a superadmin
 * assigned to the site, and to assign a superadmin to a site where a user holds its name
 * ({@link UsernameTakenException}). A clash can still be on disk — an install upgraded from a version that did
 * not check, a restored backup, a hand edit. Resolving it in favour of the site user would let whoever created
 * that user sign in under the superadmin's name and lock the superadmin out; resolving it in favour of the
 * superadmin only makes the shadowing site user unreachable. So the superadmin is tried first, and a clash is
 * logged at WARN (site, both names) so the operator can delete the site user.
 */
@Service
public class SiteUserDetailsService implements UserDetailsService {

    private static final Logger log = LoggerFactory.getLogger(SiteUserDetailsService.class);

    private final SiteResolver siteResolver;
    private final SiteUserStore siteUsers;
    private final TenantMasterStore masters;

    public SiteUserDetailsService(SiteResolver siteResolver, SiteUserStore siteUsers, TenantMasterStore masters) {
        this.siteResolver = Objects.requireNonNull(siteResolver, "siteResolver");
        this.siteUsers = Objects.requireNonNull(siteUsers, "siteUsers");
        this.masters = Objects.requireNonNull(masters, "masters");
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        Site site = siteResolver.current()
                .orElseThrow(() -> new UsernameNotFoundException("no tenant bound for tenant-realm authentication"));
        String wanted = username == null ? "" : username.trim();

        // Among the superadmins ASSIGNED to this site (not merely the first record with the name).
        Optional<User> master = wanted.isEmpty() ? Optional.empty() : masters.assignedTo(site.id()).stream()
                .filter(user -> user.realm() == Realm.TENANT && user.username().equalsIgnoreCase(wanted))
                .findFirst();
        Optional<User> subUser = siteUsers.findByUsername(site.id(), username)
                .filter(user -> user.realm() == Realm.TENANT);

        if (master.isPresent()) {
            subUser.ifPresent(shadow -> log.warn("site '{}': the site user '{}' has the same login name as the "
                    + "tenant superadmin '{}'; signing in as the superadmin — delete the site user (it cannot "
                    + "sign in while the clash stands)", site.id(), shadow.username(), master.get().username()));
            return new RunwayUserDetails(master.get());
        }
        if (subUser.isPresent()) {
            return new RunwayUserDetails(subUser.get());
        }

        throw new UsernameNotFoundException("no tenant user '" + username + "' for site '" + site.id() + "'");
    }
}
