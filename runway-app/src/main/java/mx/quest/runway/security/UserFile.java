package mx.quest.runway.security;

import java.util.List;

/**
 * The on-disk shape of a user store file — a {@code users:} list of {@link User} records. A single
 * top-level record (rather than a bare list) keeps the YAML forward-compatible: store-level metadata
 * can be added later without breaking older files.
 *
 * @param users the users in this store (defensively copied, unmodifiable; {@code null} → empty)
 */
public record UserFile(List<User> users) {

    public UserFile {
        users = (users == null) ? List.of() : List.copyOf(users);
    }

    /** An empty store (the "not configured yet" state of an absent file). */
    public static UserFile empty() {
        return new UserFile(List.of());
    }
}
