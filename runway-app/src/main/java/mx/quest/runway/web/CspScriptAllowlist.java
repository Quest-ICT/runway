package mx.quest.runway.web;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code script-src} source list of the <b>host-allowlist policy</b> — {@code runway.csp.script-src} plus
 * {@code runway.csp.extra-hosts} plus the {@link CspHostContributor} hosts, exactly what {@link RunwayCspProperties}
 * puts after the nonce — evaluated the way a browser evaluates it, so the edge can ask: <em>would the policy a
 * public page had before {@code 'strict-dynamic'} have let this script run?</em>
 *
 * <h2>Why the edge needs this (the invariant)</h2>
 * On a public page, {@code 'strict-dynamic'} makes the browser ignore {@code 'self'} and the hosts: a parser-inserted
 * script runs if and only if it carries the page nonce. So whatever the edge stamps decides what runs. Stamping every
 * {@code <script>} (an earlier cut) turned scripts the allowlist used to block — an injected inline
 * {@code <script>}, a {@code <script src>} on a host nobody allowlisted — into scripts that run. The rule is now:
 * <b>the edge stamps a script only when this allowlist allows it</b>, so moving a page onto the nonce policy never
 * lets a script run that the allowlist policy blocked. {@code 'strict-dynamic'} then extends trust only from those
 * scripts to what <em>they</em> insert (reCAPTCHA's loader inserting its {@code gstatic} chunks — the purpose of
 * the public-page nonce).
 *
 * <h2>What is evaluated (CSP Level 3, §6.7.2)</h2>
 * <ul>
 *   <li>{@code 'self'} — the document's origin, plus the secure upgrade ({@code http} page → {@code https} URL on the
 *       same host with default ports);</li>
 *   <li>{@code *}, scheme sources ({@code https:}) and host sources ({@code [scheme://]host[:port][/path]}, with
 *       {@code *.} subdomain wildcards, {@code :*} ports, and the {@code http}→{@code https}, {@code ws}→{@code wss}
 *       scheme upgrades);</li>
 *   <li>{@code 'unsafe-inline'} — honoured for inline scripts only when the list has no nonce or hash source and no
 *       {@code 'strict-dynamic'} (a browser ignores it otherwise);</li>
 *   <li>a configured {@code 'strict-dynamic'} — the old policy then allowed no parser-inserted script by host at all,
 *       so nothing is allowed;</li>
 *   <li>every other token (hashes, {@code 'unsafe-eval'}, malformed expressions) allows nothing here. A hash source
 *       keeps working under the new policy on its own — the browser checks it — so it needs no stamp.</li>
 * </ul>
 *
 * <h2>Fail closed</h2>
 * The URL is classified from the attribute value <b>after</b> HTML character-reference decoding (the caller's job —
 * see {@code ScriptNonceStamper}), and any URL whose meaning this class cannot pin down the way a browser would —
 * a backslash, a control character, a special scheme without {@code //}, three or more slashes where the authority
 * starts ({@code ///evil.example/x.js}: an empty authority to {@code java.net.URI}, the host {@code evil.example} to a
 * browser), user info, a host that is not a plain name, a {@code %2e} dot segment — is <b>not</b> allowed. A wrong "no" blocks a script that used to load (visible,
 * fixable by writing the URL plainly); a wrong "yes" would run a script that used to be blocked. Only the first is
 * acceptable.
 *
 * <p>Immutable and thread-safe.
 */
public final class CspScriptAllowlist {

    /** The schemes the WHATWG URL parser treats as "special" (an authority is required after {@code scheme:}). */
    private static final Set<String> SPECIAL_SCHEMES = Set.of("http", "https", "ws", "wss", "ftp", "file");

    private static final Pattern SCHEME_SOURCE = Pattern.compile("^([a-z][a-z0-9+.-]*):$");

    /** {@code [scheme://]host[:port][path]} — host is {@code *}, {@code *.labels} or {@code labels}. */
    private static final Pattern HOST_SOURCE = Pattern.compile(
            "^(?:([a-z][a-z0-9+.-]*)://)?(\\*|(?:\\*\\.)?[a-z0-9-]+(?:\\.[a-z0-9-]+)*)(?::(\\*|[0-9]+))?(/[^?#]*)?$");

    /** A URL's leading scheme, if it has one. */
    private static final Pattern URL_SCHEME = Pattern.compile("^([A-Za-z][A-Za-z0-9+.-]*):");

    private final String sourceList;
    private final boolean self;
    private final boolean wildcard;
    private final boolean unsafeInline;
    private final boolean nonceOrHash;
    private final boolean strictDynamic;
    private final List<String> schemeSources;
    private final List<HostSource> hostSources;

    /** One parsed host source; {@code scheme}, {@code port} and {@code path} are {@code null} when absent. */
    private record HostSource(String scheme, String host, String port, String path) {
    }

    /** A classified script URL: lower-case scheme and host, effective port, and decoded, normalized path. */
    record Target(String scheme, String host, int port, String path) {
    }

    private CspScriptAllowlist(String sourceList) {
        this.sourceList = sourceList == null ? "" : sourceList.trim().replaceAll("\\s+", " ");
        boolean selfSeen = false;
        boolean star = false;
        boolean inline = false;
        boolean nonceHash = false;
        boolean sd = false;
        List<String> schemes = new ArrayList<>();
        List<HostSource> hosts = new ArrayList<>();
        for (String raw : this.sourceList.isEmpty() ? new String[0] : this.sourceList.split(" ")) {
            String token = raw.toLowerCase(Locale.ROOT);
            if (token.startsWith("'")) {
                switch (token) {
                    case "'self'" -> selfSeen = true;
                    case "'unsafe-inline'" -> inline = true;
                    case "'strict-dynamic'" -> sd = true;
                    default -> {
                        if (token.startsWith("'nonce-") || token.startsWith("'sha256-")
                                || token.startsWith("'sha384-") || token.startsWith("'sha512-")) {
                            nonceHash = true;
                        }
                    }
                }
                continue;
            }
            if ("*".equals(token)) {
                star = true;
                continue;
            }
            Matcher scheme = SCHEME_SOURCE.matcher(token);
            if (scheme.matches()) {
                schemes.add(scheme.group(1));
                continue;
            }
            // The path keeps its case (paths are case-sensitive); scheme and host are matched lower-case.
            Matcher host = HOST_SOURCE.matcher(lowerExceptPath(raw));
            if (host.matches()) {
                hosts.add(new HostSource(host.group(1), host.group(2), host.group(3), host.group(4)));
            }
            // Anything else is not a valid source expression: a browser ignores it, and so does this class.
        }
        this.self = selfSeen;
        this.wildcard = star;
        this.unsafeInline = inline;
        this.nonceOrHash = nonceHash;
        this.strictDynamic = sd;
        this.schemeSources = List.copyOf(schemes);
        this.hostSources = List.copyOf(hosts);
    }

    /**
     * Parses a {@code script-src} source list (the tokens after the directive name), for example
     * {@code "'self' https://www.google.com https://www.gstatic.com"}.
     */
    public static CspScriptAllowlist parse(String sourceList) {
        return new CspScriptAllowlist(sourceList);
    }

    /** The shipped default with no hosts contributed: {@code 'self'} only. */
    public static CspScriptAllowlist selfOnly() {
        return parse("'self'");
    }

    /** The normalized source list this allowlist was built from (single spaces). */
    public String sourceList() {
        return sourceList;
    }

    /**
     * Whether the allowlist policy let an <b>inline</b> script (no {@code src}) run: only with {@code 'unsafe-inline'},
     * and only when no nonce/hash source and no {@code 'strict-dynamic'} made the browser ignore it. False for the
     * shipped default — inline scripts were blocked, and stay blocked.
     */
    public boolean allowsInline() {
        return unsafeInline && !nonceOrHash && !strictDynamic;
    }

    /**
     * Whether the allowlist policy let a script with this {@code src} run on a document at {@code document}.
     *
     * @param url      the attribute value, already HTML-decoded (character references resolved)
     * @param base     what a relative URL resolves against: the document URL, or a same-origin {@code <base href>};
     *                 {@code null} when a cross-origin {@code <base>} is in force — relative URLs are then refused
     * @param document the document's own URL, whose origin {@code 'self'} means (never {@code null})
     * @return {@code true} only when a source expression matches; {@code false} for anything unclassifiable
     */
    public boolean allowsUrl(String url, URI base, URI document) {
        if (strictDynamic) {
            return false;
        }
        Target target = classify(url, base);
        Target origin = originOf(document);
        if (target == null || origin == null) {
            return false;
        }
        if (self && matchesSelf(target, origin)) {
            return true;
        }
        if (wildcard && target.host() != null
                && ("http".equals(target.scheme()) || "https".equals(target.scheme())
                    || target.scheme().equals(origin.scheme()))) {
            return true;
        }
        for (String scheme : schemeSources) {
            if (schemePartMatches(scheme, target.scheme())) {
                return true;
            }
        }
        for (HostSource source : hostSources) {
            if (matchesHost(source, target, origin)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolves a {@code <base href>} value: the resolved URL when it is <b>same-origin</b> with {@code document}
     * (relative script URLs may then resolve against it), else {@code null} (cross-origin, or unclassifiable).
     */
    public static URI sameOriginBase(String href, URI document) {
        Target origin = originOf(document);
        URI resolved = resolve(href, document);
        if (origin == null || resolved == null) {
            return null;
        }
        Target target = targetOf(resolved);
        if (target == null || target.host() == null || !target.host().equals(origin.host())
                || !target.scheme().equals(origin.scheme()) || target.port() != origin.port()) {
            return null;
        }
        return resolved;
    }

    /**
     * A short, non-reversible identity of the source list — folded into page ETags (via {@link PublicPageNonce}),
     * because which scripts the edge stamps depends on it: changing the allowlist must move every validator, or a
     * client could revalidate a body stamped under the old list into a {@code 304}.
     */
    public String fingerprint() {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha.digest(("runway-csp-script-allowlist/v1|" + sourceList).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    // ---- URL classification ------------------------------------------------------------------------------------

    /** Classifies a decoded {@code src} value against {@code base}; {@code null} when it cannot be pinned down. */
    static Target classify(String url, URI base) {
        URI resolved = resolve(url, base);
        return resolved == null ? null : targetOf(resolved);
    }

    /**
     * Resolves a decoded attribute URL the way a browser would, or {@code null} wherever this code and a browser
     * could disagree. Absolute URLs never consult {@code base}; relative ones need a non-null {@code base}.
     */
    private static URI resolve(String url, URI base) {
        if (url == null) {
            return null;
        }
        // The URL parser trims leading/trailing C0 controls and spaces, and drops tab/LF/CR anywhere.
        int start = 0;
        int end = url.length();
        while (start < end && url.charAt(start) <= ' ') {
            start++;
        }
        while (end > start && url.charAt(end - 1) <= ' ') {
            end--;
        }
        StringBuilder cleaned = new StringBuilder(end - start);
        for (int i = start; i < end; i++) {
            char c = url.charAt(i);
            if (c == '\t' || c == '\n' || c == '\r') {
                continue;
            }
            if (c < ' ' || c == 0x7F || c == '\\') {
                return null; // a browser reads '\' as '/' in special URLs and the JDK does not: refuse
            }
            cleaned.append(c);
        }
        String value = cleaned.toString();
        if (value.isEmpty()) {
            return null; // src="" never loads a script
        }
        Matcher scheme = URL_SCHEME.matcher(value);
        boolean absolute = scheme.find();
        boolean special = absolute && SPECIAL_SCHEMES.contains(scheme.group(1).toLowerCase(Locale.ROOT));
        if (special && !value.startsWith("//", scheme.end())) {
            return null; // "https:evil.example" is relative-or-absolute depending on the page's scheme: refuse
        }
        // A third slash where the authority starts: RFC 3986 (java.net.URI) reads "///evil.example/x.js" as an EMPTY
        // authority plus the path "/evil.example/x.js" — same-origin — while the WHATWG parser every browser runs
        // skips any number of slashes there and reads the host evil.example (proven in
        // Chrome 155). Same for "https:///evil.example". Refuse rather than model it: nobody writes it on purpose.
        int authorityAt = special ? scheme.end() : (absolute ? -1 : 0);
        if (authorityAt >= 0 && value.startsWith("///", authorityAt)) {
            return null;
        }
        URI ref;
        try {
            ref = new URI(encodeIllegal(value));
        } catch (URISyntaxException malformed) {
            return null;
        }
        if (ref.isAbsolute()) {
            return ref;
        }
        if (base == null) {
            return null;
        }
        try {
            return base.resolve(ref);
        } catch (IllegalArgumentException unresolvable) {
            return null;
        }
    }

    /** The scheme/host/port/path of an absolute URL, or {@code null} for anything with user info or no plain host. */
    private static Target targetOf(URI uri) {
        if (uri.getScheme() == null) {
            return null;
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!SPECIAL_SCHEMES.contains(scheme)) {
            return uri.isOpaque() ? new Target(scheme, null, -1, null) : null; // data:, blob:, … — scheme only
        }
        if (uri.getRawUserInfo() != null || uri.getHost() == null) {
            return null;
        }
        URI normalized = uri.normalize();
        String rawPath = normalized.getRawPath() == null ? "" : normalized.getRawPath();
        if (rawPath.toLowerCase(Locale.ROOT).contains("%2e")) {
            return null; // the URL parser treats %2e as '.', a dot segment the JDK would leave in place
        }
        int port = uri.getPort() >= 0 ? uri.getPort() : defaultPort(scheme);
        String path = normalized.getPath() == null || normalized.getPath().isEmpty() ? "/" : normalized.getPath();
        return new Target(scheme, uri.getHost().toLowerCase(Locale.ROOT), port, path);
    }

    private static Target originOf(URI document) {
        return document == null ? null : targetOf(document);
    }

    /** Percent-encodes what {@link URI} rejects (non-ASCII, space, {@code "<>`{}|^}); a stray {@code %} still fails. */
    private static String encodeIllegal(String value) {
        StringBuilder out = null;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean legal = c < 0x80 && c > ' ' && "\"<>`{}|^".indexOf(c) < 0;
            if (legal) {
                if (out != null) {
                    out.append(c);
                }
                continue;
            }
            if (out == null) {
                out = new StringBuilder(value.length() + 16).append(value, 0, i);
            }
            int cp = value.codePointAt(i);
            if (Character.isSupplementaryCodePoint(cp)) {
                i++;
            }
            for (byte b : new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8)) {
                out.append('%').append(Character.toUpperCase(Character.forDigit((b >> 4) & 0xF, 16)))
                        .append(Character.toUpperCase(Character.forDigit(b & 0xF, 16)));
            }
        }
        return out == null ? value : out.toString();
    }

    // ---- CSP3 matching -----------------------------------------------------------------------------------------

    /** {@code 'self'}: same origin, or the same host reached over the secure scheme with default ports. */
    private static boolean matchesSelf(Target url, Target origin) {
        if (url.host() == null || !url.host().equals(origin.host())) {
            return false;
        }
        if (url.scheme().equals(origin.scheme()) && url.port() == origin.port()) {
            return true;
        }
        boolean portsCompatible = url.port() == origin.port()
                || (url.port() == defaultPort(url.scheme()) && origin.port() == defaultPort(origin.scheme()));
        return portsCompatible && ("https".equals(url.scheme()) || "wss".equals(url.scheme()));
    }

    private static boolean matchesHost(HostSource source, Target url, Target origin) {
        if (url.host() == null) {
            return false;
        }
        String expectedScheme = source.scheme() != null ? source.scheme() : origin.scheme();
        if (!schemePartMatches(expectedScheme, url.scheme())) {
            return false;
        }
        if (source.host().startsWith("*")) {
            if (!url.host().endsWith(source.host().substring(1))) {
                return false; // "*.example.com" ⇒ subdomains only; "*" alone ⇒ any host
            }
        } else if (!source.host().equals(url.host())) {
            return false;
        }
        if (!portMatches(source.port(), url)) {
            return false;
        }
        if (source.path() == null || source.path().isEmpty()) {
            return true;
        }
        String expected = percentDecode(source.path());
        if (expected == null) {
            return false;
        }
        return expected.endsWith("/") ? url.path().startsWith(expected) : url.path().equals(expected);
    }

    private static boolean portMatches(String sourcePort, Target url) {
        if (sourcePort == null) {
            return url.port() == defaultPort(url.scheme());
        }
        if ("*".equals(sourcePort)) {
            return true;
        }
        try {
            return Integer.parseInt(sourcePort) == url.port();
        } catch (NumberFormatException tooLong) {
            return false;
        }
    }

    /** CSP3 scheme-part matching, including the secure upgrades ({@code http}→{@code https}, {@code ws}→…). */
    private static boolean schemePartMatches(String expression, String url) {
        if (expression.equals(url)) {
            return true;
        }
        return switch (expression) {
            case "http" -> "https".equals(url);
            case "ws" -> "wss".equals(url) || "http".equals(url) || "https".equals(url);
            case "wss" -> "https".equals(url);
            default -> false;
        };
    }

    private static int defaultPort(String scheme) {
        return switch (scheme) {
            case "http", "ws" -> 80;
            case "https", "wss" -> 443;
            case "ftp" -> 21;
            default -> -1;
        };
    }

    /** The token lower-cased up to the start of its path (paths are case-sensitive). */
    private static String lowerExceptPath(String token) {
        int authority = token.indexOf("://");
        int from = authority >= 0 ? authority + 3 : 0;
        int slash = token.indexOf('/', from);
        return slash < 0 ? token.toLowerCase(Locale.ROOT)
                : token.substring(0, slash).toLowerCase(Locale.ROOT) + token.substring(slash);
    }

    /** Decodes {@code %XX} escapes (UTF-8); {@code null} on a malformed escape. */
    private static String percentDecode(String path) {
        if (path.indexOf('%') < 0) {
            return path;
        }
        try {
            return new URI("http://h" + path).getPath();
        } catch (URISyntaxException malformed) {
            return null;
        }
    }
}
