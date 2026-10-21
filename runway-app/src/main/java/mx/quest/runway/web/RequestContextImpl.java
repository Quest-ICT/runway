package mx.quest.runway.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import java.util.Optional;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.site.SiteViews;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.spi.service.RequestContext;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The kernel's {@link RequestContext}: the site {@link SiteResolver} bound to this request, as a frozen view.
 *
 * <p><b>Why the application realm answers empty.</b> The resolver binds a site for every request it can, including
 * {@code /__app/…}: an unmatched host falls back to the platform's catch-all default site. For a public page that is
 * the right answer, but an application-realm screen does not manage "the default site", and a plugin screen that
 * trusted it would act on a tenant nobody chose. So the application realm ({@link AppRealmPaths}, the same predicate
 * its security chain uses) reports no site, as does a thread with no request. Reporting no site is the safe answer,
 * so it is given when either spelling of the path is the application realm ({@link RequestPaths#eitherMatches}):
 * an encoded {@code /%5F%5Fapp/…} reaches the same screen as {@code /__app/…}, and once reported the host's site.
 */
@Component
public class RequestContextImpl implements RequestContext {

    private final SiteResolver resolver;

    public RequestContextImpl(SiteResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    @Override
    public Optional<SiteView> site() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return Optional.empty();
        }
        HttpServletRequest request = attributes.getRequest();
        if (RequestPaths.eitherMatches(request, AppRealmPaths::isAppRealm)) {
            return Optional.empty();
        }
        return resolver.current().map(SiteViews::toView);
    }
}
