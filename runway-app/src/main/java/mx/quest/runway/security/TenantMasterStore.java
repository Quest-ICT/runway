package mx.quest.runway.security;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import mx.quest.runway.storage.StorageLayout;
import org.springframework.stereotype.Component;

/**
 * The global tenant-superadmin store — {@code external/site-users.yml}. These are the {@code SUPERADMIN}
 * identities that fully manage one or more tenancies; they are <b>application-managed</b> (created and
 * password-reset from the application realm) but <b>tenant-operated</b> (they act inside their assigned
 * tenancies). A single identity may be {@linkplain User#assignedTo(String) assigned} to several tenancies
 * (via its {@code sites} list, or {@code "*"} for all). This is the one sanctioned bridge between the
 * realms; it never lets a tenant identity reach the application realm.
 *
 * <p>The file lives at the external root (not inside any tenant folder, not web-served): part of the
 * trusted computing base, written only by the application realm.
 */
@Component
public class TenantMasterStore {

    private static final String FILE = "site-users.yml";

    private final StorageLayout layout;
    private final UserStore users;

    public TenantMasterStore(StorageLayout layout, UserStore users) {
        this.layout = Objects.requireNonNull(layout, "layout");
        this.users = Objects.requireNonNull(users, "users");
    }

    /** The tenant-master identity with this login name (case-insensitive), or empty. */
    public Optional<User> findByUsername(String username) {
        return users.findByUsername(file(), username);
    }

    /** The tenant-master identity with this stable principal id, or empty. */
    public Optional<User> findByPrincipalId(String principalId) {
        return users.findByPrincipalId(file(), principalId);
    }

    /** Every tenant-master identity. */
    public List<User> list() {
        return users.list(file());
    }

    /** The tenant-master identities assigned to {@code siteId} (directly or via {@code "*"}). */
    public List<User> assignedTo(String siteId) {
        return list().stream().filter(master -> master.assignedTo(siteId)).toList();
    }

    /**
     * Upserts a tenant-master identity.
     *
     * <p>Login names are unique among the tenant superadmins ({@link UserStore#save}) and must not collide with a
     * user of any site the superadmin signs in to — both sign in on that site's one login form. So when the save
     * introduces the name ({@link UserStore#claimsNewName}: a new superadmin, or a rename) every site it is
     * assigned to is checked; otherwise only the sites it is newly assigned to (an {@code "*"} assignment covers
     * every site folder). An existing clash on a site it already covers does not block a password reset or a
     * suspend.
     *
     * @throws UsernameTakenException if the superadmin's name is already in use among the superadmins, or by a
     *                                user of a site this save assigns it to
     */
    public void save(User master) {
        Objects.requireNonNull(master, "master");
        Optional<User> previous = findByPrincipalId(master.principalId());
        Set<String> toCheck = coveredSites(master);
        if (!UserStore.claimsNewName(previous, master)) {
            previous.map(this::coveredSites).ifPresent(toCheck::removeAll);
        }
        for (String siteId : toCheck) {
            if (users.findByUsername(SiteUserStore.file(layout, siteId), master.username()).isPresent()) {
                throw new UsernameTakenException(master.username());
            }
        }
        users.save(file(), master);
    }

    /** The site ids {@code master} signs in to: its listed sites, or every site folder for {@code "*"}. */
    private Set<String> coveredSites(User master) {
        Set<String> covered = new LinkedHashSet<>();
        for (String site : master.sites()) {
            if ("*".equals(site)) {
                covered.addAll(siteFolders());
            } else if (mx.quest.runway.storage.PathSafety.isSafeId(site)) { // an unsafe id names no site folder
                covered.add(site);
            }
        }
        return covered;
    }

    private List<String> siteFolders() {
        Path root = layout.sites();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> children = Files.list(root)) {
            return children.filter(Files::isDirectory).map(path -> path.getFileName().toString())
                    .filter(mx.quest.runway.storage.PathSafety::isSafeId).toList();
        } catch (IOException e) {
            throw new UncheckedIOException("listing site folders under " + root, e);
        }
    }

    /** Removes a tenant-master identity. */
    public boolean delete(String principalId) {
        return users.delete(file(), principalId);
    }

    private Path file() {
        return file(layout);
    }

    /** The tenant-superadmin store file under {@code layout}. */
    static Path file(StorageLayout layout) {
        return layout.external().resolve(FILE);
    }
}
