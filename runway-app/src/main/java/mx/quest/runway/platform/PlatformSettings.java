package mx.quest.runway.platform;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Platform-wide operator settings — <b>application-realm</b> state that is not tenant-owned and must never
 * live inside a tenant folder (so it can never travel in a tenant backup, unlike a per-site field would).
 * Persisted as {@code external/platform.yml} by {@link PlatformSettingsStore}; an absent file is
 * {@link #EMPTY}.
 *
 * <p>Values are held as plain {@link String}s / boxed {@link Boolean}s / a small nested {@link Smtp} record so
 * this type stays dependency-free (no coupling to the security, site, mail or web packages, hence no package
 * cycle); the consuming layers interpret them. A {@code null} field means <b>"not set — defer to the
 * {@code application.yml} default"</b>; only an explicit value here overrides it.
 *
 * @param defaultSiteId      the id of the catch-all <b>default site</b> that answers requests whose {@code Host}
 *                          is not bound to any other site; {@code null}/blank ⇒ no catch-all (an unmatched host
 *                          404s, the pre-default behaviour)
 * @param passwordStrength   the Argon2id memory-cost profile name ({@code STANDARD}|{@code HIGH}); {@code null}/
 *                          blank ⇒ use the {@code application.yml} default. Applies to <b>new</b> hashes on the
 *                          next launch (Argon2id PHC strings are self-describing, so existing hashes still
 *                          verify).
 * @param nodeName          a human-friendly identity for this node, shown on the About page; {@code null}/blank
 *                          ⇒ the {@code runway.node.name} config, else the OS host name. Applies immediately.
 * @param compressionEnabled master response-compression switch; {@code null} ⇒ the {@code runway.compression
 *                          .enabled} default. Applies immediately (the filter reads it live per request).
 * @param brotliEnabled     whether Brotli is offered (only effective when its native library loaded);
 *                          {@code null} ⇒ the {@code runway.compression.brotli.enabled} default. Applies
 *                          immediately.
 * @param gzipEnabled       whether gzip is offered; {@code null} ⇒ the {@code runway.compression.gzip.enabled}
 *                          default. Applies immediately.
 * @param settings          the console-managed kernel settings (Settings → Server settings and the policy cards on
 *                          Backups, Bandwidth and Logs): full property name → stored text. Loaded below the host's
 *                          {@code application.yml} at startup, so the host's value wins ({@code KernelSettings}).
 *                          Never {@code null}.
 * @param siteQuotas        each site's own monthly (rolling-30-day) outbound bandwidth cap in bytes, set on the
 *                          Sites screen ({@code 0} = unlimited); a site absent here has the platform default
 *                          ({@code runway.bandwidth.quota.default-monthly-bytes}). Read live, so a change applies
 *                          within a minute, without a restart. Kept here, not in the site's own folder, so it never
 *                          travels in a site backup. Never {@code null}.
 * @param embedHosts        the hosts the operator approves for sites to add to their own pages' Content Security
 *                          Policy ({@link EmbedHost}): a site switches on the ones it needs, and only its public pages'
 *                          policy grows. Read live. Never {@code null}.
 * @param logLevels         log levels set on Activity → Logs → Log levels: logger name → level ({@code DEBUG}, …).
 *                          Applied at once and again at every start ({@link LogLevelService}). Never {@code null}.
 * @param siteStorageCeilings each site's own storage ceiling in bytes for its files ({@code static/}: Static files
 *                          and Documents), set on the Sites screen ({@code 0} = unlimited); a site absent here has the
 *                          platform default ({@code runway.static-files.default-max-bytes}). Gates new uploads only,
 *                          never deletes or hides a file. Read live. Kept here, not in the site's folder, so it never
 *                          travels in a site backup. Never {@code null}.
 * @param smtp             the <b>platform mail account</b> used for application-level messages (password
 *                          recovery, and any future operator notifications) for BOTH realms; {@code null} ⇒ no
 *                          platform mail configured (recovery links cannot be sent). This is entirely separate
 *                          from a site's own contact-form relay — the platform never sends a tenant's mail with
 *                          it. Applies immediately (the mailer reads it live).
 */
