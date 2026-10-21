package mx.quest.runway.wizard;

import java.nio.charset.StandardCharsets;
import mx.quest.runway.console.AssetVersion;
import mx.quest.runway.web.CspFilter;
import org.springframework.context.annotation.Conditional;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The first-run setup surface, present only in WIZARD mode. {@code GET /setup} serves the form to create
 * the application superadmin; {@code POST /setup} provisions it via {@link WizardService} and the process
 * relaunches into ACTIVE mode, after which this controller (and the whole wizard layer) is gone.
 *
 * <p>Rendered as self-contained HTML styled with the same self-hosted chrome as the consoles (Tabler +
 * {@code runway-chrome.js}, served from {@code /__assets} — no CDN; the WIZARD security chain permits that
 * prefix so the page can style itself). The setup surface is open because there are no accounts yet to
 * authenticate against — it must be reached only over localhost / behind the proxy until setup completes
 * (a deployment/launcher responsibility).
 */
@Controller
@Conditional(WizardModeCondition.class)
public class WizardController {

    private final WizardService wizard;
    private final AssetVersion assetVersion;

    public WizardController(WizardService wizard, AssetVersion assetVersion) {
        this.wizard = wizard;
        this.assetVersion = assetVersion;
    }

    @GetMapping("/setup")
    @ResponseBody
    public ResponseEntity<String> form() {
        return html(document("Runway — first-run setup",
                "<h2 class=\"h2 text-center mb-1\">Welcome to Runway</h2>"
                        + "<p class=\"text-secondary text-center mb-4\">Create the two administrators to finish"
                        + " setup. The server will then restart into normal mode.</p>"
                        + "<form method=\"post\" action=\"/setup\" autocomplete=\"off\">"
                        + "<h3 class=\"h4 mb-1\">Application superadmin</h3>"
                        + "<p class=\"text-secondary small mb-3\">Runs the platform: sites, plugins, application users.</p>"
                        + "<div class=\"mb-3\"><label class=\"form-label\" for=\"w-user\">Username</label>"
                        + "<input id=\"w-user\" name=\"username\" class=\"form-control\" autocomplete=\"username\" autofocus required></div>"
                        + "<div class=\"mb-3\"><label class=\"form-label\" for=\"w-name\">Display name</label>"
                        + "<input id=\"w-name\" name=\"displayName\" class=\"form-control\"></div>"
                        + "<div class=\"mb-3\"><label class=\"form-label\" for=\"w-email\">Recovery email</label>"
                        + "<input id=\"w-email\" name=\"recoveryEmail\" type=\"email\" class=\"form-control\"></div>"
                        + "<div class=\"mb-3\"><label class=\"form-label\" for=\"w-pw\">Password</label>"
                        + "<input id=\"w-pw\" name=\"password\" type=\"password\" class=\"form-control\" autocomplete=\"new-password\" required minlength=\"8\"></div>"
                        + "<hr class=\"my-4\">"
                        + "<h3 class=\"h4 mb-1\">Default site administrator</h3>"
                        + "<p class=\"text-secondary small mb-3\">Manages the default site. A separate account in the"
                        + " site realm — the two realms never cross.</p>"
                        + "<div class=\"mb-3\"><label class=\"form-label\" for=\"w-suser\">Username</label>"
                        + "<input id=\"w-suser\" name=\"siteAdminUsername\" class=\"form-control\" autocomplete=\"off\" required></div>"
                        + "<div class=\"mb-3\"><label class=\"form-label\" for=\"w-semail\">Recovery email</label>"
                        + "<input id=\"w-semail\" name=\"siteAdminEmail\" type=\"email\" class=\"form-control\"></div>"
                        + "<div class=\"mb-3\"><label class=\"form-label\" for=\"w-spw\">Password</label>"
                        + "<input id=\"w-spw\" name=\"siteAdminPassword\" type=\"password\" class=\"form-control\" autocomplete=\"new-password\" required minlength=\"8\"></div>"
                        + "<div class=\"form-footer\"><button type=\"submit\" class=\"btn btn-primary w-100\">"
                        + "Create administrators &amp; restart</button></div></form>"));
    }

