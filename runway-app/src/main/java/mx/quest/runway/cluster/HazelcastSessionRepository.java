package mx.quest.runway.cluster;

import com.hazelcast.map.IMap;
import java.time.Duration;
import org.springframework.session.MapSession;
import org.springframework.session.SessionRepository;

/**
 * A hand-rolled {@link SessionRepository} over a Hazelcast {@link IMap}{@code <String, MapSession>} — Spring
 * Session's persistence contract, backed by the distributed grid rather than one JVM's heap.
 *
 * <h2>Why hand-rolled</h2>
 * {@code spring-session-hazelcast} was dropped from Spring Session 4.x; the library's
 * own {@link org.springframework.session.MapSessionRepository} is deliberately <em>not</em> reused either,
 * because it is typed to a plain {@code Map<String, Session>} and its semantics (defensive copies, expiry
 * handling, id-change on {@code save}) are simple enough to own directly against the typed {@code IMap}. Keep
 * the Spring Session API, roll the repository — the pattern for any dropped backend.
 *
 * <h2>How a {@link MapSession} lives in the grid</h2>
 * {@link MapSession} is {@link java.io.Serializable}, so it round-trips through the {@code IMap}'s default Java
 * serializer with no custom {@code DataSerializable}/portable code. The attributes it carries — Spring
 * Security's {@code SecurityContext} and the CSRF token, written under the realm/site-scoped keys — must
 * therefore be serializable too; {@code security.User} and {@code security.RunwayUserDetails} are made
 * {@link java.io.Serializable} for exactly this reason (the rest of the chain — {@code SecurityContextImpl},
 * {@code UsernamePasswordAuthenticationToken}, {@code SimpleGrantedAuthority}, {@code DefaultCsrfToken} — is
 * already serializable).
 *
 * <h2>Semantics</h2>
 * <ul>
 *   <li><b>Defensive copies.</b> {@link #save} stores a {@code new MapSession(session)} copy and {@link
 *       #findById} returns one, so a caller mutating the object it holds never silently changes the stored
 *       entry — only an explicit {@link #save} persists. (In EMBEDDED mode a get could otherwise hand back a
 *       reference into local heap.)</li>
 *   <li><b>Id change.</b> Spring Security rotates the session id on login (session-fixation defense); when the
 *       id differs from the original, the old key is removed so the map is not left with a stale duplicate.</li>
 *   <li><b>Expiry.</b> {@link #findById} treats an expired entry as absent — it deletes it and returns {@code
 *       null}. Hazelcast also evicts idle entries via the map's {@code max-idle-seconds} (set from {@link
 *       RunwayClusterProperties#getSessionTtl()} in {@link ClusterConfig}), so this is a belt-and-braces
 *       read-time check, not the primary sweeper.</li>
 * </ul>
 *
 * <p>Wired only when {@code runway.cluster.enabled=true}; in the single-node default this class is never
 * instantiated and the container's {@code HttpSession} is used unchanged.
 */
final class HazelcastSessionRepository implements SessionRepository<MapSession> {

    private final IMap<String, MapSession> sessions;
    private final Duration sessionTtl;

    /**
     * @param sessions   the distributed session map (never {@code null})
     * @param sessionTtl the default max-inactive-interval stamped onto a newly created session (never {@code
     *                   null}); the same value seeds the map's {@code max-idle-seconds} in {@link ClusterConfig}
     */
    HazelcastSessionRepository(IMap<String, MapSession> sessions, Duration sessionTtl) {
        this.sessions = sessions;
        this.sessionTtl = sessionTtl;
    }

    @Override
    public MapSession createSession() {
        MapSession created = new MapSession();
        created.setMaxInactiveInterval(sessionTtl);
        return created;
    }

    @Override
    public void save(MapSession session) {
        if (!session.getId().equals(session.getOriginalId())) {
            sessions.remove(session.getOriginalId());
        }
        sessions.put(session.getId(), new MapSession(session));
    }

    @Override
    public MapSession findById(String id) {
        MapSession saved = sessions.get(id);
        if (saved == null) {
            return null;
        }
        if (saved.isExpired()) {
            deleteById(saved.getId());
            return null;
        }
        return new MapSession(saved);
    }

    @Override
    public void deleteById(String id) {
        sessions.remove(id);
    }
}
