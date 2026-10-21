package mx.quest.runway.web;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The in-kernel Content-Security-Policy violation collector — the endpoint {@link RunwayCspProperties#getReportUri()}
 * ({@code /__csp-report} by default) points browsers at. It logs each violation at {@code WARN} so an operator can
 * see, from the server log, what a would-be-enforced policy is flagging — then widen
 * {@link RunwayCspProperties#getExtraHosts() runway.csp.extra-hosts} for a legitimate resource before switching
 * {@link RunwayCspProperties#getMode() runway.csp.mode} from {@code report} to {@code enforce}.
 *
 * <h2>Unauthenticated + CSRF-exempt — necessarily</h2>
 * Browsers POST these reports automatically, cross-cutting, with <b>no session and no CSRF token</b>. So the path
 * is {@code permitAll} (it falls into the tenant realm chain's {@code anyRequest().permitAll()}) and explicitly
 * CSRF-exempt (see {@code TenantSecurityConfig} — {@code ignoringRequestMatchers("/__csp-report")}). It is also a
 * reserved prefix in {@code EndpointContributorRegistry}, so a plugin can never shadow it.
 *
 * <h2>Robust by contract — always 204, never throws</h2>
 * It accepts <b>any</b> content type ({@code consumes = ALL} — browsers send {@code application/csp-report} or
 * {@code application/reports+json}, not {@code application/json}) and always returns {@code 204 No Content}. It
 * parses <b>both</b> report shapes:
 * <ul>
 *   <li>the classic single-report object {@code {"csp-report": { … }}} (the {@code report-uri} directive), and</li>
 *   <li>the Reporting-API array {@code [ { "type": "csp-violation", "body": { … } }, … ]} (the {@code report-to}
 *       directive) — each element's {@code body} carries the violation fields.</li>
 * </ul>
 * A body that fails to parse is logged <b>truncated</b> to {@link #RAW_BODY_CAP} characters, never echoed whole
 * (an attacker could otherwise flood the log with a huge or hostile body). An empty body is ignored.
 */
@RestController
public class CspReportController {

    private static final Logger log = LoggerFactory.getLogger(CspReportController.class);

    /** A shared, thread-safe reader — Jackson 3 {@code ObjectMapper} is immutable and safe to reuse. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The cap (characters) on an unparseable body echoed to the log, so a hostile body cannot flood it. */
    static final int RAW_BODY_CAP = 500;

    @PostMapping(value = "/__csp-report", consumes = MediaType.ALL_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void report(@RequestBody(required = false) byte[] body) {
        if (body == null || body.length == 0) {
            return; // an empty ping — nothing to record
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            if (root != null && root.isArray()) {
                // Reporting-API shape: a list of reports, each with the violation under "body".
                for (JsonNode entry : root) {
                    JsonNode violation = entry.has("body") ? entry.get("body") : entry;
                    logViolation(violation);
                }
            } else {
                // Classic shape: {"csp-report": { … }}, or a bare report object.
                JsonNode violation = (root != null && root.has("csp-report")) ? root.get("csp-report") : root;
                logViolation(violation);
            }
        } catch (RuntimeException unparseable) {
            String raw = new String(body, StandardCharsets.UTF_8);
            if (raw.length() > RAW_BODY_CAP) {
                raw = raw.substring(0, RAW_BODY_CAP) + "…";
            }
            log.warn("CSP violation (unparsed report): {}", raw);
        }
    }

    /** Logs the salient fields of one violation object at WARN. Tolerates either the classic or reports-API keys. */
    private static void logViolation(JsonNode v) {
        String directive = text(v, "effective-directive", "violated-directive", "effectiveDirective");
        String blocked = text(v, "blocked-uri", "blockedURL");
        String document = text(v, "document-uri", "documentURL");
        log.warn("CSP violation directive='{}' blocked='{}' doc='{}'", directive, blocked, document);
    }

    /** The first non-null text value among {@code keys} on {@code node}, or {@code ""} — shape-tolerant. */
    private static String text(JsonNode node, String... keys) {
        if (node != null) {
            for (String key : keys) {
                JsonNode value = node.get(key);
                if (value != null && !value.isNull()) {
                    return value.asString();
                }
            }
        }
        return "";
    }
}
