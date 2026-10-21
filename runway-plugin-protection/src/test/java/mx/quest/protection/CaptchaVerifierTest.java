package mx.quest.protection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The captcha verifier's request-building and response-parsing through the injected
 * {@link CaptchaVerifier.Exchange} seam — no real network. Since 0.4.0 the only provider is Google reCAPTCHA
 * (v2 + v3); this covers the reCAPTCHA endpoint + form body, the {@code success} parse, the v3 score gate, the
 * legacy-value migration to v2, the provider-shaped widget helpers, the blank-token short-circuit, and the
 * fail-open stance on a transport error / non-2xx status.
 */
class CaptchaVerifierTest {

    /** A recording exchange: captures the URL + body and returns a canned response (or throws). */
    private static final class RecordingExchange implements CaptchaVerifier.Exchange {
        final AtomicReference<String> url = new AtomicReference<>();
        final AtomicReference<String> body = new AtomicReference<>();
        private final CaptchaVerifier.Exchange.Response response;
        private final IOException toThrow;

        RecordingExchange(CaptchaVerifier.Exchange.Response response) {
            this.response = response;
            this.toThrow = null;
        }

        RecordingExchange(IOException toThrow) {
            this.response = null;
            this.toThrow = toThrow;
        }

        @Override
        public CaptchaVerifier.Exchange.Response post(String u, String b) throws IOException {
            url.set(u);
            body.set(b);
            if (toThrow != null) {
                throw toThrow;
            }
            return response;
        }
    }

    private static CaptchaVerifier.Exchange.Response ok(String json) {
        return new CaptchaVerifier.Exchange.Response(200, json);
    }

    private static ProtectionSettings.Captcha captcha(String provider, double threshold) {
        return new ProtectionSettings.Captcha(true, provider, "SITEKEY", "sk-secret", threshold);
    }

    // ---- token param + widget helpers -------------------------------------

    @Test
    void tokenParamIsAlwaysTheRecaptchaField() {
        // Both modes (and any legacy/blank value that migrates to v2) post under the same field name.
        assertEquals("g-recaptcha-response", CaptchaVerifier.tokenParam("recaptcha-v2"));
        assertEquals("g-recaptcha-response", CaptchaVerifier.tokenParam("recaptcha-v3"));
        assertEquals("g-recaptcha-response", CaptchaVerifier.tokenParam(null));
        assertEquals("g-recaptcha-response", CaptchaVerifier.tokenParam("turnstile"));
    }

    @Test
    void widgetHelpersDistinguishVisibleV2FromInvisibleV3() {
        assertFalse(CaptchaVerifier.isInvisible("recaptcha-v2"));
        assertTrue(CaptchaVerifier.isInvisible("recaptcha-v3"));
        // A legacy / blank value normalizes to the visible v2.
        assertFalse(CaptchaVerifier.isInvisible("turnstile"));
        assertFalse(CaptchaVerifier.isInvisible(null));

        assertEquals("g-recaptcha", CaptchaVerifier.widgetClass("recaptcha-v2"));
        assertEquals("", CaptchaVerifier.widgetClass("recaptcha-v3"), "v3 is invisible — no widget div");

        assertEquals("https://www.google.com/recaptcha/api.js", CaptchaVerifier.scriptUrl("recaptcha-v2", "KEY"));
        assertEquals("https://www.google.com/recaptcha/api.js?render=KEY",
                CaptchaVerifier.scriptUrl("recaptcha-v3", "KEY"), "v3 splices the site key into ?render=");
    }

    // ---- endpoint + body --------------------------------------------------

    @Test
    void verifyPostsSecretResponseAndRemoteIpToTheRecaptchaEndpoint() {
        RecordingExchange ex = new RecordingExchange(ok("{\"success\":true}"));
        boolean result = new CaptchaVerifier(ex).verify(captcha("recaptcha-v2", 0.5), "tok123", "203.0.113.1");
        assertTrue(result);
        assertEquals(CaptchaVerifier.RECAPTCHA_URL, ex.url.get());
        assertTrue(ex.body.get().contains("secret=sk-secret"), "the secret is form-encoded into the body");
        assertTrue(ex.body.get().contains("response=tok123"), "the token is the response param");
        assertTrue(ex.body.get().contains("remoteip=203.0.113.1"), "the remote ip hint is included when present");
    }

    @Test
    void recaptchaV2AndV3HitTheRecaptchaEndpoint() {
        RecordingExchange v2 = new RecordingExchange(ok("{\"success\":true}"));
        assertTrue(new CaptchaVerifier(v2).verify(captcha("recaptcha-v2", 0.5), "t", "203.0.113.1"));
        assertEquals(CaptchaVerifier.RECAPTCHA_URL, v2.url.get());

        RecordingExchange v3 = new RecordingExchange(ok("{\"success\":true,\"score\":0.9}"));
        assertTrue(new CaptchaVerifier(v3).verify(captcha("recaptcha-v3", 0.5), "t", "203.0.113.1"));
        assertEquals(CaptchaVerifier.RECAPTCHA_URL, v3.url.get());
    }

    @Test
    void aLegacyProviderValueMigratesToRecaptchaV2AndStillVerifies() {
        // A site left over from a dropped provider (Turnstile / hCaptcha) verifies against reCAPTCHA as v2 —
        // it keeps working after upgrade (the operator must supply a reCAPTCHA key for the challenge to pass).
        RecordingExchange ex = new RecordingExchange(ok("{\"success\":true}"));
        assertTrue(new CaptchaVerifier(ex).verify(captcha("turnstile", 0.5), "tok", "203.0.113.1"));
        assertEquals(CaptchaVerifier.RECAPTCHA_URL, ex.url.get(), "a legacy value hits the reCAPTCHA endpoint");
    }