public record PlatformSettings(String defaultSiteId, String passwordStrength, String nodeName,
                               Boolean compressionEnabled, Boolean brotliEnabled, Boolean gzipEnabled,
                               Smtp smtp, Map<String, String> settings, Map<String, Long> siteQuotas,
                               List<EmbedHost> embedHosts, Map<String, String> logLevels,
                               Map<String, Long> siteStorageCeilings) {

    /** Normalizes the maps and the list to immutable, never-{@code null} values. */
    public PlatformSettings {
        settings = present(settings);
        siteQuotas = present(siteQuotas);
        embedHosts = embedHosts == null ? List.of() : embedHosts.stream().filter(java.util.Objects::nonNull).toList();
        logLevels = present(logLevels);
        siteStorageCeilings = present(siteStorageCeilings);
    }

    /** The form before {@code siteStorageCeilings}. */
    public PlatformSettings(String defaultSiteId, String passwordStrength, String nodeName,
                            Boolean compressionEnabled, Boolean brotliEnabled, Boolean gzipEnabled, Smtp smtp,
                            Map<String, String> settings, Map<String, Long> siteQuotas, List<EmbedHost> embedHosts,
                            Map<String, String> logLevels) {
        this(defaultSiteId, passwordStrength, nodeName, compressionEnabled, brotliEnabled, gzipEnabled, smtp, settings,
                siteQuotas, embedHosts, logLevels, null);
    }

    /** {@code map} sorted and immutable, without the null keys or values a hand-edited file can hold. */
    private static <V> Map<String, V> present(Map<String, V> map) {
        if (map == null) {
            return Map.of();
        }
        TreeMap<String, V> kept = new TreeMap<>();
        map.forEach((key, value) -> {
            if (key != null && value != null) {
                kept.put(key, value);
            }
        });
        return Map.copyOf(kept);
    }

    /** The form before {@code logLevels}. */
    public PlatformSettings(String defaultSiteId, String passwordStrength, String nodeName,
                            Boolean compressionEnabled, Boolean brotliEnabled, Boolean gzipEnabled, Smtp smtp,
                            Map<String, String> settings, Map<String, Long> siteQuotas, List<EmbedHost> embedHosts) {
        this(defaultSiteId, passwordStrength, nodeName, compressionEnabled, brotliEnabled, gzipEnabled, smtp, settings,
                siteQuotas, embedHosts, null);
    }

    /**
     * One host the operator approves for sites' own Content Security Policy.
     *
     * @param id         a stable id sites refer to (letters, digits, dash)
     * @param label      what it is, for the site manager ("YouTube videos")
     * @param source     the CSP source, {@code https://host[:port]} or {@code https://*.host}
     * @param directives the directives it may join, such as {@code frame-src} for an embedded player
     */
    public record EmbedHost(String id, String label, String source, List<String> directives) {

        /** Normalizes {@code directives} to an immutable, never-{@code null} list. */
        public EmbedHost {
            directives = directives == null ? List.of() : List.copyOf(directives);
        }
    }

    /** The form before {@code embedHosts}. */
    public PlatformSettings(String defaultSiteId, String passwordStrength, String nodeName,
                            Boolean compressionEnabled, Boolean brotliEnabled, Boolean gzipEnabled, Smtp smtp,
                            Map<String, String> settings, Map<String, Long> siteQuotas) {
        this(defaultSiteId, passwordStrength, nodeName, compressionEnabled, brotliEnabled, gzipEnabled, smtp, settings,
                siteQuotas, null);
    }

    /** The form before {@code siteQuotas}. */
    public PlatformSettings(String defaultSiteId, String passwordStrength, String nodeName,
                            Boolean compressionEnabled, Boolean brotliEnabled, Boolean gzipEnabled, Smtp smtp,
                            Map<String, String> settings) {
        this(defaultSiteId, passwordStrength, nodeName, compressionEnabled, brotliEnabled, gzipEnabled, smtp, settings,
                null);
    }

    /** The form before {@code settings}. */
    public PlatformSettings(String defaultSiteId, String passwordStrength, String nodeName,
                            Boolean compressionEnabled, Boolean brotliEnabled, Boolean gzipEnabled, Smtp smtp) {
        this(defaultSiteId, passwordStrength, nodeName, compressionEnabled, brotliEnabled, gzipEnabled, smtp, null,
                null);
    }

    /**
     * The platform SMTP relay — the account the platform mailer authenticates and sends through. Stored as a
     * nested {@code smtp:} block in {@code platform.yml}. The password is held {@code ENC(...)}-encrypted (a
     * {@code secret}); it is decrypted only at send time by the mail layer and never surfaced to the settings
     * form (which masks it). {@code null} components defer to a sane default (port 587, no auth).
     *
     * @param host     the SMTP server host; blank ⇒ platform mail is not configured (recovery cannot send)
     * @param port     the SMTP server port ({@code null} ⇒ 587)
     * @param auth     whether to authenticate (SMTP AUTH) with {@code username}/{@code password} ({@code null} ⇒ false)
     * @param username the SMTP username (used only when {@code auth})
     * @param password the SMTP password as the stored {@code ENC(...)} ciphertext (decrypted at send time)
     * @param from     the envelope/From address platform messages are sent as; blank ⇒ not configured
     * @param starttls whether to upgrade a plain connection with STARTTLS ({@code null} ⇒ false)
     * @param ssl      whether to open an implicit-TLS (SMTPS) connection ({@code null} ⇒ false)
     */
    public record Smtp(String host, Integer port, Boolean auth, String username, String password, String from,
                       Boolean starttls, Boolean ssl) {

        /** Whether enough is set to attempt a send: a non-blank host AND a non-blank From address. */
        public boolean isConfigured() {
            return host != null && !host.isBlank() && from != null && !from.isBlank();
        }

        /** Never leak the (encrypted) password in diagnostics. */
        @Override
        public String toString() {
            return "Smtp[host=" + host + ", port=" + port + ", auth=" + auth + ", username=" + username
                    + ", from=" + from + ", starttls=" + starttls + ", ssl=" + ssl
                    + ", password=" + (password == null || password.isBlank() ? "<none>" : "<set>") + "]";
        }
    }

    /** The all-defaults value: no catch-all site, no platform mail, every setting deferring to {@code application.yml}. */
    public static final PlatformSettings EMPTY = new PlatformSettings(null, null, null, null, null, null, null);

    /**
     * Back-compatible constructor for the settings that predate the platform mailer (no {@code smtp} block). Kept
     * so pre-existing call sites and stored files (which carried no SMTP block) continue to work — the
     * record-evolution rule from the SPI guidance, applied to this internal store.
     */
    public PlatformSettings(String defaultSiteId, String passwordStrength, String nodeName,
                            Boolean compressionEnabled, Boolean brotliEnabled, Boolean gzipEnabled) {
        this(defaultSiteId, passwordStrength, nodeName, compressionEnabled, brotliEnabled, gzipEnabled, null);
    }

    /**
     * Back-compatible constructor for the two original fields (default site + password strength); the
     * node-name, compression overrides and SMTP block default to "not set". Kept so pre-existing call sites and
     * stored files (which only carried those two fields) continue to work.
     */
    public PlatformSettings(String defaultSiteId, String passwordStrength) {
        this(defaultSiteId, passwordStrength, null, null, null, null, null);
    }

    /** The configured catch-all default-site id, if any (blank normalised away). */
    public Optional<String> defaultSite() {
        return trimmed(defaultSiteId);
    }

    /** The configured password-strength profile name, if any (blank normalised away). */
    public Optional<String> strength() {
        return trimmed(passwordStrength);
    }

    /** The configured node-name override, if any (blank normalised away); empty ⇒ defer to config/host name. */
    public Optional<String> node() {
        return trimmed(nodeName);
    }

    /** This value with a new {@link #defaultSiteId} ({@code null}/blank clears the catch-all); others preserved. */
    public PlatformSettings withDefaultSiteId(String id) {
        return new PlatformSettings(id, passwordStrength, nodeName, compressionEnabled, brotliEnabled, gzipEnabled, smtp,
                settings, siteQuotas, embedHosts, logLevels, siteStorageCeilings);
    }

    /** This value with a new {@link #passwordStrength} ({@code null}/blank restores the default); others preserved. */
    public PlatformSettings withPasswordStrength(String strength) {
        return new PlatformSettings(defaultSiteId, strength, nodeName, compressionEnabled, brotliEnabled, gzipEnabled,
                smtp, settings, siteQuotas, embedHosts, logLevels, siteStorageCeilings);
    }

    /** This value with a new {@link #smtp} platform-mail block ({@code null} clears it); others preserved. */
    public PlatformSettings withSmtp(Smtp mail) {
        return new PlatformSettings(defaultSiteId, passwordStrength, nodeName, compressionEnabled, brotliEnabled,
                gzipEnabled, mail, settings, siteQuotas, embedHosts, logLevels, siteStorageCeilings);
    }

    /**
     * With {@code newSettings} as the console-managed kernel settings ({@code settings:} in {@code platform.yml}):
     * full property names to their stored text ({@code ENC(...)} for a secret). See {@code KernelSettings}.
     */
    public PlatformSettings withSettings(Map<String, String> newSettings) {
        return new PlatformSettings(defaultSiteId, passwordStrength, nodeName, compressionEnabled, brotliEnabled,
                gzipEnabled, smtp, newSettings, siteQuotas, embedHosts, logLevels, siteStorageCeilings);
    }

    /** With {@code levels} as the console's log levels (logger name → level). */
    public PlatformSettings withLogLevels(Map<String, String> levels) {
        return new PlatformSettings(defaultSiteId, passwordStrength, nodeName, compressionEnabled, brotliEnabled,
                gzipEnabled, smtp, settings, siteQuotas, embedHosts, levels, siteStorageCeilings);
    }

    /** With {@code hosts} as the approved embed hosts. */
    public PlatformSettings withEmbedHosts(List<EmbedHost> hosts) {
        return new PlatformSettings(defaultSiteId, passwordStrength, nodeName, compressionEnabled, brotliEnabled,
                gzipEnabled, smtp, settings, siteQuotas, hosts, logLevels, siteStorageCeilings);
    }

    /**
     * With {@code bytes} as site {@code siteId}'s own bandwidth cap ({@code 0} = unlimited), or without one when
     * {@code bytes} is {@code null}, so the platform default applies again.
     */
    public PlatformSettings withSiteQuota(String siteId, Long bytes) {
        Map<String, Long> next = new TreeMap<>(siteQuotas);
        if (bytes == null) {
            next.remove(siteId);
        } else {
            next.put(siteId, bytes);
        }
        return new PlatformSettings(defaultSiteId, passwordStrength, nodeName, compressionEnabled, brotliEnabled,
                gzipEnabled, smtp, settings, next, embedHosts, logLevels, siteStorageCeilings);
    }

    /**
     * With {@code bytes} as site {@code siteId}'s own storage ceiling ({@code 0} = unlimited), or without one when
     * {@code bytes} is {@code null}, so the platform default applies again.
     */
    public PlatformSettings withSiteStorageCeiling(String siteId, Long bytes) {
        Map<String, Long> next = new TreeMap<>(siteStorageCeilings);
        if (bytes == null) {
            next.remove(siteId);
        } else {
            next.put(siteId, bytes);
        }
        return new PlatformSettings(defaultSiteId, passwordStrength, nodeName, compressionEnabled, brotliEnabled,
                gzipEnabled, smtp, settings, siteQuotas, embedHosts, logLevels, next);
    }

    private static Optional<String> trimmed(String s) {
        return (s == null || s.isBlank()) ? Optional.empty() : Optional.of(s.trim());
    }
}
