package mx.quest.runway.backup;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import mx.quest.runway.plugin.ContributionGuard;
import mx.quest.runway.spi.BackupParticipant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Component;

/**
 * Collects every {@link BackupParticipant} bean — core and plugin alike — indexed by its stable archive-namespace
 * {@code key()}, so a backup writes/reads each participant's slice under a collision-free namespace. A blank or
 * duplicate key among kernel participants fails startup; a plugin participant that collides or has a blank key is
 * refused and its plugin marked degraded. {@link BackupService} drives the participants: an
 * {@code export} failure fails the backup, an {@code importInto} failure is contained under {@link #guard()}.
 * Thread-safe and immutable.
 */
@Component
public class BackupParticipantRegistry {

    private final Map<String, BackupParticipant> byKey;
    private final ContributionGuard guard;

    /** Unit-test form: every participant counts as kernel code, so a collision fails fast. */
    public BackupParticipantRegistry(List<BackupParticipant> participants) {
        this(participants, ContributionGuard.standalone());
    }

    /**
     * @param participants every {@link BackupParticipant} bean Spring discovered (possibly empty)
     * @param guard        the plugin-contribution containment policy
     * @throws IllegalStateException if a <b>kernel</b> participant has a blank key or two kernel participants
     *                               share one
     */
    @Autowired
    public BackupParticipantRegistry(List<BackupParticipant> participants, ContributionGuard guard) {
        List<BackupParticipant> ordered = new java.util.ArrayList<>(participants);
        AnnotationAwareOrderComparator.sort(ordered);
        this.guard = guard;
        this.byKey = Collections.unmodifiableMap(guard.index("BackupParticipant", ordered, BackupParticipant::key));
    }

    /** The containment policy the restore path wraps {@code importInto} with. */
    public ContributionGuard guard() {
        return guard;
    }

    /** Every registered participant, in order. */
    public Collection<BackupParticipant> all() {
        return byKey.values();
    }

    /** The participant for an archive-namespace key, if any. */
    public Optional<BackupParticipant> find(String key) {
        return Optional.ofNullable(byKey.get(key));
    }
}
