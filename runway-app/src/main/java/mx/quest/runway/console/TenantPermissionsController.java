package mx.quest.runway.console;

import mx.quest.runway.authz.AuthorizationAdminService;
import mx.quest.runway.authz.PermissionCatalog;
import mx.quest.runway.log.SiteLogService;
import mx.quest.runway.site.SiteResolver;
import mx.quest.runway.spi.model.Realm;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.server.ResponseStatusException;

/**
 * The <b>tenant-realm</b> permissions matrix ({@code /__admin/permissions}): a TENANT principal (a tenant
 * superadmin/admin) edits its <b>bound tenancy's</b> users and roles. The tenancy is the request's resolved
 * site ({@link SiteResolver#current()}), never a path argument — so a tenant manager can only ever shape the
 * tenancy they are signed into, and the application realm is unreachable from here (this route is on the tenant
 * security chain only). Every successful change is recorded to the bound site's audit log.
 */
@Controller
@RequestMapping("/__admin/permissions")
public class TenantPermissionsController extends AbstractPermissionsController {

    private final SiteResolver siteResolver;
    private final TenantAuthority tenantAuthority;
    private final SiteLogService siteLog;

    public TenantPermissionsController(PermissionsMatrixService matrix, AuthorizationAdminService admin,
                                       PermissionCatalog catalog, SiteResolver siteResolver,
                                       TenantAuthority tenantAuthority, SiteLogService siteLog) {
        super(matrix, admin, catalog);
        this.siteResolver = siteResolver;
        this.tenantAuthority = tenantAuthority;
        this.siteLog = siteLog;
    }

    @Override
    protected void audit(String actor, String action, String detail) {
        siteLog.audit(tenancyId(), actor, action, detail);
    }

    @Override
    protected void requireAdmin(org.springframework.security.core.Authentication authentication) {
        tenantAuthority.require(authentication, tenancyId()); // master / wildcard of the bound tenancy
    }

    @Override
    protected Realm realm() {
        return Realm.TENANT;
    }

    @Override
    protected String tenancyId() {
        return siteResolver.current()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "no bound tenancy"))
                .id();
    }

    @Override
    protected String basePath() {
        return "/__admin/permissions";
    }

    @Override
    protected String contentTemplate() {
        return "operator/permissions";
    }

    @Override
    protected String titleKey() {
        return "op.permissions.title";
    }
}
