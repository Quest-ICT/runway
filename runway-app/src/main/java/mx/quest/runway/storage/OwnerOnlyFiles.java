package mx.quest.runway.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;

/**
 * Restricts a secret-bearing file to its owner. The single source of truth for the {@code chmod 600} guarantee
 * that plugin secret settings (transport credentials, API keys) rely on — so the check can't silently differ
 * between call sites.
 *
 * <p><b>Fail-secure.</b> Earlier code applied POSIX {@code rw-------} and <em>swallowed</em>
 * {@link UnsupportedOperationException} on a non-POSIX filesystem, leaving the secret with whatever default
 * (possibly world-readable) permissions the platform gave it. This helper instead escalates: POSIX first, then a
 * single owner-only ACL entry (NTFS and other {@link AclFileAttributeView} filesystems), and if <em>neither</em>
 * can be applied it throws — so the caller can refuse to persist a secret it cannot protect rather than leak it.
 */
public final class OwnerOnlyFiles {

    private OwnerOnlyFiles() {
    }

    /**
     * Restricts {@code file} to owner-only read/write.
     *
     * @throws IOException if the filesystem supports neither POSIX permissions nor ACLs (so the caller must
     *                     treat the file as unprotectable and not leave a secret in it)
     */
    public static void restrictToOwner(Path file) throws IOException {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            return;
        } catch (UnsupportedOperationException nonPosix) {
            // Not a POSIX filesystem — fall through to the ACL path (Windows/NTFS, SMB).
        }
        AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class);
        if (acl == null) {
            throw new IOException("filesystem supports neither POSIX permissions nor ACLs; "
                    + "cannot restrict the secret-bearing file " + file);
        }
        // Replace the ACL with a single allow-all entry for the owner, so no inherited group/everyone grant
        // survives — the ACL equivalent of rw-------.
        UserPrincipal owner = Files.getOwner(file);
        AclEntry ownerOnly = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                .build();
        acl.setAcl(List.of(ownerOnly));
    }
}
