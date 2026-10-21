package mx.quest.runway.security;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import mx.quest.runway.event.KernelEventOrder;
import mx.quest.runway.site.SiteChangedEvent;
import mx.quest.runway.storage.StoreCache;
import mx.quest.runway.storage.YamlStore;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * The realm-agnostic user-store primitive — typed read/find/list/save/delete over a
 * {@link UserFile} YAML file, built on {@link YamlStore} (atomic writes + per-file write
 * serialization), a {@link StoreCache} over hot reads, and a per-file lock that makes each
 * read-modify-write upsert atomic against concurrent writers in this process. It mirrors
 * {@link mx.quest.runway.site.SiteStore} but operates on an explicit file {@link Path} per call, so
 * one instance serves the application store, the tenant-master store, and every per-tenant store; the
 * three concrete realm stores (later slices) bind their own files.
 *
 * <p>Identity within a file is the {@link User#principalId()}: {@link #save(Path, User)} upserts by
 * principal id, and {@link #findByUsername(Path, String)} matches the login name case-insensitively. Login
 * names are unique within a file ({@link #save} refuses to introduce a duplicate); uniqueness <em>across</em>
 * files (a site user against the tenant superadmins assigned to that site) is the realm stores' check.
 * This layer does no realm or master-pin validation — that is the concrete stores' fail-closed load
 * check; here an absent file is simply an {@linkplain UserFile#empty() empty} store.
 */
@Component
public class UserStore {

    private final YamlStore yaml;
    private final StoreCache<String, UserFile> cache = new StoreCache<>(256);
    private final ConcurrentMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public UserStore(YamlStore yaml) {
        this.yaml = Objects.requireNonNull(yaml, "yaml");
    }

    /**
     * A site delete/restore rewrites that site's {@code users.yml} wholesale. This store is keyed by file path
     * and realm-agnostic, so it drops its whole cache — no stale user file (which could keep an old credential
     * alive after a restore) may survive. Rare event; the small cache re-warms on the next read.
     */
    @EventListener
    @Order(KernelEventOrder.STORES)
    public void onSiteChanged(SiteChangedEvent event) {
        if (event.isWholesaleReplacement()) {
            cache.invalidateAll();
        }
    }

    /** Every user in {@code file}, in file order (empty when the file is absent). */
    public List<User> list(Path file) {
        return cached(file).users();
    }

    /** The user in {@code file} whose login name matches {@code username} (case-insensitive), or empty. */
    public Optional<User> findByUsername(Path file, String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        String wanted = username.trim();
        return list(file).stream().filter(user -> user.username().equalsIgnoreCase(wanted)).findFirst();
    }

    /** The user in {@code file} with this stable principal id, or empty. */
    public Optional<User> findByPrincipalId(Path file, String principalId) {
        if (principalId == null) {
            return Optional.empty();
        }
        return list(file).stream().filter(user -> user.principalId().equals(principalId)).findFirst();
    }

    /**
     * Upserts {@code user} (by principal id) into {@code file} atomically, then evicts the cache.
     *
     * <p><b>Login names are unique within a file.</b> When the save <em>introduces</em> a name — a new principal,
     * or an existing one whose username changes other than in case — and another principal in the same file
     * already answers to it (case-insensitive, trimmed; {@link #findByUsername}'s match), the save is refused
     * under the file lock, so two concurrent creates of the same name cannot both land. A clash already on disk
     * does not block a save that keeps the name (a password reset or a suspend of either account).
     *
     * @throws UsernameTakenException if the save would introduce a login name another principal holds
     */
    public void save(Path file, User user) {
        Objects.requireNonNull(user, "user");
        ReentrantLock lock = lockFor(file);
        lock.lock();
        try {
            List<User> current = read(file).users();
            Optional<User> previous = current.stream()
                    .filter(existing -> existing.principalId().equals(user.principalId())).findFirst();
            if (claimsNewName(previous, user)) {
                boolean taken = current.stream()
                        .anyMatch(other -> !other.principalId().equals(user.principalId())
                                && other.username().equalsIgnoreCase(user.username()));
                if (taken) {
                    throw new UsernameTakenException(user.username());
                }
            }
            List<User> updated = new ArrayList<>();
            boolean replaced = false;
            for (User existing : current) {
                if (existing.principalId().equals(user.principalId())) {
                    updated.add(user);
                    replaced = true;
                } else {
                    updated.add(existing);
                }
            }
            if (!replaced) {
                updated.add(user);
            }
            write(file, new UserFile(updated));
        } finally {
            lock.unlock();
        }
        cache.invalidate(key(file));
    }

    /**
     * Removes the user with {@code principalId} from {@code file} atomically.
     *
     * @return {@code true} if a user was removed
     */
    public boolean delete(Path file, String principalId) {
        if (principalId == null) {
            return false;
        }
        ReentrantLock lock = lockFor(file);
        lock.lock();
        try {
            List<User> current = read(file).users();
            List<User> updated = current.stream().filter(u -> !u.principalId().equals(principalId)).toList();
            if (updated.size() == current.size()) {
                return false;
            }
            write(file, new UserFile(updated));
        } finally {
            lock.unlock();
        }
        cache.invalidate(key(file));
        return true;
    }

    /**
     * Whether saving {@code updated} over {@code previous} (the same principal's stored record, if any) gives
     * that principal a login name it did not already answer to — a new principal, or a rename that is more than
     * a change of case. Only such a save is checked for clashes, so an existing on-disk clash never blocks an
     * unrelated edit.
     */
    static boolean claimsNewName(Optional<User> previous, User updated) {
        return previous.isEmpty() || !previous.get().username().equalsIgnoreCase(updated.username());
    }

    /** Drops {@code file}'s cache entry (call when its contents change out of band). */
    public void evict(Path file) {
        cache.invalidate(key(file));
    }

    private UserFile cached(Path file) {
        return cache.get(key(file), k -> read(file));
    }

    private UserFile read(Path file) {
        try {
            return yaml.read(file, UserFile.class).orElseGet(UserFile::empty);
        } catch (IOException e) {
            throw new UncheckedIOException("reading user store " + file, e);
        }
    }

    private void write(Path file, UserFile content) {
        try {
            yaml.write(file, content);
        } catch (IOException e) {
            throw new UncheckedIOException("writing user store " + file, e);
        }
    }

    private static String key(Path file) {
        return file.toAbsolutePath().normalize().toString();
    }

    private ReentrantLock lockFor(Path file) {
        return locks.computeIfAbsent(key(file), k -> new ReentrantLock());
    }
}
