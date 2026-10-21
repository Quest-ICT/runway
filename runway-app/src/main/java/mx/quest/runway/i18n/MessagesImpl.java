package mx.quest.runway.i18n;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import java.util.Objects;
import mx.quest.runway.console.OperatorMessageSource;
import mx.quest.runway.spi.service.Messages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.LocaleResolver;

/**
 * The kernel's {@link Messages}: public text from {@link SiteAwareMessageSource#forSite} (the named site's bundle,
 * then the plugin bundles, add-only), console text from {@link OperatorMessageSource} (core and plugin operator
 * bundles) in the locale the console's {@link LocaleResolver} gives the current request — the same resolver that
 * picks the operator's UI language for every console page, so a plugin's console strings always match the shell
 * around them.
 *
 * <p>Never throws for a missing key or a broken bundle: a lookup failure is logged at debug and answers the
 * fallback, because a label that falls back is better than a console screen or public page that fails.
 */
@Component
public class MessagesImpl implements Messages {

    private static final Logger log = LoggerFactory.getLogger(MessagesImpl.class);

    private final SiteAwareMessageSource siteMessages;
    private final OperatorMessageSource operatorMessages;
    private final ObjectProvider<LocaleResolver> localeResolver;

    public MessagesImpl(SiteAwareMessageSource siteMessages, OperatorMessageSource operatorMessages,
                        ObjectProvider<LocaleResolver> localeResolver) {
        this.siteMessages = Objects.requireNonNull(siteMessages, "siteMessages");
        this.operatorMessages = Objects.requireNonNull(operatorMessages, "operatorMessages");
        this.localeResolver = localeResolver;
    }

    @Override
    public String site(String siteId, Locale locale, String code, String fallback, Object... args) {
        MessageSource source = siteMessages.forSite(siteId); // throws IllegalArgumentException for an unsafe id
        Objects.requireNonNull(locale, "locale");
        Objects.requireNonNull(code, "code");
        return lookup(source, code, fallback, args, locale);
    }

    @Override
    public String console(String code, String fallback, Object... args) {
        Objects.requireNonNull(code, "code");
        Locale locale = operatorLocale();
        if (locale == null) {
            return fallback == null ? code : fallback; // off a request: no operator, so no UI language to choose
        }
        return lookup(operatorMessages, code, fallback, args, locale);
    }

    private static String lookup(MessageSource source, String code, String fallback, Object[] args, Locale locale) {
        String defaultText = fallback == null ? code : fallback;
        try {
            String text = source.getMessage(code, args, defaultText, locale);
            return text == null ? defaultText : text;
        } catch (RuntimeException failure) {
            log.debug("message '{}' could not be resolved ({}); using the fallback", code, failure.toString());
            return defaultText;
        }
    }

    /** The current request's console UI locale, or {@code null} off a request thread. */
    private Locale operatorLocale() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return null;
        }
        HttpServletRequest request = attributes.getRequest();
        LocaleResolver resolver = localeResolver == null ? null : localeResolver.getIfAvailable();
        Locale locale = resolver == null ? null : resolver.resolveLocale(request);
        return locale != null ? locale : request.getLocale();
    }
}
