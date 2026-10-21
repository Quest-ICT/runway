package mx.quest.runway.page;

/**
 * One entry in the language switcher (a {@code siteLocales} model element): a site locale, its
 * self-language label, the URL that switches to it on the current page (a {@code ?lang=} link that
 * sets the preference cookie), and whether it is the current request's locale.
 *
 * @param code    the language code (e.g. {@code "en"})
 * @param label   the language's display name in its own language (e.g. {@code "English"})
 * @param url     the switch URL on the current page ({@code <currentPath>?lang=<code>})
 * @param current whether this is the locale currently being rendered
 */
public record LocaleOption(String code, String label, String url, boolean current) {
}
