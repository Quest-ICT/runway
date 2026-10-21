package mx.quest.protection;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A tiny, dependency-free reader for the two fields the CAPTCHA {@code siteverify} JSON carries that
 * {@link CaptchaVerifier} actually consumes: the boolean {@code success} and (reCAPTCHA v3 only) the numeric
 * {@code score}. reCAPTCHA returns a flat top-level JSON object with these keys, so a targeted field scan is both
 * sufficient and safer than binding a full model — it never fails on an unexpected extra field and pulls no JSON
 * library into the thin plugin jar.
 *
 * <p>Non-strict on purpose: a body that is missing, blank, or malformed yields {@code success == false} and
 * {@code score == 0.0} (a rejection, not an exception) — the verifier's transport-level fail-open is decided
 * before this reader is reached, so here a body we cannot make sense of is treated as "not verified", which is
 * the safe outcome for a *present* (2xx) response. The patterns are anchored to the {@code "success"} /
 * {@code "score"} keys so a boolean/number elsewhere in the payload cannot be mistaken for them.
 */
final class SiteVerifyResponse {

    // "success" : true   — tolerant of whitespace around the colon; value must be the literal true/false token.
    private static final Pattern SUCCESS =
            Pattern.compile("\"success\"\\s*:\\s*(true|false)", Pattern.CASE_INSENSITIVE);

    // "score" : 0.7      — a JSON number (optional sign, decimals, exponent).
    private static final Pattern SCORE =
            Pattern.compile("\"score\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?)");

    private SiteVerifyResponse() {
    }

    /** Whether the response's {@code success} field is present and {@code true}; false for any other body. */
    static boolean success(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        Matcher m = SUCCESS.matcher(body);
        return m.find() && "true".equalsIgnoreCase(m.group(1));
    }

    /** The response's {@code score} (reCAPTCHA v3), or {@code 0.0} when absent/unparseable (a safe floor). */
    static double score(String body) {
        if (body == null || body.isBlank()) {
            return 0.0;
        }
        Matcher m = SCORE.matcher(body);
        if (!m.find()) {
            return 0.0;
        }
        try {
            return Double.parseDouble(m.group(1));
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}
