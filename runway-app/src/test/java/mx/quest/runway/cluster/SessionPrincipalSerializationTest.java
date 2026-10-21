package mx.quest.runway.cluster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.List;
import java.util.Set;
import mx.quest.runway.security.Roles;
import mx.quest.runway.security.RunwayUserDetails;
import mx.quest.runway.security.User;
import mx.quest.runway.spi.model.Realm;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;

/**
 * The guard against a non-serializable field creeping into {@link User} or {@link RunwayUserDetails} and
 * silently breaking clustered sessions. When {@code runway.cluster.enabled=true}, Spring Security's
 * {@code SecurityContext} — with a {@link RunwayUserDetails} principal — is stored as an {@code HttpSession}
 * attribute and serialized into the Hazelcast sessions {@code IMap}. If any field on the identity graph were
 * not {@link java.io.Serializable}, the write would throw at runtime, on a node other than the author's, long
 * after this change shipped. This test round-trips the exact object graph through plain Java serialization
 * (the {@code IMap}'s default serializer) so such a regression fails at build time instead.
 *
 * <p>Pure unit test — no Spring, no Hazelcast — because Java serialization is precisely what the grid uses.
 */
class SessionPrincipalSerializationTest {

    @Test
    void aSecurityContextWrappingRunwayUserDetailsRoundTripsThroughJavaSerialization() throws Exception {
        User user = new User("app-master", "root", "$argon2id$v=19$m=19456,t=2,p=1$c2FsdA$aGFzaA", "Root",
                "root@example.test", true, true, Realm.APPLICATION,
                Set.of(Roles.SYSTEM_SUPERADMIN, Roles.SYSTEM_ADMIN), List.of("*"));
        RunwayUserDetails principal = new RunwayUserDetails(user);
        UsernamePasswordAuthenticationToken auth = UsernamePasswordAuthenticationToken.authenticated(
                principal, principal.getPassword(), principal.getAuthorities());
        SecurityContextImpl context = new SecurityContextImpl(auth);

        SecurityContextImpl restored = roundTrip(context);

        // The whole graph survived — and it is a genuinely fresh copy, not the same reference.
        assertNotSame(context, restored);
        RunwayUserDetails restoredPrincipal =
                assertInstanceOf(RunwayUserDetails.class, restored.getAuthentication().getPrincipal());

        // Identity + realm + authorities survived the trip intact.
        assertEquals("app-master", restoredPrincipal.principalId());
        assertEquals("root", restoredPrincipal.getUsername());
        assertEquals(Realm.APPLICATION, restoredPrincipal.realm());
        assertTrue(restoredPrincipal.master());
        assertTrue(restoredPrincipal.assignedTo("any-site")); // "*" assignment survived
        assertEquals(
                Set.of(Roles.AUTHORITY_PREFIX + Roles.SYSTEM_SUPERADMIN, Roles.AUTHORITY_PREFIX + Roles.SYSTEM_ADMIN),
                restoredPrincipal.getAuthorities().stream().map(Object::toString)
                        .collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void theUserRecordAloneRoundTripsAndEqualsItself() throws Exception {
        User user = new User("u1", "alice", "hash", "Alice", "", true, false, Realm.TENANT,
                Set.of(Roles.ADMIN), List.of("acme"));
        // The record's generated equals compares every component — a clean round-trip must equal the original.
        assertEquals(user, roundTrip(user));
    }

    @SuppressWarnings("unchecked")
    private static <T> T roundTrip(T value) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(value);
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (T) in.readObject();
        }
    }
}
