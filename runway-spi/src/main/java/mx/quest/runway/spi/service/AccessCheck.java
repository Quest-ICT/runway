package mx.quest.runway.spi.service;

/**
 * Checks a permission for the principal of the request on the calling thread. The kernel's implementation is the
 * bean named {@value #BEAN_NAME}, so the same check is available in Spring Security expressions
 * ({@code @PreAuthorize("@authz.may('blog|manage')")}) and in Thymeleaf templates
 * ({@code ${@authz.may('blog|manage')}}), and by injection of this type in Java.
 *
 * <p>Thread-safe; never throws for a missing request or principal.
 *
 * @since SPI 0.5.0
 */
public interface AccessCheck {

    /** The bean name the kernel registers its implementation under. */
    String BEAN_NAME = "authz";

    /**
     * Whether the current principal holds {@code permissionKey} in the current realm and tenancy. A plugin's own
     * permission is named {@code <pluginId>|<permissionId>}; a kernel permission by its name
     * ({@code EDIT_PAGES}). <b>Fail-closed</b>: {@code false} with no request, no signed-in principal, an
     * undeclared key, or on an endpoint route dispatched anonymously
     * ({@link mx.quest.runway.spi.model.Route#anonymous()}).
     */
    boolean may(String permissionKey);
}
