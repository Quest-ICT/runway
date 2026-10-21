/**
 * Per-tenant internationalization. Each site carries its own message bundle under
 * {@code sites/<id>/i18n/messages[_<locale>].properties}; {@link mx.quest.runway.i18n.SiteAwareMessageSource}
 * resolves a {@code #{}} key against the current request's site bundle for the request locale.
 *
 * <p>Self-containment rule: a site's bundle is complete on its own — there is <b>no</b> fallback to
 * an app-level catalog, so a missing key renders as {@code ??key??} (a signal the bundle is
 * incomplete, not a silent fall-through). Bundles are reloadable at runtime and travel in the site's
 * backup.
 */
package mx.quest.runway.i18n;
