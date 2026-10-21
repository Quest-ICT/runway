package mx.quest.runway.storage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;

/**
 * Says so, once at startup, when a configuration still sets a {@code runway.storage.*} key that was removed
 * before 1.0 ({@value #STATS} and {@value #LOGS}). Neither key ever changed where Runway writes anything — the
 * first was never read, the second only labelled a folder on the About page — so dropping them changes no
 * behaviour. Failing the start over a key with no effect would punish an operator for a harmless leftover, and
 * staying silent would let them believe it still does something; a {@code WARN} naming the key is the middle.
 *
 * <p>Relaxed binding applies: {@code RUNWAY_STORAGE_STATS} in the environment is detected like the YAML key.
 */
public final class RetiredStorageKeys implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(RetiredStorageKeys.class);

    /** Never read: bandwidth is metered per site, in {@code sites/<id>/stats/}. */
    static final String STATS = "runway.storage.stats";
    /** Only displayed: the kernel writes no file there (the launcher's {@code runway.out} is outside Runway). */
    static final String LOGS = "runway.storage.logs";

    private final Environment environment;

    public RetiredStorageKeys(Environment environment) {
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    /** The retired keys this environment still sets, in a stable order (empty when none). */
    public List<String> present() {
        List<String> found = new ArrayList<>();
        for (String key : List.of(STATS, LOGS)) {
            if (environment.containsProperty(key)) {
                found.add(key);
            }
        }
        return found;
    }

    @Override
    public void afterPropertiesSet() {
        for (String key : present()) {
            log.warn("Configuration key '{}' was removed before 1.0 and has no effect; delete it from your "
                    + "configuration (see the CHANGELOG).", key);
        }
    }
}
