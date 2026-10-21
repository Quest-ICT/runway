package mx.quest.runway.spi.service;

import java.util.Locale;

/**
 * Looks up a localized message for a plugin's output. A kernel singleton, injected by type.
 *
 * <p>Two namespaces that never mix: <b>public</b> output (what a site's visitors read) resolves against the site's
 * own bundle first and then every plugin's {@code i18n/messages*.properties} (add-only: a plugin can never override
 * a site's key); <b>console</b> output resolves against the operator console's bundles, including every plugin's
 * operator bundle, in the operator's chosen UI language.
 *
 * <p>Arguments are applied with {@link java.text.MessageFormat}, to the found message or to the fallback. Thread-safe;
 * never returns {@code null}; never throws for a missing key.
 *
 * @since SPI 0.5.0
 */
public interface Messages {

    /**
     * A message for public output on site {@code siteId}, in {@code locale}. Works off-request (the site is named,
     * not taken from the thread).
     *
     * @param fallback the text when no bundle has {@code code} (nullable: {@code null} yields the code itself)
     * @throws IllegalArgumentException if {@code siteId} is not a safe identifier
     */
    String site(String siteId, Locale locale, String code, String fallback, Object... args);

    /**
     * A message for console output, in the UI language of the operator making the current request.
     *
     * @param fallback the text when no bundle has {@code code} (nullable: {@code null} yields the code itself)
     * @return the message; off a request thread, the fallback (or the code)
     */
    String console(String code, String fallback, Object... args);
}
