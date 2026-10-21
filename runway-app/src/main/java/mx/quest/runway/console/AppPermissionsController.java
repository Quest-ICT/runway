package mx.quest.runway.console;

import mx.quest.runway.authz.AuthorizationAdminService;
import mx.quest.runway.authz.PermissionCatalog;
import mx.quest.runway.log.AppActivityLog;
import mx.quest.runway.spi.model.Realm;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * The <b>application-realm</b> permissions matrix ({@code /__app/permissions}): an APPLICATION principal edits
 * application users' roles/grants and the application roles' deltas, with no tenancy. The realm is APPLICATION
 * by construction (this route only sits on the application security chain), so the scope is the app stores.
 * Every successful change is recorded to the application activity log ({@link AppActivityLog}).
 */
@Controller
@RequestMapping("/__app/permissions")
public class AppPermissionsController extends AbstractPermissionsController {

    private final PlatformAuthority platformAuthority;
    private final AppActivityLog activity;

    public AppPermissionsController(PermissionsMatrixService matrix, AuthorizationAdminService admin,
                                    PermissionCatalog catalog, PlatformAuthority platformAuthority,
                                    AppActivityLog activity) {
        super(matrix, admin, catalog);
        this.platformAuthority = platformAuthority;
        this.activity = activity;
    }

    @Override
    protected void audit(String actor, String action, String detail) {
        activity.record(actor, action, detail);
    }

    @Override
    protected void requireAdmin(Authentication authentication) {
        platformAuthority.require(authentication); // master / wildcard of the application realm
    }

    @Override
    protected Realm realm() {
        return Realm.APPLICATION;
    }

    @Override
    protected String tenancyId() {
        return null; // the application realm has no tenancy
    }

    @Override
    protected String basePath() {
        return "/__app/permissions";
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
