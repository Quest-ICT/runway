package mx.quest.runway.console;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import mx.quest.runway.i18n.SiteAwareMessageSource;
import mx.quest.runway.web.RequestPaths;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.NoSuchMessageException;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The single {@link MessageSource} installed on the Thymeleaf engine: it routes {@code #{}} resolution by the
 * current request. An <b>operator</b> request ({@link OperatorPaths#isOperatorRequest}) resolves against the
 * {@link OperatorMessageSource}; everything else (tenant pages) keeps resolving against the per-site
 * {@link SiteAwareMessageSource}. One engine, one message source, but the two realms' string namespaces never
 * cross — the i18n half of the realm-isolation law.
 *
 * <p>The discriminator is the request URI (via {@link RequestContextHolder}), not whether a site is bound:
 * {@code /__admin} chrome must use operator strings even though a tenant is the subject. With no request bound
 * (e.g. a background render) it falls through to the site source, which itself no-ops without a bound site.
 */
@Component
public class OperatorAwareMessageSource implements MessageSource {

    /**
     * Request attribute a <b>tenant-page render</b> sets to force resolution against the SITE strings even when
     * it is served under a console path. The page editor's live preview / quality run
     * ({@code DraftRenderer}) render a tenant's own page — full of the site's {@code #{}} keys — from a
     * {@code /__admin/**} URL; without this the URI-based routing below would hand them the console's operator
     * strings and every site key would echo as its bare code. Set by the render choke point, honoured here.
     */
    public static final String SITE_MESSAGES_ATTRIBUTE = "mx.quest.runway.render.siteMessages";

    private final OperatorMessageSource operatorMessages;
    private final SiteAwareMessageSource siteMessages;

    public OperatorAwareMessageSource(OperatorMessageSource operatorMessages, SiteAwareMessageSource siteMessages) {
        this.operatorMessages = operatorMessages;
        this.siteMessages = siteMessages;
    }

    private MessageSource current() {
        return isOperatorRequest() ? operatorMessages : siteMessages;
    }

    private static boolean isOperatorRequest() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return false;
        }
        HttpServletRequest request = attributes.getRequest();
        // A tenant-page render served under a console path (editor preview / quality) opts into SITE strings.
        if (Boolean.TRUE.equals(request.getAttribute(SITE_MESSAGES_ATTRIBUTE))) {
            return false;
        }
        return OperatorPaths.isOperatorRequest(RequestPaths.normalized(request));
    }

    @Override
    public String getMessage(String code, Object[] args, String defaultMessage, Locale locale) {
        return current().getMessage(code, args, defaultMessage, locale);
    }

    @Override
    public String getMessage(String code, Object[] args, Locale locale) throws NoSuchMessageException {
        return current().getMessage(code, args, locale);
    }

    @Override
    public String getMessage(MessageSourceResolvable resolvable, Locale locale) throws NoSuchMessageException {
        return current().getMessage(resolvable, locale);
    }
}
