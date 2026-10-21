package mx.quest.runway.console;

import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;

/**
 * The single source of the cache-busting token that every first-party {@code /__assets/**} reference carries as
 * {@code ?v=<token>} — templates read it as the {@code assetVersion} model attribute, Java code that emits asset
 * URLs (the wizard, the preview window, the protection plugin's CAPTCHA widget) calls {@link #url(String)}.
 *
 * <p><b>Why every reference is versioned.</b> {@code /__assets/**} answers a versioned request with a one-day
 * {@code max-age} (see {@link AssetCachePolicy}), so a browser does not even ask again for a day. A new release
 * must therefore be served from a <em>new URL</em>, or a browser keeps running the previous release's script after
 * an upgrade — which is exactly what happened to {@code page-editor.js} before this existed. An unversioned
 * request is still answered correctly, only less efficiently ({@code no-cache}: revalidate every time).
 *
 * <p><b>The token.</b> The build timestamp from build-info when present — the reproducible-build
 * {@code outputTimestamp}, stable across restarts of one jar and different for every release cut — else the time
 * this class was loaded (a dev/IDE run without build-info: different on every restart). The fallback is a
 * process-wide constant, so {@link OperatorShellModel} (which derives it the same way through {@link #of}) and
 * this bean always agree within one JVM. <b>Dev caveat:</b> two SNAPSHOT builds share the pinned timestamp, so a
 * developer testing one build over another must hard-reload.
 *
 * <p>Immutable and thread-safe.
 */
@Component
public class AssetVersion {

    /** The query parameter that marks a versioned asset request. */
    public static final String PARAM = "v";

    /** The fallback token: when this class was loaded, i.e. process start (one value per JVM). */
    private static final String PROCESS_START = Long.toString(System.currentTimeMillis());

    private final String value;

    /** The bean: derived from build-info when present (see {@link #of}). */
    @Autowired
    public AssetVersion(ObjectProvider<BuildProperties> buildProperties) {
        this(of(buildProperties.getIfAvailable()));
    }

    private AssetVersion(String value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    /** A fixed token — for tests and for callers that already hold the value. */
    public static AssetVersion fixed(String value) {
        return new AssetVersion(value);
    }

    /**
     * The token for {@code build}: its build time in epoch milliseconds, or the process-start fallback when
     * {@code build} (or its time) is {@code null}. Never {@code null}, always digits.
     */
    public static String of(BuildProperties build) {
        return (build != null && build.getTime() != null)
                ? Long.toString(build.getTime().toEpochMilli())
                : PROCESS_START;
    }

    /** The token itself (digits only — safe to embed in an attribute or URL unescaped). */
    public String value() {
        return value;
    }

    /**
     * {@code path} with {@code v=<token>} appended — {@code ?v=} or, when {@code path} already has a query,
     * {@code &v=}. {@code path} is expected to be an {@code /__assets/...} path; it is not escaped.
     */
    public String url(String path) {
        return path + (path.indexOf('?') < 0 ? '?' : '&') + PARAM + '=' + value;
    }
}
