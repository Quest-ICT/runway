package mx.quest.runway.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/** The concurrency cap: work passes through under the cap and fails busy when the cap is saturated. */
class ConcurrencyLimitedPasswordEncoderTest {

    /** A delegate whose encode() blocks until released — lets a test pin a permit deterministically. */
    private static final class Blocking implements PasswordEncoder {
        private final CountDownLatch inside = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override public String encode(CharSequence raw) {
            inside.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "encoded";
        }

        @Override public boolean matches(CharSequence raw, String encoded) {
            return true;
        }

        @Override public boolean upgradeEncoding(String encoded) {
            return true; // distinct from the PasswordEncoder default (false), to prove delegation
        }
    }

    @Test
    void passesThroughUnderTheCap() {
        PasswordEncoder encoder = new ConcurrencyLimitedPasswordEncoder(new SimplePassThrough(), 2, 1000);
        for (int i = 0; i < 5; i++) {
            assertEquals("e:pw", encoder.encode("pw"));
        }
    }

    @Test
    void failsBusyWhenTheCapIsSaturated() throws InterruptedException {
        Blocking blocking = new Blocking();
        PasswordEncoder encoder = new ConcurrencyLimitedPasswordEncoder(blocking, 1, 150);

        Thread holder = new Thread(() -> encoder.encode("first")); // grabs the only permit and blocks
        holder.start();
        assertTrue(blocking.inside.await(2, TimeUnit.SECONDS), "the first hash should be in flight");

        assertThrows(PasswordHashingBusyException.class, () -> encoder.encode("second"));

        blocking.release.countDown();
        holder.join(2000);
    }

    @Test
    void upgradeEncodingDelegatesWithoutAPermit() {
        assertTrue(new ConcurrencyLimitedPasswordEncoder(new Blocking(), 1, 10).upgradeEncoding("x"));
    }

    @Test
    void rejectsANonPositiveCap() {
        assertThrows(IllegalArgumentException.class,
                () -> new ConcurrencyLimitedPasswordEncoder(new SimplePassThrough(), 0, 10));
    }

    /** A trivial non-blocking delegate for the happy path. */
    private static final class SimplePassThrough implements PasswordEncoder {
        @Override public String encode(CharSequence raw) { return "e:" + raw; }
        @Override public boolean matches(CharSequence raw, String encoded) { return encoded.equals("e:" + raw); }
    }
}
