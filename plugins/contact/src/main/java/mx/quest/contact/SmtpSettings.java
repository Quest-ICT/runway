package mx.quest.contact;

import java.util.Objects;
import java.util.Optional;
import mx.quest.runway.spi.model.SettingsView;

/**
 * The per-site SMTP relay configuration, materialized from a site's {@code contact} plugin settings (the
 * {@code smtp*} + {@code fromAddress}/{@code toAddress} {@code siteSettings}). Immutable; a pure value.
 *
 * <p><b>Isolation.</b> Every field comes from <em>this</em> site's own {@code sites/<id>/plugins/contact.yml};
 * a site can therefore only ever send through the relay <em>it</em> configured — never another tenant's.
 *
 * <p><b>Secret handling.</b> The password is a {@code secret} setting, stored {@code ENC(...)}-encrypted by the
 * platform. It never appears in the {@link SettingsView} (the kernel leaves secret keys out of it); the caller
 * hands it over separately, already in clear, from {@code PluginSettings.secret(siteId, "smtpPassword")}. The
 * plaintext lives only in this transient value and is handed straight to Jakarta Mail, never logged (see
 * {@link #toString()}) and never stored by this plugin.
 *
 * @param host      the SMTP server host; blank ⇒ {@link #isConfigured()} is false (mail is skipped)
 * @param port      the SMTP server port (1..65535; default 25)
 * @param auth      whether to authenticate (SMTP AUTH) with {@code username}/{@code password}
 * @param username  the SMTP username (used only when {@code auth})
 * @param password  the SMTP password in PLAINTEXT (already decrypted; used only when {@code auth})
 * @param starttls  whether to upgrade a plain connection with STARTTLS
 * @param ssl       whether to open an implicit-TLS (SMTPS) connection
 * @param from      the envelope/From address; blank ⇒ falls back to {@code to}
 * @param to        the recipient address; blank ⇒ {@link #isConfigured()} is false (mail is skipped)
 */
public record SmtpSettings(String host, int port, boolean auth, String username, String password,
                           boolean starttls, boolean ssl, String from, String to) {

    /**
     * Reads the SMTP relay from a site's contact settings.
     *
     * @param view     the site's effective contact settings ({@code PluginSettings.site}) — declared defaults
     *                 overlaid by what the operator saved; secret keys are absent from it by contract
     * @param password the {@code smtpPassword} secret in clear ({@code PluginSettings.secret}); empty when unset
     *                 or undecryptable — the relay then authenticates with a blank password and reports the failure
     * @return the materialized, immutable settings (never {@code null}); {@link #isConfigured()} reports usability
     */
    public static SmtpSettings from(SettingsView view, Optional<String> password) {
        Objects.requireNonNull(view, "view");
        String host = text(view, "smtpHost");
        int port = (int) view.integer("smtpPort", 25L, 1, 65535);
        boolean auth = view.bool("smtpAuth", false);
        String username = text(view, "smtpUsername");
        String clear = password == null ? "" : password.orElse("");
        boolean starttls = view.bool("smtpStarttls", false);
        boolean ssl = view.bool("smtpSsl", false);
        String from = text(view, "fromAddress");
        String to = text(view, "toAddress");
        return new SmtpSettings(host, port, auth, username, clear, starttls, ssl, from, to);
    }

    /**
     * Whether enough is set to attempt a send: a non-blank host AND a non-blank recipient. When false the
     * {@link ContactMailer} reports {@code NOT_CONFIGURED} and the submission is stored only (never lost).
     */
    public boolean isConfigured() {
        return host != null && !host.isBlank() && to != null && !to.isBlank();
    }

    /** The effective From address: the configured one, or the recipient when From is blank (a sane default). */
    public String effectiveFrom() {
        return (from == null || from.isBlank()) ? to : from;
    }

    private static String text(SettingsView view, String key) {
        return view.string(key, "").strip();
    }

    /** Never leak the password in logs / diagnostics. */
    @Override
    public String toString() {
        return "SmtpSettings[host=" + host + ", port=" + port + ", auth=" + auth + ", username=" + username
                + ", starttls=" + starttls + ", ssl=" + ssl + ", from=" + from + ", to=" + to
                + ", password=" + (password == null || password.isBlank() ? "<none>" : "<set>") + "]";
    }
}