    @Test
    void remoteIpIsOmittedWhenBlank() {
        RecordingExchange ex = new RecordingExchange(ok("{\"success\":true}"));
        new CaptchaVerifier(ex).verify(captcha("recaptcha-v2", 0.5), "tok", null);
        assertFalse(ex.body.get().contains("remoteip="), "no remoteip param when the ip is null");
    }

    // ---- success / rejection parse ----------------------------------------

    @Test
    void aSuccessFalseResponseIsRejected() {
        RecordingExchange ex = new RecordingExchange(ok("{\"success\":false,\"error-codes\":[\"invalid-input-response\"]}"));
        assertFalse(new CaptchaVerifier(ex).verify(captcha("recaptcha-v2", 0.5), "tok", "203.0.113.1"),
                "the provider actively rejected the token");
    }

    // ---- blank token short-circuit ----------------------------------------

    @Test
    void aBlankTokenFailsWithoutCallingTheProvider() {
        AtomicReference<Boolean> called = new AtomicReference<>(false);
        CaptchaVerifier.Exchange ex = (url, body) -> { called.set(true); return ok("{\"success\":true}"); };
        CaptchaVerifier verifier = new CaptchaVerifier(ex);
        assertFalse(verifier.verify(captcha("recaptcha-v2", 0.5), "", "203.0.113.1"), "empty token → not verified");
        assertFalse(verifier.verify(captcha("recaptcha-v2", 0.5), null, "203.0.113.1"), "null token → not verified");
        assertFalse(verifier.verify(captcha("recaptcha-v2", 0.5), "   ", "203.0.113.1"), "blank token → not verified");
        assertFalse(called.get(), "a missing token never reaches the provider");
    }

    // ---- reCAPTCHA v3 score gate ------------------------------------------

    @Test
    void recaptchaV3RejectsAScoreBelowTheThreshold() {
        RecordingExchange ex = new RecordingExchange(ok("{\"success\":true,\"score\":0.3}"));
        assertFalse(new CaptchaVerifier(ex).verify(captcha("recaptcha-v3", 0.5), "tok", "203.0.113.1"),
                "v3 score 0.3 < threshold 0.5 → rejected");
    }

    @Test
    void recaptchaV3AcceptsAScoreAtOrAboveTheThreshold() {
        RecordingExchange atThreshold = new RecordingExchange(ok("{\"success\":true,\"score\":0.5}"));
        assertTrue(new CaptchaVerifier(atThreshold).verify(captcha("recaptcha-v3", 0.5), "tok", "203.0.113.1"),
                "v3 score == threshold → accepted");

        RecordingExchange above = new RecordingExchange(ok("{\"success\":true,\"score\":0.8}"));
        assertTrue(new CaptchaVerifier(above).verify(captcha("recaptcha-v3", 0.5), "tok", "203.0.113.1"));
    }

    @Test
    void theScoreGateOnlyAppliesToV3NotV2() {
        // v2 has no score; a success:true (no score field) passes even though the config carries a threshold.
        RecordingExchange ex = new RecordingExchange(ok("{\"success\":true}"));
        assertTrue(new CaptchaVerifier(ex).verify(captcha("recaptcha-v2", 0.9), "tok", "203.0.113.1"),
                "v2 ignores the score threshold");
    }

    // ---- fail-open on transport error / non-2xx ---------------------------

    @Test
    void aTransportErrorFailsOpen() {
        RecordingExchange ex = new RecordingExchange(new IOException("connection reset"));
        assertTrue(new CaptchaVerifier(ex).verify(captcha("recaptcha-v2", 0.5), "tok", "203.0.113.1"),
                "a provider outage must never lock everyone out — fail open");
    }

    @Test
    void aNon2xxStatusFailsOpen() {
        RecordingExchange ex = new RecordingExchange(new CaptchaVerifier.Exchange.Response(503, "gateway down"));
        assertTrue(new CaptchaVerifier(ex).verify(captcha("recaptcha-v3", 0.5), "tok", "203.0.113.1"),
                "a 5xx from the provider fails open");
    }

    @Test
    void aNullResponseFailsOpen() {
        CaptchaVerifier.Exchange ex = (url, body) -> null;
        assertTrue(new CaptchaVerifier(ex).verify(captcha("recaptcha-v2", 0.5), "tok", "203.0.113.1"),
                "a null response is treated as an outage — fail open");
    }

    // ---- score reader edge cases ------------------------------------------

    @Test
    void anUnparseableBodyOnA200IsTreatedAsNotVerified() {
        // A present (2xx) but nonsense body has no success:true → rejected (the transport fail-open is only for
        // an unreachable provider, decided before parsing).
        RecordingExchange ex = new RecordingExchange(ok("not json at all"));
        assertFalse(new CaptchaVerifier(ex).verify(captcha("recaptcha-v2", 0.5), "tok", "203.0.113.1"));
    }

    @Test
    void siteVerifyResponseReadsSuccessAndScore() {
        assertTrue(SiteVerifyResponse.success("{\"success\": true, \"score\": 0.7}"));
        assertFalse(SiteVerifyResponse.success("{\"success\": false}"));
        assertFalse(SiteVerifyResponse.success(""), "blank body → not success");
        assertFalse(SiteVerifyResponse.success(null), "null body → not success");
        assertEquals(0.7, SiteVerifyResponse.score("{\"success\":true,\"score\":0.7}"), 1e-9);
        assertEquals(0.0, SiteVerifyResponse.score("{\"success\":true}"), 1e-9, "absent score → 0.0 floor");
    }
}
