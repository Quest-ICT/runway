package mx.quest.runway.content;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import mx.quest.runway.plugin.ContributionGuard;
import mx.quest.runway.spi.ContentSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Collects every {@link ContentSource} bean — core and plugin alike — and indexes them by their stable
 * {@link ContentSource#key()}. This is the kernel half of the content-source seam: sources arrive as ordinary beans
 * (a plugin contributes one exactly as the
 * core does), and every consumer — the content router, the sitemap/llms.txt endpoints, the search indexer —
 * iterates this one registry rather than knowing any source individually.
 *
 * <p><b>Key collisions</b>: a duplicate or blank key among kernel sources fails startup; a plugin
 * source that collides (with the kernel's {@code pages}, or an earlier plugin in load order) or has a blank key is
 * refused and its plugin marked degraded — a source can never silently shadow another's content namespace
 * ({@code <key>:<id>} identities, per-tenant {@code sites/<id>/plugins/<key>/} folders).
 *
 * <p>The registration order (Spring's collection order) is preserved and exposed by {@link #sources()};
 * consumers that enumerate all sources iterate in that stable order. Per-request failure containment
 * (degrade-don't-crash around a throwing source) is the <em>consumer's</em> job, done through the one containment helper
 * the registry exposes ({@link #guard()}).
 *
 * <p>Thread-safe and immutable after construction.
 */
@Component
public class ContentSourceRegistry {

    private final Map<String, ContentSource> byKey;
    private final ContributionGuard guard;

    /** Unit-test form: every source counts as kernel code, so a collision fails fast. */
    public ContentSourceRegistry(List<ContentSource> sources) {
        this(sources, ContributionGuard.standalone());
    }

    /**
     * @param sources every {@link ContentSource} bean Spring discovered (possibly empty)
     * @param guard   the plugin-contribution containment policy
     * @throws IllegalStateException if a <b>kernel</b> source has a blank key or two kernel sources share one
     */
    @Autowired
    public ContentSourceRegistry(List<ContentSource> sources, ContributionGuard guard) {
        this.guard = guard;
        // Collections.unmodifiableMap over the guard's LinkedHashMap (not Map.copyOf, which does not keep order) —
        // the registration order is part of the contract sources()/consumers iterate in.
        this.byKey = Collections.unmodifiableMap(guard.index("ContentSource", sources, ContentSource::key));
    }

    /** The containment policy every consumer wraps its calls into a source with. */
    public ContributionGuard guard() {
        return guard;
    }

    /** Whether no sources are registered (consumers then skip the seam entirely — today's behaviour). */
    public boolean isEmpty() {
        return byKey.isEmpty();
    }

    /** All registered sources, in registration order. */
    public List<ContentSource> sources() {
        return List.copyOf(byKey.values());
    }

    /** The source with the given key, if registered. */
    public Optional<ContentSource> byKey(String key) {
        return Optional.ofNullable(byKey.get(key));
    }
}
