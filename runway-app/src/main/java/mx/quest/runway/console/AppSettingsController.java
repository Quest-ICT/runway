package mx.quest.runway.console;

import jakarta.mail.MessagingException;
import java.util.Locale;
import mx.quest.runway.crypto.SecretCipher;
import mx.quest.runway.log.AppActivityLog;
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
 *       any future operator notifications) for BOTH realms; never for site-generated mail. The password is
 *       stored {@code ENC(...)}-encrypted ({@link SecretCipher}) and masked on the form (a blank field on save
 *       keeps the stored secret). A "Send test email" action verifies the account. Applies immediately.</li>
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
                return new PlatformSettings(site, level, normalize(nodeName),
                    compressionOverride, brotliOverride, gzipOverride,
                    smtp(current.smtp(), smtpHost, smtpPort, smtpAuth, smtpUsername, smtpPassword, smtpFrom,
                            smtpStarttls, smtpSsl),
                    current.settings(), current.siteQuotas(), current.embedHosts(), current.logLevels(),
                    current.siteStorageCeilings());
            });
            activity.record(authentication.getName(), "platform.settings-changed", "");
            redirect.addFlashAttribute("flash", "Platform settings saved.");
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

    /**
     * Builds the SMTP block from the submitted form, encrypting a newly-entered password and <b>preserving</b>
     * the stored secret when the password field is left blank (the masked-secret convention). Returns
     * {@code null} when nothing is set, so an untouched form does not litter {@code platform.yml} with an
     * all-blank block.
     */
    private PlatformSettings.Smtp smtp(PlatformSettings.Smtp current, String host, String port, boolean auth,
                                       String username, String password, String from, boolean starttls,
                                       boolean ssl) {
        String h = normalize(host);
        String u = normalize(username);
        String f = normalize(from);
        Integer p = port(port);
        String stored = current != null ? current.password() : null;
        String secret;
        if (normalize(password) == null) {
            secret = stored; // blank field ⇒ keep the stored (encrypted) secret
        } else {
            if (!cipher.isAvailable()) {
                throw new IllegalArgumentException("Cannot store an SMTP password: no encryption key is "
                        + "configured (set runway.security.secret-key).");
            }
            secret = cipher.encrypt(password.trim());
        }
        boolean empty = h == null && f == null && u == null
                && (secret == null || secret.isBlank()) && !auth && !starttls && !ssl && p == null;
        if (empty) {
            return null;
        }
        return new PlatformSettings.Smtp(h, p, auth, u, secret, f, starttls, ssl);
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
