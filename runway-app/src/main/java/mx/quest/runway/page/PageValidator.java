package mx.quest.runway.page;

import jakarta.servlet.http.HttpServletRequest;

/**
 * A page render's HTTP cache validators — a <b>strong</b> {@code ETag} and a {@code Last-Modified}
 * time — together with the conditional-request logic that decides whether a {@code 304 Not Modified}
 * may be returned. The host computes this <em>before</em> rendering, so an unchanged page
 * short-circuits the whole render.
 *
 * <p>The ETag is derived from everything that shapes the output (see {@link PageValidatorService}),
 * so it changes whenever the page, its master, the site's bundle/config, or the build changes — and
 * only then. {@code If-None-Match} takes precedence over {@code If-Modified-Since} (RFC 9110): when an
 * {@code If-None-Match} header is present it alone decides, and a non-match forces a fresh render even
 * if the date would have matched.
 *
 * <p>Public pages carry no modification time (since 1.0.0-rc1): {@link PageValidatorService} always yields
 * {@code lastModified == 0}, so {@code If-Modified-Since} never answers {@code 304} for a page — most of what the ETag
 * folds has no date that could move with it. The date path stays for a caller that can vouch for one.
 *
 * @param etag         the strong, quoted ETag (e.g. {@code "a1b2…"}); never {@code null}
 * @param lastModified a modification time in epoch millis that moves whenever the representation does, or {@code 0}
 *                     when there is none (then {@code If-Modified-Since} is ignored)
 */
public record PageValidator(String etag, long lastModified) {

    /**
     * Whether the request's conditional headers permit a {@code 304 Not Modified}.
     *
     * @param request the current request (its {@code If-None-Match} / {@code If-Modified-Since} headers)
     * @return {@code true} when the client's cached copy is still current
     */
    public boolean isNotModified(HttpServletRequest request) {
        String ifNoneMatch = request.getHeader("If-None-Match");
        if (ifNoneMatch != null && !ifNoneMatch.isBlank()) {
            return etagMatches(ifNoneMatch); // takes precedence; a non-match means "modified"
        }
        long ifModifiedSince = ifModifiedSince(request);
        return ifModifiedSince != -1 && lastModified > 0
                && lastModified / 1000 <= ifModifiedSince / 1000; // second granularity per HTTP dates
    }

    /** Matches our strong ETag against an {@code If-None-Match} list ({@code *}, weak {@code W/}, commas). */
    private boolean etagMatches(String header) {
        for (String candidate : header.split(",")) {
            String tag = candidate.trim();
            if (tag.equals("*")) {
                return true;
            }
            if (tag.startsWith("W/")) {
                tag = tag.substring(2).trim(); // compare weakly-tagged values by their opaque part
            }
            if (tag.equals(etag)) {
                return true;
            }
        }
        return false;
    }

    private static long ifModifiedSince(HttpServletRequest request) {
        try {
            return request.getDateHeader("If-Modified-Since");
        } catch (IllegalArgumentException malformed) {
            return -1;
        }
    }
}
