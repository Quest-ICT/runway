package mx.quest.runway.console;

import jakarta.mail.MessagingException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import mx.quest.runway.crypto.SecretCipher;
import mx.quest.runway.log.AppActivityLog;
import mx.quest.runway.mail.OAuth2TokenClient;
import mx.quest.runway.mail.PlatformMailer;
import mx.quest.runway.platform.HostSettings;
import mx.quest.runway.platform.PlatformSettings;
import mx.quest.runway.platform.PlatformSettingsStore;
import mx.quest.runway.security.PasswordStrength;
import mx.quest.runway.security.RunwaySecurityProperties;
import mx.quest.runway.site.SiteStore;
import mx.quest.runway.web.CompressionFilter;
import mx.quest.runway.web.TemplateCacheService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The application-realm <b>Settings</b> screen ({@code /__app/settings}) — platform-wide operator settings
 * held in {@code external/platform.yml} ({@link PlatformSettingsStore}), not per-tenant and outside every
 * tenant folder. A <b>platform</b> operation, gated to the master/wildcard admin via {@link PlatformAuthority}.
 *
 * <p>The settings:
 * <ul>
 *   <li><b>Default site</b> — the catch-all that answers requests whose Host isn't bound to another site.
 *       Applies immediately (site resolution reads it live).</li>
 *   <li><b>Password strength</b> — the Argon2id memory-cost profile. Applies to <b>new</b> hashes on the next
 *       restart (existing PHC hashes verify regardless).</li>
 *   <li><b>Node name</b> — a human-friendly identity for this node, shown on About. Blank defers to the
 *       {@code runway.node.name} config, else the host name. Applies immediately.</li>
 *   <li><b>Response compression</b> — a master switch plus per-encoding Brotli / gzip switches. Applies
 *       immediately: {@link CompressionFilter} resolves them live per request.</li>
 *   <li><b>Platform mail (SMTP)</b> — the account used for application-level messages (password recovery, and
 *       any future operator notifications) for BOTH realms; never for site-generated mail. It signs in with a
 *       password or with an OAuth2 token (XOAUTH2, for Microsoft 365). The password and the OAuth2 client secret
 *       are stored {@code ENC(...)}-encrypted ({@link SecretCipher}) and masked on the form (a blank field on save
 *       keeps the stored secret). A "Send test email" action verifies the account, and says when the token rather
 *       than the mail server failed. Applies immediately.</li>
 * </ul>
 *
 * <p>Plus one maintenance action, <b>Clear template cache</b> ({@link #clearTemplateCache}). It sits here rather
 * than on the read-only About page because it mutates server state and needs this screen's platform-admin gate.
 */
@Controller
public class AppSettingsController {

    private final PlatformSettingsStore platform;
    private final SiteStore sites;
    private final RunwaySecurityProperties security;
    private final PlatformAuthority platformAuthority;
    private final CompressionFilter compression;
    private final SecretCipher cipher;
    private final PlatformMailer mailer;
    private final AppActivityLog activity;
    private final TemplateCacheService templateCache;
    private final MessageSource messages;
    private final HostSettings host;

    private static final String COMPRESSION_ENABLED = "runway.compression.enabled";
    private static final String BROTLI_ENABLED = "runway.compression.brotli.enabled";
    private static final String GZIP_ENABLED = "runway.compression.gzip.enabled";

    public AppSettingsController(PlatformSettingsStore platform, SiteStore sites,
                                 RunwaySecurityProperties security, PlatformAuthority platformAuthority,
                                 CompressionFilter compression, SecretCipher cipher, PlatformMailer mailer,
                                 AppActivityLog activity, TemplateCacheService templateCache,
                                 @Qualifier("operatorAwareMessageSource") MessageSource messages,
                                 HostSettings host) {
        this.host = host;
        this.templateCache = templateCache;
        this.messages = messages;
        this.platform = platform;
        this.sites = sites;
        this.security = security;
        this.platformAuthority = platformAuthority;
        this.compression = compression;
        this.cipher = cipher;
        this.mailer = mailer;
        this.activity = activity;
    }

    @GetMapping("/__app/settings")
    public String form(Authentication authentication, Model model) {
        platformAuthority.require(authentication);
        populate(model, platform.get());
        model.addAttribute("contentTemplate", "operator/app/settings");
        model.addAttribute("pageTitleKey", "op.settings.platform.title");
        return "operator/op-layout";
    }

    @PostMapping("/__app/settings")
    public String save(@RequestParam(required = false) String defaultSiteId,
                       @RequestParam(required = false) String passwordStrength,
                       @RequestParam(required = false) String nodeName,
                       @RequestParam(name = "compressionEnabled", defaultValue = "false") boolean compressionEnabled,
                       @RequestParam(name = "brotliEnabled", defaultValue = "false") boolean brotliEnabled,
                       @RequestParam(name = "gzipEnabled", defaultValue = "false") boolean gzipEnabled,
                       @RequestParam(required = false) String smtpHost,
                       @RequestParam(required = false) String smtpPort,
                       @RequestParam(name = "smtpAuth", defaultValue = "false") boolean smtpAuth,
                       @RequestParam(required = false) String smtpUsername,
                       @RequestParam(required = false) String smtpPassword,
                       @RequestParam(required = false) String smtpFrom,
                       @RequestParam(name = "smtpStarttls", defaultValue = "false") boolean smtpStarttls,
                       @RequestParam(name = "smtpSsl", defaultValue = "false") boolean smtpSsl,
                       @RequestParam(required = false) String smtpAuthMethod,
                       @RequestParam(required = false) String smtpOauthTokenUrl,
                       @RequestParam(required = false) String smtpOauthClientId,
                       @RequestParam(required = false) String smtpOauthClientSecret,
                       @RequestParam(required = false) String smtpOauthScope,
                       Authentication authentication, RedirectAttributes redirect) {
        platformAuthority.require(authentication);
        try {
            String site = normalize(defaultSiteId);
            String strength = normalize(passwordStrength);
            if (strength != null) {
                // Validate against the enum so a bad value never reaches the encoder factory at startup.
                PasswordStrength.valueOf(strength.toUpperCase(Locale.ROOT));
                strength = strength.toUpperCase(Locale.ROOT);
            }
            String level = strength;
            List<String> cleared = new ArrayList<>();
            AtomicReference<PlatformSettings> before = new AtomicReference<>();
            AtomicReference<PlatformSettings> after = new AtomicReference<>();
            // A checkbox submits nothing when unchecked (→ the false default) and its value when checked; so the
            // three compression flags are always explicit here — stored as an override the live filter reads.
            // A switch the server itself sets is not stored: the host's value wins (one home per setting).
            Boolean compressionOverride = host.setOnHost(COMPRESSION_ENABLED) ? null : compressionEnabled;
            Boolean brotliOverride = host.setOnHost(BROTLI_ENABLED) ? null : brotliEnabled;
            Boolean gzipOverride = host.setOnHost(GZIP_ENABLED) ? null : gzipEnabled;
            // One atomic change against the latest settings: the SMTP block keeps the stored password on a blank
            // field, and every field this screen does not own (kernel settings, quotas, embed hosts, log levels)
            // is carried over as it is now, not as it was when this request started.
            // The site's existence is checked inside the change: a site deleted while this saves cannot become the
            // default after its deletion cleared the pointer (the deletion's own change waits for this one).
            platform.update(current -> {
                if (site != null && !sites.exists(site)) {
                    throw new IllegalArgumentException("no such site: " + site);
                }
                cleared.clear();
                PlatformSettings next = new PlatformSettings(site, level, normalize(nodeName),
                    compressionOverride, brotliOverride, gzipOverride,
                    smtp(current.smtp(), smtpHost, smtpPort, smtpAuth, smtpUsername, smtpPassword, smtpFrom,
                            smtpStarttls, smtpSsl, new OAuthForm(smtpAuthMethod, smtpOauthTokenUrl,
                                    smtpOauthClientId, smtpOauthClientSecret, smtpOauthScope), cleared),
                    current.settings(), current.siteQuotas(), current.embedHosts(), current.logLevels(),
                    current.siteStorageCeilings());
                before.set(current);
                after.set(next);
                return next;
            });
            // What changed, by name, never a value: who repointed the mail account, and when, is on the record.
            List<String> changed = changedFields(before.get(), after.get());
            String detail = String.join(", ", changed)
                    + (cleared.isEmpty() ? "" : " (cleared " + String.join(", ", cleared) + ")");
            activity.record(authentication.getName(), "platform.settings-changed", detail);
            redirect.addFlashAttribute("flash", cleared.isEmpty() ? "Platform settings saved."
                    : "Platform settings saved. The stored " + String.join(" and ", cleared)
                            + " was cleared because the server it is sent to changed: enter it again.");
        } catch (IllegalArgumentException invalid) {
            redirect.addFlashAttribute("error", invalid.getMessage());
        }
        return "redirect:/__app/settings";
    }

    /**
     * Sends a diagnostic message through the platform relay to verify the SMTP account from the console. The
     * recipient defaults to the configured From address when {@code testTo} is blank.
     */
    @PostMapping("/__app/settings/test-email")
    public String testEmail(@RequestParam(required = false) String testTo,
                            Authentication authentication, RedirectAttributes redirect) {
        platformAuthority.require(authentication);
        PlatformSettings.Smtp smtp = platform.get().smtp();
        if (smtp == null || !smtp.isConfigured()) {
            redirect.addFlashAttribute("error", "Configure and save the platform mail account first.");
            return "redirect:/__app/settings";
        }
        String to = normalize(testTo) != null ? normalize(testTo) : smtp.from();
        try {
            mailer.send(to, "Runway platform mail test",
                    "This is a test message from Runway.\n\n"
                            + "If you received it, the platform mail account is configured correctly and can be "
                            + "used for password recovery and other application messages.");
            activity.record(authentication.getName(), "platform.test-email", to);
            redirect.addFlashAttribute("flash", "Test email sent to " + to + ".");
        } catch (OAuth2TokenClient.TokenException e) {
            // Its own message already names the endpoint and the cause; the root cause alone would not say OAuth2.
            redirect.addFlashAttribute("error", "Test email failed before reaching the mail server: no OAuth2 token. "
                    + e.getMessage());
        } catch (MessagingException e) {
            redirect.addFlashAttribute("error", "Test email failed: " + rootMessage(e));
        }
        return "redirect:/__app/settings";
    }

    /**
     * Clears the parsed-template cache ({@link TemplateCacheService#evictAll()}). Runway runs the Thymeleaf cache
     * ON, evicts precisely on every console edit, and re-reads a template whose file changed on disk on its next use
     * ({@code FileStampValidity}), so this is not needed in normal use; it is a backstop (e.g. a file system whose
     * timestamps cannot be trusted). It affects <b>this node only</b> — on a cluster, run it on
     * each node. Harmless: the next request of each page re-parses its template. Platform-admin gated like the
     * rest of this screen, and recorded in the activity log.
     */
    @PostMapping("/__app/settings/clear-template-cache")
    public String clearTemplateCache(Authentication authentication, RedirectAttributes redirect) {
        platformAuthority.require(authentication);
        templateCache.evictAll();
        activity.record(authentication.getName(), "platform.template-cache-cleared", "");
        redirect.addFlashAttribute("flash", messages.getMessage("op.settings.platform.cache.cleared", null,
                "Template cache cleared.", LocaleContextHolder.getLocale()));
        return "redirect:/__app/settings";
    }

    /** The OAuth2 sign-in fields of the platform mail form, as submitted. */
    private record OAuthForm(String method, String tokenUrl, String clientId, String clientSecret, String scope) {
    }

    /**
     * Builds the SMTP block from the submitted form, encrypting a newly-entered password or OAuth2 client secret and
     * <b>preserving</b> the stored one when its field is left blank (the masked-secret convention). Returns
     * {@code null} when nothing is set, so an untouched form does not litter {@code platform.yml} with an
     * all-blank block.
     *
     * <p>An account set to OAuth2 sign-in is checked here, so a mistake is reported on save rather than by the first
     * password-recovery mail that fails: it needs TLS (STARTTLS or implicit), a token endpoint Runway will call
     * ({@code https}, or {@code http} to loopback), a client ID and a client secret.
     *
     * <p><b>A stored secret follows its server.</b> Kept on a blank field only while the settings that say where it
     * is sent stay the same: the password while host, port and username do, the client secret while the token
     * endpoint and client ID do, and, for an OAuth2 account, the mail server (host and port) and the scope the token is
     * minted for and sent with. Otherwise someone allowed to edit this screen, who does not know the secret, could
     * point the account at a server of theirs and have Runway send it there (the rule plugin settings follow with
     * {@code secretBoundTo}). A secret dropped this way is named in {@code cleared}; an OAuth2 account whose client
     * secret was dropped is refused, since it cannot sign in without one.
     */
    private PlatformSettings.Smtp smtp(PlatformSettings.Smtp current, String host, String port, boolean auth,
                                       String username, String password, String from, boolean starttls,
                                       boolean ssl, OAuthForm oauth, List<String> cleared) {
        String h = normalize(host);
        String u = normalize(username);
        String f = normalize(from);
        Integer p = port(port);
        String method = PlatformSettings.Smtp.AUTH_OAUTH2.equals(normalize(oauth.method()))
                ? PlatformSettings.Smtp.AUTH_OAUTH2 : PlatformSettings.Smtp.AUTH_PASSWORD;
        String tokenUrl = normalize(oauth.tokenUrl());
        String clientId = normalize(oauth.clientId());
        String scope = normalize(oauth.scope());
        boolean passwordMoved = current != null && (!sameHost(current.host(), h) || !Objects.equals(current.port(), p)
                || !Objects.equals(normalize(current.username()), u));
        // The client secret goes to the token endpoint; the token minted from it goes to the mail server, for the
        // scope asked: both follow it. Only for an OAuth2 account, so a password account whose hidden OAuth2 fields
        // ride along is not told a secret was cleared when its host moves.
        boolean oauthAccount = current != null && (PlatformSettings.Smtp.AUTH_OAUTH2.equals(current.authMethod())
                || PlatformSettings.Smtp.AUTH_OAUTH2.equals(method));
        boolean clientSecretMoved = current != null && (!Objects.equals(normalize(current.oauthTokenUrl()), tokenUrl)
                || !Objects.equals(normalize(current.oauthClientId()), clientId)
                || oauthAccount && (!sameHost(current.host(), h) || !Objects.equals(current.port(), p)
                        || !Objects.equals(normalize(current.oauthScope()), scope)));
        String secret = secret(normalize(password), current != null ? current.password() : null, "an SMTP password",
                passwordMoved, "SMTP password", cleared);
        String clientSecret = secret(normalize(oauth.clientSecret()),
                current != null ? current.oauthClientSecret() : null, "an OAuth2 client secret",
                clientSecretMoved, "OAuth2 client secret", cleared);
        if (auth && method.equals(PlatformSettings.Smtp.AUTH_OAUTH2)) {
            if (cleared.contains("OAuth2 client secret")) {
                throw new IllegalArgumentException("Enter the OAuth2 client secret again: the token endpoint, client "
                        + "ID, mail server or scope changed, and neither the stored secret nor a token minted from it "
                        + "goes to a new one.");
            }
            if (!starttls && !ssl) {
                throw new IllegalArgumentException("OAuth2 sign-in needs STARTTLS or implicit TLS.");
            }
            try {
                // The stored secret stays encrypted; only its presence matters for this check.
                OAuth2TokenClient.endpoint(new OAuth2TokenClient.Request(tokenUrl, clientId, clientSecret, scope));
            } catch (MessagingException invalid) {
                throw new IllegalArgumentException("Platform mail: " + invalid.getMessage() + ".");
            }
        }
        boolean oauthEmpty = tokenUrl == null && clientId == null && clientSecret == null && scope == null;
        boolean empty = h == null && f == null && u == null
                && (secret == null || secret.isBlank()) && !auth && !starttls && !ssl && p == null && oauthEmpty;
        if (empty) {
            return null;
        }
        return new PlatformSettings.Smtp(h, p, auth, u, secret, f, starttls, ssl, method, tokenUrl, clientId,
                clientSecret, scope);
    }

    /**
     * A newly entered secret, encrypted; or, when the field was left blank, the stored ciphertext, unless the server
     * it is sent to {@code moved}: then nothing, and {@code name} is added to {@code cleared}.
     */
    private String secret(String entered, String stored, String what, boolean moved, String name,
                          List<String> cleared) {
        if (entered == null) {
            if (stored == null || stored.isBlank()) {
                return null;
            }
            if (moved) {
                cleared.add(name);
                return null;
            }
            return stored; // blank field, same server ⇒ keep the stored secret
        }
        if (!cipher.isAvailable()) {
            throw new IllegalArgumentException("Cannot store " + what + ": no encryption key is "
                    + "configured (set runway.security.secret-key).");
        }
        return cipher.encrypt(entered);
    }

    private static boolean sameHost(String a, String b) {
        String x = normalize(a);
        String y = normalize(b);
        return x == null ? y == null : y != null && x.equalsIgnoreCase(y);
    }

    /**
     * The names of the settings this save changed (never their values), for the activity log: the platform fields
     * this screen owns, and each field of the mail account.
     */
    static List<String> changedFields(PlatformSettings before, PlatformSettings after) {
        List<String> changed = new ArrayList<>();
        if (before == null || after == null) {
            return changed;
        }
        diff(changed, "default site", before.defaultSiteId(), after.defaultSiteId());
        diff(changed, "password strength", before.passwordStrength(), after.passwordStrength());
        diff(changed, "node name", before.nodeName(), after.nodeName());
        diff(changed, "compression", before.compressionEnabled(), after.compressionEnabled());
        diff(changed, "brotli", before.brotliEnabled(), after.brotliEnabled());
        diff(changed, "gzip", before.gzipEnabled(), after.gzipEnabled());
        PlatformSettings.Smtp b = before.smtp();
        PlatformSettings.Smtp a = after.smtp();
        diff(changed, "mail host", b == null ? null : b.host(), a == null ? null : a.host());
        diff(changed, "mail port", b == null ? null : b.port(), a == null ? null : a.port());
        diff(changed, "mail sign-in", b == null ? null : b.auth(), a == null ? null : a.auth());
        diff(changed, "mail sign-in method", b == null ? null : b.authMethod(), a == null ? null : a.authMethod());
        diff(changed, "mail username", b == null ? null : b.username(), a == null ? null : a.username());
        diff(changed, "mail password", b == null ? null : b.password(), a == null ? null : a.password());
        diff(changed, "mail from", b == null ? null : b.from(), a == null ? null : a.from());
        diff(changed, "mail STARTTLS", b == null ? null : b.starttls(), a == null ? null : a.starttls());
        diff(changed, "mail TLS", b == null ? null : b.ssl(), a == null ? null : a.ssl());
        diff(changed, "OAuth2 token endpoint", b == null ? null : b.oauthTokenUrl(),
                a == null ? null : a.oauthTokenUrl());
        diff(changed, "OAuth2 client ID", b == null ? null : b.oauthClientId(), a == null ? null : a.oauthClientId());
        diff(changed, "OAuth2 client secret", b == null ? null : b.oauthClientSecret(),
                a == null ? null : a.oauthClientSecret());
        diff(changed, "OAuth2 scope", b == null ? null : b.oauthScope(), a == null ? null : a.oauthScope());
        return changed;
    }

    private static void diff(List<String> changed, String name, Object before, Object after) {
        if (!Objects.equals(before, after)) {
            changed.add(name);
        }
    }

    private void populate(Model model, PlatformSettings current) {
        model.addAttribute("sites", sites.findAll());
        model.addAttribute("currentDefaultSiteId", current.defaultSite().orElse(""));
        model.addAttribute("passwordStrengths", PasswordStrength.values());
        model.addAttribute("currentPasswordStrength", current.strength().orElse(""));
        // The profile in force when no override is set (the config default) — shown as the effective baseline.
        model.addAttribute("configDefaultStrength", security.getPasswordStrength().name());
        model.addAttribute("currentNodeName", current.node().orElse(""));
        // Compression switches seed from the EFFECTIVE current state (platform override, else config default),
        // read from the filter so the form and the running behaviour never disagree.
        model.addAttribute("compressionEnabled", compression.enabled());
        model.addAttribute("brotliEnabled", compression.brotliOn());
        model.addAttribute("gzipEnabled", compression.gzipOn());
        // A switch the server itself sets is shown locked, with where it is set.
        model.addAttribute("compLock", host.origin(COMPRESSION_ENABLED).orElse(null));
        model.addAttribute("brotliLock", host.origin(BROTLI_ENABLED).orElse(null));
        model.addAttribute("gzipLock", host.origin(GZIP_ENABLED).orElse(null));
        model.addAttribute("brotliNativeAvailable", compression.brotliNativeAvailable());
        // Platform mail: seed the visible fields; the secret is NEVER sent to the form — only a "configured" flag.
        PlatformSettings.Smtp smtp = current.smtp();
        model.addAttribute("smtpHost", smtp != null ? orEmpty(smtp.host()) : "");
        model.addAttribute("smtpPort", smtp != null ? smtp.port() : null);
        model.addAttribute("smtpAuth", smtp != null && Boolean.TRUE.equals(smtp.auth()));
        model.addAttribute("smtpUsername", smtp != null ? orEmpty(smtp.username()) : "");
        model.addAttribute("smtpFrom", smtp != null ? orEmpty(smtp.from()) : "");
        model.addAttribute("smtpStarttls", smtp != null && Boolean.TRUE.equals(smtp.starttls()));
        model.addAttribute("smtpSsl", smtp != null && Boolean.TRUE.equals(smtp.ssl()));
        model.addAttribute("smtpPasswordConfigured",
                smtp != null && smtp.password() != null && !smtp.password().isBlank());
        model.addAttribute("smtpAuthMethod", smtp != null && PlatformSettings.Smtp.AUTH_OAUTH2.equals(smtp.authMethod())
                ? PlatformSettings.Smtp.AUTH_OAUTH2 : PlatformSettings.Smtp.AUTH_PASSWORD);
        model.addAttribute("smtpOauthTokenUrl", smtp != null ? orEmpty(smtp.oauthTokenUrl()) : "");
        model.addAttribute("smtpOauthClientId", smtp != null ? orEmpty(smtp.oauthClientId()) : "");
        model.addAttribute("smtpOauthScope", smtp != null ? orEmpty(smtp.oauthScope()) : "");
        model.addAttribute("smtpOauthSecretConfigured",
                smtp != null && smtp.oauthClientSecret() != null && !smtp.oauthClientSecret().isBlank());
        model.addAttribute("smtpConfigured", smtp != null && smtp.isConfigured());
    }

    /** Trims and maps blank to {@code null} (blank = "unset this setting"). */
    private static String normalize(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    /** Parses the port field: blank ⇒ {@code null} (use the default); otherwise validated to 1..65535. */
    private static Integer port(String raw) {
        String v = normalize(raw);
        if (v == null) {
            return null;
        }
        int port;
        try {
            port = Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("SMTP port must be a number (1..65535).");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("SMTP port must be in 1..65535.");
        }
        return port;
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String m = cur.getMessage();
        return (m == null || m.isBlank()) ? cur.getClass().getSimpleName() : m;
    }
}
