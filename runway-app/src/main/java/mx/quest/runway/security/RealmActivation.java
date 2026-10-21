package mx.quest.runway.security;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Enforces the realm identity invariants at startup, <b>fail-closed</b>: it runs
 * {@link AppUserStore#validate()} as the context comes up, so a tampered application store (a second
 * master, a pin mismatch, a disabled/demoted master, a stray {@code SYSTEMSUPERADMIN}, a duplicate
 * username) makes the context <b>fail to refresh</b> rather than booting open. Without this hook the
 * fail-closed guarantee is unenforced — the validator would be dead code.
 *
 * <p>Runs in every mode: before provisioning (no pin) a master-less store is valid, so this is safe
 * during the first-run WIZARD state too.
 */
@Component
public class RealmActivation {

    private static final Logger log = LoggerFactory.getLogger(RealmActivation.class);

    private final AppUserStore appUserStore;

    public RealmActivation(AppUserStore appUserStore) {
        this.appUserStore = appUserStore;
    }

    /** Validates the application identity store; a failure aborts context refresh (never boot open). */
    @PostConstruct
    public void validateRealmsOnStartup() {
        appUserStore.validate();
        log.info("Application realm identity store validated at startup.");
    }
}
