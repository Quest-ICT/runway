package mx.quest.runway.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * The public-page nonce source: derived from the page version (stable per ETag, different across ETags), backed by a
 * lazily created owner-only secret that survives a restart, rotates when deleted, and disables the feature — never
 * the page — when it cannot be used.
 */
class PublicPageNonceTest {

    private static final String ETAG = "\"0123456789abcdef0123456789abcdef\"";

    private static PublicPageNonce on(Path dir) {
        return new PublicPageNonce(true, dir.resolve(PublicPageNonce.KEY_FILE));
    }

    @Test
    void theNonceIsStablePerEtagAndDiffersAcrossEtags(@TempDir Path dir) {
        PublicPageNonce source = on(dir);
        String a = source.nonceFor(ETAG);
        assertThat(a).isEqualTo(source.nonceFor(ETAG));
        assertThat(a).isNotEqualTo(source.nonceFor("\"ffffffffffffffffffffffffffffffff\""));
    }

    @Test
    void theNonceIsA22CharBase64UrlToken(@TempDir Path dir) {
        String nonce = on(dir).nonceFor(ETAG);
        assertThat(nonce).hasSize(PublicPageNonce.NONCE_CHARS).matches("[A-Za-z0-9_-]{22}");
    }

    @Test
    void theSecretIsCreatedOnFirstNeedOwnerOnlyAndNotBefore(@TempDir Path dir) throws Exception {
        Path key = dir.resolve(PublicPageNonce.KEY_FILE);
        PublicPageNonce source = on(dir);
        assertThat(key).as("nothing is written at construction").doesNotExist();

        source.nonceFor(ETAG);

        assertThat(key).exists();
        assertThat(Files.size(key)).isEqualTo(32);
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(key))).isEqualTo("rw-------");
        try (var leftovers = Files.list(dir)) {
            assertThat(leftovers.toList()).as("no temp file left behind").containsExactly(key);
        }
    }

    @Test
    void aRestartReusesTheSecretSoNoncesAndTheFoldSurvive(@TempDir Path dir) {
        PublicPageNonce first = on(dir);
        String nonce = first.nonceFor(ETAG);
        String fold = first.etagFold();

        PublicPageNonce afterRestart = on(dir);
        assertThat(afterRestart.nonceFor(ETAG)).isEqualTo(nonce);
        assertThat(afterRestart.etagFold()).isEqualTo(fold);
    }

    @Test
    void deletingTheSecretRotatesEveryNonceAndTheEtagFold(@TempDir Path dir) throws Exception {
        PublicPageNonce before = on(dir);
        String nonce = before.nonceFor(ETAG);
        String fold = before.etagFold();

        Files.delete(dir.resolve(PublicPageNonce.KEY_FILE));
        PublicPageNonce rotated = on(dir); // rotation takes effect at restart

        assertThat(rotated.nonceFor(ETAG)).isNotEqualTo(nonce);
        assertThat(rotated.etagFold()).as("a rotation must move every ETag").isNotEqualTo(fold);
    }

    @Test
    void theFoldNamesTheStateButNeverTheSecret(@TempDir Path dir) throws Exception {
        PublicPageNonce source = on(dir);
        String fold = source.etagFold();
        assertThat(fold).startsWith("on:").hasSize(3 + 16);
        String hexKey = java.util.HexFormat.of().formatHex(Files.readAllBytes(dir.resolve(PublicPageNonce.KEY_FILE)));
        assertThat(hexKey).doesNotContain(fold.substring(3));
        assertThat(new PublicPageNonce(false, dir.resolve(PublicPageNonce.KEY_FILE)).etagFold()).isEqualTo("off");
    }

    /**
     * Which scripts carry the nonce depends on the stamp allowlist, so a changed allowlist must move every ETag —
     * otherwise a body stamped under the old list could be revalidated into a 304 under the new one.
     */
    @Test
    void theStampAllowlistJoinsTheFold(@TempDir Path dir) {
        Path key = dir.resolve(PublicPageNonce.KEY_FILE);
        String plain = new PublicPageNonce(true, key).etagFold();
        String a = new PublicPageNonce(true, key, CspScriptAllowlist.parse("'self'").fingerprint()).etagFold();
        String b = new PublicPageNonce(true, key, CspScriptAllowlist.parse("'self' https://x.test").fingerprint())
                .etagFold();
        assertThat(a).startsWith(plain + ":").isNotEqualTo(b);
        assertThat(new PublicPageNonce(true, key, " ").etagFold()).isEqualTo(plain);
        assertThat(new PublicPageNonce(false, key, "abc").etagFold()).isEqualTo("off");
    }

    @Test
    void switchedOffItIsInertAndWritesNothing(@TempDir Path dir) {
        PublicPageNonce off = new PublicPageNonce(false, dir.resolve(PublicPageNonce.KEY_FILE));
        assertThat(off.active()).isFalse();
        assertThat(off.nonceFor(ETAG)).isNull();
        MockHttpServletRequest request = new MockHttpServletRequest();
        off.publish(request, ETAG);
        assertThat(request.getAttribute(PublicPageNonce.REQUEST_ATTRIBUTE)).isNull();
        assertThat(dir.resolve(PublicPageNonce.KEY_FILE)).doesNotExist();
    }

    @Test
    void publishAndWithdrawManageTheRequestAttribute(@TempDir Path dir) {
        PublicPageNonce source = on(dir);
        MockHttpServletRequest request = new MockHttpServletRequest();
        source.publish(request, ETAG);
        assertThat(request.getAttribute(PublicPageNonce.REQUEST_ATTRIBUTE)).isEqualTo(source.nonceFor(ETAG));
        PublicPageNonce.withdraw(request);
        assertThat(request.getAttribute(PublicPageNonce.REQUEST_ATTRIBUTE)).isNull();
    }

    /** A truncated/corrupt secret disables the feature (logged) — public pages keep the allowlist, never a 500. */
    @Test
    void anUnusableSecretDisablesTheFeatureInsteadOfFailingPages(@TempDir Path dir) throws Exception {
        Files.write(dir.resolve(PublicPageNonce.KEY_FILE), new byte[] {1, 2, 3});
        PublicPageNonce source = on(dir);
        assertThat(source.active()).isFalse();
        assertThat(source.nonceFor(ETAG)).isNull();
        assertThat(source.etagFold()).isEqualTo("off");
        assertThat(Files.size(dir.resolve(PublicPageNonce.KEY_FILE))).as("an operator file is never overwritten")
                .isEqualTo(3);
    }

    /** Racing first uses (threads here; nodes sharing the folder in a cluster) agree on ONE secret. */
    @Test
    void racingCreatorsAgreeOnOneSecret(@TempDir Path dir) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<String>> tasks = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                tasks.add(() -> on(dir).nonceFor(ETAG));
            }
            List<String> nonces = new ArrayList<>();
            for (Future<String> f : pool.invokeAll(tasks)) {
                nonces.add(f.get());
            }
            assertThat(nonces).doesNotContainNull();
            assertThat(nonces.stream().distinct().count()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