    @PostMapping("/setup")
    @ResponseBody
    public ResponseEntity<String> complete(@RequestParam String username, @RequestParam String password,
                                           @RequestParam(required = false) String displayName,
                                           @RequestParam(required = false) String recoveryEmail,
                                           @RequestParam String siteAdminUsername,
                                           @RequestParam String siteAdminPassword,
                                           @RequestParam(required = false) String siteAdminEmail) {
        wizard.completeSetup(username, password,
                displayName == null ? "" : displayName, recoveryEmail == null ? "" : recoveryEmail,
                siteAdminUsername, siteAdminPassword, siteAdminEmail);
        return html(document("Runway — restarting",
                "<div class=\"text-center\">"
                        + "<div class=\"mb-3\"><i class=\"fa-solid fa-circle-check text-green\" style=\"font-size:3rem\"></i></div>"
                        + "<h2 class=\"h2 mb-2\">Setup complete</h2>"
                        + "<div class=\"mb-3\"><div class=\"spinner-border text-primary\" role=\"status\"></div></div>"
                        + "<p class=\"text-secondary\" id=\"rs-note\">The administrators were created and Runway is restarting"
                        + " into normal mode — this page continues to <a href=\"/app/login\">/app/login</a> automatically once"
                        + " it is back.</p></div>" + restartScript("/app/login")));
    }

    /**
     * A tiny inline poller that redirects to {@code target} the moment {@code /actuator/health} is back — only
     * after it has first gone down, so it never bounces off the still-alive instance about to shut down. A hard
     * timeout drops to a manual link. Same behavior as {@code restarting.html} (the plugin-restart interstitial).
     */
    private static String restartScript(String target) {
        return "<script" + cspNonceAttr() + ">(function(){var t='" + target + "',s=Date.now(),H=120000,down=false;"
                + "function m(){var n=document.getElementById('rs-note');"
                + "if(n){n.innerHTML='Runway is starting \\u2014 <a href=\"'+t+'\">sign in</a>.';}}"
                + "function k(){if(Date.now()-s>H){m();return;}"
                + "fetch('/actuator/health',{cache:'no-store'}).then(function(r){"
                + "if(r&&r.ok){if(down){location.replace(t);return;}setTimeout(k,1500);}"
                + "else{down=true;setTimeout(k,1500);}}).catch(function(){down=true;setTimeout(k,1500);});}"
                + "setTimeout(k,1500);})();</script>";
    }

    /**
     * Wraps {@code cardBody} in a styled, self-hosted-chrome page (Tabler + runway-chrome, no CDN). Every
     * {@code /__assets} reference carries the {@link AssetVersion} token, so an upgraded build's chrome is never
     * shadowed by the previous one's cached copy.
     */
    private String document(String title, String cardBody) {
        return "<!DOCTYPE html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<meta name=\"robots\" content=\"noindex\"><title>" + title + "</title>"
                + "<link rel=\"icon\" type=\"image/svg+xml\" href=\"" + assetVersion.url("/__assets/runway.svg") + "\">"
                + "<link rel=\"stylesheet\" href=\"" + assetVersion.url("/__assets/vendor/tabler.min.css") + "\">"
                + "<link rel=\"stylesheet\" href=\"" + assetVersion.url("/__assets/vendor/fontawesome/css/all.min.css") + "\">"
                + "<link rel=\"stylesheet\" href=\"" + assetVersion.url("/__assets/runway-chrome.css") + "\"></head>"
                + "<body class=\"d-flex flex-column bg-body-tertiary\" data-i18n-pw-toggle=\"Show / hide password\">"
                + "<div class=\"page page-center\"><div class=\"container container-tight py-4\">"
                + "<div class=\"text-center mb-4\"><span class=\"navbar-brand navbar-brand-autodark fs-2\">"
                + "<img src=\"" + assetVersion.url("/__assets/runway.svg") + "\" alt=\"Runway\" style=\"height:2rem;width:auto\" class=\"me-2\">Runway</span></div>"
                + "<div class=\"card card-md\"><div class=\"card-body\">" + cardBody + "</div></div>"
                + "</div></div><script defer" + cspNonceAttr() + " src=\"" + assetVersion.url("/__assets/runway-chrome.js") + "\"></script></body></html>";
    }

    /**
     * The {@code nonce="<x>"} attribute for a {@code <script>} the wizard hand-builds, read from the per-request
     * value {@link CspFilter} published (the wizard renders in WIZARD mode where the CSP filter still applies).
     * Empty when there is no nonce (CSP OFF, or outside a request), so nothing is stamped. Both the inline restart
     * poller and the {@code runway-chrome.js} tag carry it, so they authorize under {@code script-src 'nonce-<x>'}
     * (and, once strict-dynamic lands, the nonced inline poller is trusted directly).
     */
    private static String cspNonceAttr() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs
                && attrs.getRequest().getAttribute(CspFilter.NONCE_REQUEST_ATTRIBUTE) instanceof String nonce
                && !nonce.isEmpty()) {
            return " nonce=\"" + nonce + "\"";
        }
        return "";
    }

    private static ResponseEntity<String> html(String body) {
        return ResponseEntity.ok().contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8)).body(body);
    }

}
