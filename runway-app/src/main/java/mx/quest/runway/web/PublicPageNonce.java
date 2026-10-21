package mx.quest.runway.web;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import mx.quest.runway.storage.OwnerOnlyFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The CSP nonce for <b>public tenant pages</b>: derived from the page's <b>version</b>, not drawn per
 * request.
 *
 * <h2>Why derived, not random</h2>
 * Public pages are shared-cacheable ({@code public, max-age=300}) and revalidate with {@code 304}. A random
 * per-request nonce cannot coexist with that: a cache that stores header and body together freezes one nonce for
 * everyone, and a cache that re-validates would pair a fresh header with a stale body and block every script.
 * So the nonce is {@code base64url(HMAC-SHA256(secret, "runway-csp-nonce/v1|" + etag))}, truncated to
 * {@value #NONCE_CHARS} characters (132 bits): <b>stable for one content version</b> — every {@code 200} and every
 * {@code 304} of the same ETag carries the same value, so shared caching and revalidation keep working — and it
 * changes whenever the page changes.
 *
 * <h2>The secret, and why its fingerprint is in the ETag</h2>
 * 32 bytes from {@link SecureRandom}, generated on first need into {@code <external>/csp-nonce.key}, owner-only
 * ({@code 0600}, via {@link OwnerOnlyFiles}). It is read once and held in memory. <b>Rotation</b>: delete the file
 * and restart — every nonce changes. Because a body carries the nonce, a rotated nonce is a <em>different
 * body</em> for the same inputs, so {@link #etagFold()} hands {@code PageValidatorService} a fingerprint of the
 * secret (never the secret): a rotation moves every ETag, and no browser or CDN can revalidate an old body (old
 * nonce) against a new header. The same fold carries the on/off state, so flipping the feature moves every ETag
 * too. In a cluster, give every node the same file (as with other shared secrets) or each node gets its own
 * ETags — still correct, just fewer cross-node {@code 304}s.
 *
 * <h2>Fail-safe</h2>
 * If the key cannot be created or read, the feature disables itself for this process (logged once at ERROR):
 * public pages keep today's host-allowlist policy and their ETags fold {@code off}. Never a 500, never a header
 * that would block the page's scripts.
 *
 * <h2>Limits (documented, not hidden)</h2>
 * The nonce is visible to anyone who can fetch the page and stable per content version, so it is not a secret
 * against markup injected into the server-rendered page. That is why the edge stamps <b>only</b> the scripts the
 * host-allowlist policy would have run ({@link CspScriptAllowlist} — same-origin or allowlisted {@code src}, never an
 * injected inline script) and overrides any {@code nonce} attribute already in the markup; where it cannot override a
 * copy (inside an {@code <iframe srcdoc>}, which inherits the page's policy), the edge sees the page's own nonce in the
 * body and serves that response with no nonce at all ({@code ScriptNonceStamper.carriesNonce}): injected markup gains
 * nothing it did not have under the allowlist policy. What the nonce adds is {@code 'strict-dynamic'}: the trusted
 * scripts may insert further scripts (reCAPTCHA's loader), and parser-inserted scripts at runtime
 * ({@code document.write}) that carry no nonce are blocked.
 */
public class PublicPageNonce {

    private static final Logger log = LoggerFactory.getLogger(PublicPageNonce.class);

    /**
     * Request attribute under which {@code PageController} publishes the public-page nonce for this response.
     * Read by {@link CspFilter} (header) and {@code HeadInjectionFilter} (body stamp). Present only on a page
     * response that carries a validator; absent everywhere else (errors, plugin endpoints, maintenance).
     */
    public static final String REQUEST_ATTRIBUTE = "runway.csp.publicNonce";

    /** The secret's file name under the external folder. */
    public static final String KEY_FILE = "csp-nonce.key";

    /** Characters of base64url kept (22 ⇒ 132 bits — above the 128-bit floor CSP guidance asks for). */
    static final int NONCE_CHARS = 22;

    private static final int KEY_BYTES = 32;
    private static final String HMAC = "HmacSHA256";

    private final boolean configured;
    private final Path keyFile;
    /** The stamp allowlist's fingerprint folded into the ETag ({@code null}: none — the 2-argument form). */
    private final String stampPolicy;

    /** {@code null} until loaded; a zero-length array marks "load failed — feature off for this process". */
    private volatile byte[] key;
    private volatile String fingerprint;

    /**
     * @param configured whether public strict-dynamic is switched on — CSP not {@code OFF}, {@code strict-dynamic}
     *                   on, and {@code public-strict-dynamic} on ({@link RunwayCspProperties#isPublicNonceActive()})
     * @param keyFile    where the secret lives ({@code <external>/csp-nonce.key})
     */
    public PublicPageNonce(boolean configured, Path keyFile) {
        this(configured, keyFile, null);
    }

    /**
     * @param stampPolicy the fingerprint of the {@link CspScriptAllowlist} that decides which scripts the edge stamps
     *                    ({@link CspScriptAllowlist#fingerprint()}); it joins {@link #etagFold()}, because a changed
     *                    allowlist stamps a different body for the same page
     */
    public PublicPageNonce(boolean configured, Path keyFile, String stampPolicy) {
        this.configured = configured;
        this.keyFile = keyFile;
        this.stampPolicy = stampPolicy == null || stampPolicy.isBlank() ? null : stampPolicy;
    }

    /** Whether public pages get a derived nonce + {@code 'strict-dynamic'} (config on <b>and</b> a usable key). */
    public boolean active() {
        return configured && loadKey().length > 0;
    }

    /** Whether the configuration asks for the feature (key not consulted) — the edge uses it to decide buffering. */
    public boolean configured() {
        return configured;
    }

    /**
     * The token {@code PageValidatorService} folds into every page ETag: {@code off}, or {@code on:<fingerprint>}
     * (plus {@code :<allowlist fingerprint>} when one was given). A page body differs by the stamped nonce and by
     * which scripts carry it, so the switch, the secret and the stamp allowlist must all move the validator.
     */
    public String etagFold() {
        if (!active()) {
            return "off";
        }
        return stampPolicy == null ? "on:" + fingerprint : "on:" + fingerprint + ":" + stampPolicy;
    }

    /**
     * The nonce for a page version, or {@code null} when the feature is not {@link #active()}.
     *
     * @param etag the page's strong ETag (quoted, as sent)
     */
    public String nonceFor(String etag) {
        if (etag == null || !active()) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(key, HMAC));
            byte[] tag = mac.doFinal(("runway-csp-nonce/v1|" + etag).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(tag).substring(0, NONCE_CHARS);
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("HmacSHA256 unavailable", impossible);
        }
    }

    /**
     * Publishes this response's nonce (derived from {@code etag}) for the CSP header and the body stamp; a no-op
     * when inactive. Call it on every response that carries {@code etag} — the {@code 200} and the {@code 304}
     * alike — so both carry the same header.
     */
    public void publish(HttpServletRequest request, String etag) {
        String nonce = nonceFor(etag);
        if (nonce != null) {
            request.setAttribute(REQUEST_ATTRIBUTE, nonce);
        }
    }

    /** Withdraws a published nonce (an error page is about to replace the page body). */
    public static void withdraw(HttpServletRequest request) {
        request.removeAttribute(REQUEST_ATTRIBUTE);
    }

    private byte[] loadKey() {
        byte[] loaded = key;
        if (loaded != null) {
            return loaded;
        }
        synchronized (this) {
            if (key == null) {
                byte[] read;
                try {
                    read = readOrCreate();
                    fingerprint = fingerprintOf(read);
                } catch (IOException | RuntimeException ex) {
                    log.error("Public-page CSP nonce disabled: cannot use the secret {} ({}). Public pages keep the "
                            + "host-allowlist policy until this is fixed and the service restarted.", keyFile,
                            ex.toString());
                    read = new byte[0];
                }
                key = read;
            }
            return key;
        }
    }

    /** Reads the secret, creating it (owner-only, atomically published) when absent. */
    private byte[] readOrCreate() throws IOException {
        if (!Files.exists(keyFile)) {
            create();
        }
        byte[] bytes = Files.readAllBytes(keyFile);
        if (bytes.length < KEY_BYTES) {
            throw new IOException("the secret is " + bytes.length + " bytes; at least " + KEY_BYTES
                    + " are required (delete the file to regenerate it)");
        }
        return bytes;
    }

    /**
     * Writes 32 random bytes to a private temp file, restricts it, then publishes it under the final name with a
     * hard link — which fails if the name exists, so two nodes sharing the folder (or a racing thread) never
     * overwrite each other's key or read a half-written one. Falls back to a plain move where hard links are
     * unsupported.
     */
    private void create() throws IOException {
        Files.createDirectories(keyFile.toAbsolutePath().getParent());
        byte[] fresh = new byte[KEY_BYTES];
        new SecureRandom().nextBytes(fresh);
        Path temp = Files.createTempFile(keyFile.toAbsolutePath().getParent(), ".csp-nonce", ".tmp");
        try {
            OwnerOnlyFiles.restrictToOwner(temp);
            try (OutputStream out = Files.newOutputStream(temp, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.SYNC)) {
                out.write(fresh);
            }
            try {
                Files.createLink(keyFile, temp);
                log.info("Generated the public-page CSP nonce secret at {}", keyFile);
            } catch (FileAlreadyExistsException someoneElseWon) {
                // Another node/thread published first — use theirs.
            } catch (UnsupportedOperationException noHardLinks) {
                if (!Files.exists(keyFile)) {
                    Files.move(temp, keyFile);
                }
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** A one-way, non-reversible identity of the secret (never the secret itself) for the ETag fold. */
    private static String fingerprintOf(byte[] secret) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update("runway-csp-nonce-key-fingerprint/v1|".getBytes(StandardCharsets.UTF_8));
            byte[] digest = sha.digest(secret);
            return java.util.HexFormat.of().formatHex(digest, 0, 8);
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
