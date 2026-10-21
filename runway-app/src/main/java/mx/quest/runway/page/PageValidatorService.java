package mx.quest.runway.page;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import mx.quest.runway.content.RoutableContent;
import mx.quest.runway.head.HeadContributorRegistry;
import mx.quest.runway.plugin.LoadedPlugin;
import mx.quest.runway.plugin.RunningPlugins;
import mx.quest.runway.site.Site;
import mx.quest.runway.spi.RunwaySpi;
import mx.quest.runway.storage.PathSafety;
import mx.quest.runway.storage.StorageLayout;
import mx.quest.runway.web.PublicPageNonce;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;

/**
 * Computes a page render's {@link PageValidator} by folding <b>every input that shapes the output</b>
 * into a strong ETag. Folding the inputs (not the rendered bytes) lets the validator be computed <em>before</em>
 * rendering, so an unchanged page returns {@code 304} without rendering at all.
 *
 * <p>The folded inputs, each a <b>labelled, length-prefixed field</b> (see {@link #field}) so two files can't
 * alias and no field can shift bytes into the next:
 * <ul>
 *   <li>the page's <b>identity</b> — its slug and its resolved public path in this locale (two pages with
 *       byte-identical files must not share an ETag);</li>
 *   <li>the page's {@code <slug>.html} / {@code .th.xml} / {@code .meta.yml};</li>
 *   <li>the master's {@code <master>.html} / {@code .th.xml} (its chrome shapes every page);</li>
 *   <li>the site's <b>chrome templates</b> — every template in {@code templates/} that is not a page (the masters,
 *       the {@code _nav}/{@code _head}/… fragments, any other partial, the error pages), by content
 *       ({@link ChromeTemplateFold}): a master or page pulls them by a name computed at render time, so the closed
 *       set is folded rather than a guess at what this render includes;</li>
 *   <li>the site's message bundles <b>as the message source has them</b> — re-checked against the disk on every
 *       request first, so a bundle edit is in the ETag and in the render together ({@link LoadedSiteInputs#bundles});
 *       without a wired message source (bare tests) the {@code messages.properties} and
 *       {@code messages_<lang>.properties} files are folded directly;</li>
 *   <li>the <b>site record</b> the request resolved — what {@code site.yml} says once loaded (base URL / locales
 *       drive canonical, alternates, the switcher). Not the file: a hand edit applies at a site reload, and the
 *       ETag moves then, with the body ({@link LoadedSiteInputs});</li>
 *   <li>the resolved locale and the <b>kernel build fingerprint</b> — the SPI contract version plus the
 *       running build's {@code BuildProperties} version and build time (so a new kernel build busts every
 *       cached page, not only an SPI bump). Where no {@code build-info.properties} is on the classpath (unit
 *       tests, an IDE run) only the SPI constant is folded — a documented fallback, never a failure;</li>
 *   <li>the public-page CSP nonce state ({@link PublicPageNonce#etagFold()}: {@code off}, or {@code on:} + a
 *       fingerprint of the secret). The edge stamps a nonce derived from this very ETag into the body, so turning
 *       the feature on/off or rotating the secret changes the bytes — and must therefore move the ETag, or a cache
 *       could revalidate an old body (old nonce) against a new header.</li>
 * </ul>
 *
 * <h2>Field encoding (why length prefixes)</h2>
 * Each field is folded as {@code <len>:<label>} + a one-byte kind ({@code f} file bytes, {@code a} absent,
 * {@code L} large-file fingerprint, {@code t} token, {@code n} null token) + {@code <len>:<value>}. The decimal
 * lengths tell the digest exactly where a value ends, so the old {@code |label=}+raw-bytes shape — where a file
 * ending in {@code "|i18n.default="} could absorb the next field's label, letting two different input sets hash
 * identically — is impossible by construction.
 *
 * <p>File <em>content</em> is folded (robust to coarse mtime granularity). A missing file folds a stable
 * {@code absent} marker so adding it later changes the ETag. A virtual render additionally folds its serving plugin's
 * id+version and i18n bundles (see {@link #computeVirtual}).
 *
 * <p><b>Loaded configuration is folded as loaded</b> — the site record, the SEO/icon/manifest head state, the
 * per-site settings for this site of every running plugin that declares {@code siteSettings:}, every running
 * plugin's app-level settings, the site's template parameters ({@code params.yml}), its widget-instance config
 * ({@code widget-instances.yml}) and the inputs of the cross-page URL map (every page's slug, paths and locales; every
 * content source's enumeration) ({@link LoadedSiteInputs}). They shape what the templates, widgets and head
 * contributors emit, so they must move the ETag; and they must move it when the change <em>applies</em> (a save, a
 * reload, a restart), never when a file is merely edited, or the old body would go out under the new ETag.
 *
 * <p><b>No modification time.</b> The validator carries no {@code Last-Modified} ({@link PageValidator#lastModified()}
 * is always {@code 0}, so an {@code If-Modified-Since} is ignored): most of the inputs above have no modification time
 * at all (a console save, a reload, a restart, a content source), and file times are not monotonic either (a restore
 * or {@code cp -p} brings an older mtime back). A date that cannot move with the ETag would answer {@code 304} to an
 * {@code If-Modified-Since}-only client across a change; the strong ETag alone decides.
 *
 * <p><b>Not describable here</b>: a widget's output for a request query reads data the kernel cannot see (a search
 * index) — {@code PageController} serves such a render without validators instead.
 *
 * <p><b>Head-injection inputs ARE folded</b> — see {@link #foldHeadInputs}. The {@code <head>} is spliced in
 * at the response edge, after this validator has been computed and may already have answered {@code 304}, so
 * anything the head seam reads is invisible here unless a contributor declares it
 * ({@code HeadContributor#headInputs}) and it is folded. Left out, a strong ETag would promise byte identity
 * it could not keep.
 */
@Component
public class PageValidatorService {

    private static final String TEMPLATES = "templates";
    private static final String I18N = "i18n";
    /** Above this, a validator input is fingerprinted by size+mtime, not read whole into the heap per render. */
    private static final long MAX_FOLD_BYTES = 10L * 1024 * 1024;

    /** Field kinds — part of the encoding, so e.g. a file whose bytes spell a large-file fingerprint can't alias one. */
    private static final byte KIND_FILE = 'f';
    private static final byte KIND_ABSENT = 'a';
    private static final byte KIND_LARGE = 'L';
    private static final byte KIND_TOKEN = 't';
    private static final byte KIND_NULL = 'n';

    private final StorageLayout layout;
    /** The head seam's declared per-site inputs; {@code null} in the bare test form (no head injection). */
    private final HeadContributorRegistry headContributors;
    /** id/version of every running plugin, computed once — plugins cannot change without a restart. */
    private volatile String pluginSetToken;
    /** For resolving a virtual render's {@code __plugin__} body-template files; {@code null} in bare unit tests. */
    private final RunningPlugins runningPlugins;
    /** The kernel build fingerprint folded into every ETag (see {@link #buildFingerprint(BuildProperties)}). */
    private final String buildFingerprint;
    /** The public-page CSP nonce source; {@code null} in tests that don't wire it (folds as {@code off}). */
    private final PublicPageNonce publicNonce;
    /** Loaded configuration folded as loaded; {@code null} in bare tests (bundle files folded directly then). */
    private final LoadedSiteInputs loaded;
    /** The per-site chrome-template token, memoized behind file stamps. */
    private final ChromeTemplateFold chrome = new ChromeTemplateFold();

    /** Bare form for unit tests (no plugin system) — a {@code __plugin__} body template folds as name-only. */
    public PageValidatorService(StorageLayout layout) {
        this(layout, (RunningPlugins) null, (HeadContributorRegistry) null, (BuildProperties) null);
    }

    /** Direct form for unit tests that stub the plugin system (no Spring {@code ObjectProvider} needed). */
    public PageValidatorService(StorageLayout layout, RunningPlugins runningPlugins) {
        this(layout, runningPlugins, null);
    }

    /** Full form (tests): the head seam's declared inputs participate exactly as they do in production. */
    public PageValidatorService(StorageLayout layout, RunningPlugins runningPlugins,
                                HeadContributorRegistry headContributors) {
        this(layout, runningPlugins, headContributors, (BuildProperties) null);
    }

    /**
     * Full form with the kernel build identity (tests that pin a build). {@code buildProperties} may be
     * {@code null}: only the SPI constant is then folded as the build fingerprint.
     */
    public PageValidatorService(StorageLayout layout, RunningPlugins runningPlugins,
                                HeadContributorRegistry headContributors, BuildProperties buildProperties) {
        this(layout, runningPlugins, headContributors, buildProperties, null);
    }

    /**
     * As the canonical form with no {@link LoadedSiteInputs}. {@code publicNonce} may be {@code null} (no public-page
     * CSP nonce — folds {@code off}).
     */
    public PageValidatorService(StorageLayout layout, RunningPlugins runningPlugins,
                                HeadContributorRegistry headContributors, BuildProperties buildProperties,
                                PublicPageNonce publicNonce) {
        this(layout, runningPlugins, headContributors, buildProperties, publicNonce, null);
    }

    /**
     * Canonical form. {@code publicNonce} and {@code loaded} may be {@code null}: no public-page CSP nonce (folds
     * {@code off}); no loaded-configuration folds (the bundle files are then folded directly — the bare test form).
     */
    public PageValidatorService(StorageLayout layout, RunningPlugins runningPlugins,
                                HeadContributorRegistry headContributors, BuildProperties buildProperties,
                                PublicPageNonce publicNonce, LoadedSiteInputs loaded) {
        this.layout = layout;
        this.runningPlugins = runningPlugins;
        this.headContributors = headContributors;
        this.buildFingerprint = buildFingerprint(buildProperties);
        this.publicNonce = publicNonce;
        this.loaded = loaded;
    }

    @Autowired
    public PageValidatorService(StorageLayout layout, ObjectProvider<RunningPlugins> runningPlugins,
                                ObjectProvider<HeadContributorRegistry> headContributors,
                                ObjectProvider<BuildProperties> buildProperties,
                                ObjectProvider<PublicPageNonce> publicNonce,
                                ObjectProvider<LoadedSiteInputs> loaded) {
        this(layout, runningPlugins.getIfAvailable(), headContributors.getIfAvailable(),
                buildProperties.getIfAvailable(), publicNonce.getIfAvailable(), loaded.getIfAvailable());
    }

    /**
     * The kernel build fingerprint: {@code runway/<spi-version>}, plus {@code /<version>@<build-time-millis>}
     * when the running jar carries {@code META-INF/build-info.properties}. Folding the build — not only the SPI
     * contract version, which stays put across most releases — is what makes a kernel upgrade that changes
     * rendering <em>code</em> move every ETag.
     *
     * <p>Fallback (deliberate): with no build-info (unit tests, an IDE run) only the SPI constant is folded.
     * Caveat: builds are reproducible, so the build time is the pinned {@code project.build.outputTimestamp};
     * two SNAPSHOT builds between release cuts share a fingerprint. Every release — what an operator installs —
     * moves it.
     */
    static String buildFingerprint(BuildProperties buildProperties) {
        StringBuilder fp = new StringBuilder("runway/").append(RunwaySpi.VERSION);
        if (buildProperties != null) {
            fp.append('/').append(buildProperties.getVersion() == null ? "" : buildProperties.getVersion())
              .append('@').append(buildProperties.getTime() == null ? "" : buildProperties.getTime().toEpochMilli());
        }
        return fp.toString();
    }

    /** As {@link #compute(Site, Page, Locale, String)} with no query string (the common, cacheable case). */
    public PageValidator compute(Site site, Page page, Locale locale) {
        return compute(site, page, locale, null);
    }

    /**
     * Computes the validator for serving {@code page} of {@code site} in {@code locale}, folding the request's
     * {@code queryString} into the ETag. The query matters because a content widget may render request-dependent
     * output (search results for {@code ?q=}, a {@code ?sent} confirmation), so two different queries must not
     * share one ETag. A {@code null}/blank query folds as the stable no-query ETag — the ordinary cacheable page.
     *
     * @return the strong ETag, with no modification time (never {@code null})
     */
    public PageValidator compute(Site site, Page page, Locale locale, String queryString) {
        MessageDigest digest = sha256();

        Path templates = layout.siteDir(site.id()).resolve(TEMPLATES); // siteDir validates the id
        Path i18n = layout.siteDir(site.id()).resolve(I18N);

        // The page's identity: without it two pages whose files are byte-identical (no `path:` in meta, so the
        // path is slug-derived and appears in no folded file) share one strong ETag.
        token(digest, "page.slug", page.slug());
        token(digest, "page.path", page.pathFor(locale));
        fold(digest, "page.html", templates.resolve(page.slug() + ".html"));
        fold(digest, "page.thxml", templates.resolve(page.slug() + ".th.xml"));
        fold(digest, "page.meta", templates.resolve(page.slug() + ".meta.yml"));
        // The master name is author-supplied (meta.yml); confine it so a "../" template cannot make the
        // validator read a file outside the tenant. An escaping name folds as absent (never read).
        fold(digest, "master.html", confine(templates, page.template() + ".html"));
        fold(digest, "master.thxml", confine(templates, page.template() + ".th.xml"));
        // Whatever the master or the page pulls in by name (a fragment, a partial) — see ChromeTemplateFold.
        token(digest, "chrome", chrome.token(templates));
        foldBundles(digest, site.id(), i18n, locale, null);
        token(digest, "site", siteRecord(site));

        // The <head> is spliced in at the response edge, AFTER this validator has been computed and may
        // already have answered 304 — so nothing the head seam reads is visible here unless it is folded.
        foldHeadInputs(digest, site.id());
        foldSiteData(digest, site);

        token(digest, "locale", locale.toLanguageTag());
        token(digest, "build", buildFingerprint);
        // The public-page CSP nonce is stamped into the body, so its switch and its secret shape the output too.
        token(digest, "csp", publicNonce == null ? "off" : publicNonce.etagFold());
        // Fold the query so a widget's request-dependent output can't be served from a different query's cache.
        // Blank/absent query = the stable no-query ETag (the normal cacheable page — no dependence introduced).
        token(digest, "query", queryString == null ? "" : queryString);

        return new PageValidator('"' + hex(digest.digest(), 16) + '"', 0L);
    }

    /**
     * Computes the validator for serving a <b>virtual</b> render (a {@link mx.quest.runway.spi.ContentSource
     * ContentSource}'s content) — the exact analog of {@link #compute(Site, Page, Locale, String)}, folding
     * every input that shapes the output so a {@code 304} never has to call the source's {@code body()}:
     * <ul>
     *   <li>the <b>content fingerprint</b> — the {@link mx.quest.runway.spi.model.ContentRef}'s full record
     *       state (which includes {@code updatedAt}); the source bumps it on every mutation, so a content edit
     *       changes the ETag with no file access here;</li>
     *   <li>the serving plugin's message bundle — its {@code #{}} labels reach the output: part of the loaded bundle
     *       state (bare form: its {@code messages[_<lang>].properties} files);</li>
     *   <li>the <b>body template's files</b> ({@code .html} + {@code .th.xml}) — a {@code __plugin__/<id>/…}
     *       name resolves through the loaded plugin's install dir (operator-editable, so it must fold), a
     *       {@code __site__/<siteId>/…} name through the tenant's own templates; an unresolvable name folds as
     *       absent;</li>
     *   <li>the same master / chrome templates / message bundles / site record / loaded configuration (params,
     *       widget config and URL-map inputs included) / locale / build / query folds a page render gets.</li>
     * </ul>
     */
    public PageValidator computeVirtual(Site site, RoutableContent content, Locale locale, String queryString) {
        MessageDigest digest = sha256();

        Path templates = layout.siteDir(site.id()).resolve(TEMPLATES); // siteDir validates the id
        Path i18n = layout.siteDir(site.id()).resolve(I18N);

        // The content itself: a CANONICAL field-by-field fingerprint (metadata-only — body() is never read).
        // Deliberately not the record's toString(): Map.of/Set.copyOf iteration order is unstable across JVM
        // runs and cluster nodes, and an order-dependent fold would silently defeat revalidation.
        token(digest, "content", contentFingerprint(content));
        // The resolved public path in this locale (the ref's id/path are already inside the fingerprint).
        token(digest, "content.path", content.pathFor(locale));

        // The body template's files (plugin-owned or tenant-owned) — both operator-editable surfaces.
        Path bodyHtml = bodyTemplateFile(site.id(), templates, content.ref().bodyTemplate(), ".html");
        Path bodyThxml = bodyTemplateFile(site.id(), templates, content.ref().bodyTemplate(), ".th.xml");
        fold(digest, "body.html", bodyHtml);
        fold(digest, "body.thxml", bodyThxml);

        // A __plugin__ body template also folds the SERVING plugin's i18n bundles (its #{} labels reach the
        // rendered output — the decision-5 obligation) and its id+version (a plugin upgrade that changes
        // body-rendering CODE, not templates, must still move the ETag).
        // (With a wired message source the serving plugin's bundle is part of the loaded bundle state folded below.)
        LoadedPlugin servingPlugin = pluginFor(content.ref().bodyTemplate());
        if (servingPlugin != null) {
            token(digest, "plugin", servingPlugin.id() + "/" + servingPlugin.descriptor().version());
        }

        // The master name comes from the ref's params (operator/source-supplied) — confine like a page's.
        fold(digest, "master.html", confine(templates, content.masterTemplate() + ".html"));
        fold(digest, "master.thxml", confine(templates, content.masterTemplate() + ".th.xml"));
        token(digest, "chrome", chrome.token(templates));
        foldBundles(digest, site.id(), i18n, locale, servingPlugin);
        token(digest, "site", siteRecord(site));

        // The <head> is spliced in at the response edge, AFTER this validator has been computed and may
        // already have answered 304 — so nothing the head seam reads is visible here unless it is folded.
        foldHeadInputs(digest, site.id());
        foldSiteData(digest, site);

        token(digest, "locale", locale.toLanguageTag());
        token(digest, "build", buildFingerprint);
        // The public-page CSP nonce is stamped into the body, so its switch and its secret shape the output too.
        token(digest, "csp", publicNonce == null ? "off" : publicNonce.etagFold());
        token(digest, "query", queryString == null ? "" : queryString);

        return new PageValidator('"' + hex(digest.digest(), 16) + '"', 0L);
    }

    /**
     * Folds the per-site data every render model carries, as loaded ({@link LoadedSiteInputs}): the template
     * parameters ({@code ${params}}), the contact information ({@code ${contact}}), the widget-instance config each widget is handed, and the inputs of the
     * cross-page URL map ({@code ${urls}}). Nothing without a wired {@link LoadedSiteInputs} (bare tests).
     */
    private void foldSiteData(MessageDigest digest, Site site) {
        if (loaded != null) {
            token(digest, "site.params", loaded.siteParams(site.id()));
            token(digest, "site.contact", loaded.siteContact(site.id()));
            token(digest, "widgets.config", loaded.widgetInstances(site.id()));
            token(digest, "urls", loaded.urlMapInputs(site));
        }
    }

    /**
     * The canonical, order-stable fingerprint of a virtual render's metadata: every {@code ContentRef} field,
     * with maps folded as <b>sorted</b> key/value pairs — two equal refs built from differently-ordered maps (or on
     * different JVMs/nodes) always fingerprint identically. Every component is length-prefixed ({@link #lp}), so a
     * {@code |} or {@code =} inside a title or a param value cannot fake a component boundary.
     */
    static String contentFingerprint(RoutableContent content) {
        var ref = content.ref();
        StringBuilder fp = new StringBuilder();
        lp(fp, content.sourceKey());
        lp(fp, ref.id());
        lp(fp, ref.path());
        lp(fp, ref.bodyTemplate());
        lp(fp, String.valueOf(ref.published()));
        lp(fp, String.valueOf(ref.noindex()));
        lp(fp, ref.canonical());
        lp(fp, ref.cacheControl());
        lp(fp, ref.activeNav());
        lp(fp, ref.updatedAt() == null ? null : Long.toString(ref.updatedAt().toEpochMilli()));
        fp.append("locales#").append(ref.locales().size()).append(';');
        ref.locales().forEach(l -> lp(fp, l.toLanguageTag()));
        sortedPairs(fp, "lp", ref.localizedPaths());
        sortedPairs(fp, "t", ref.title());
        sortedPairs(fp, "d", ref.description());
        sortedPairs(fp, "p", ref.params());
        return fp.toString();
    }

    /** Appends a map as {@code <label>#<size>;} then its sorted, length-prefixed key/value pairs. */
    private static void sortedPairs(StringBuilder fp, String label, java.util.Map<String, String> map) {
        fp.append(label).append('#').append(map.size()).append(';');
        map.keySet().stream().sorted().forEach(key -> {
            lp(fp, key);
            lp(fp, map.get(key));
        });
    }

    /** Appends {@code <length>:<value>} ({@code -} for {@code null}) — a self-delimiting string component. */
    private static void lp(StringBuilder fp, String value) {
        if (value == null) {
            fp.append('-');
        } else {
            fp.append(value.length()).append(':').append(value);
        }
    }

    /**
     * Folds the message bundles a render reads. With a wired message source this is the source's own
     * {@linkplain LoadedSiteInputs#bundles loaded state} — re-checked against the disk on this very call, so a bundle
     * edited on disk is in the ETag and in the render together, never in only one of them. The bare form folds the
     * site's {@code messages.properties} + {@code messages_<lang>.properties} (and a serving plugin's) bytes.
     */
    private void foldBundles(MessageDigest digest, String siteId, Path i18n, Locale locale,
                             LoadedPlugin servingPlugin) {
        var state = loaded == null ? null : loaded.bundles(siteId);
        if (state != null) {
            token(digest, "i18n", state.fingerprint());
            return;
        }
        fold(digest, "i18n.default", i18n.resolve("messages.properties"));
        fold(digest, "i18n.locale", i18n.resolve("messages_" + locale.getLanguage() + ".properties"));
        if (servingPlugin != null) {
            Path pluginI18n = servingPlugin.installDir().resolve(I18N);
            fold(digest, "plugin.i18n.default", pluginI18n.resolve("messages.properties"));
            fold(digest, "plugin.i18n.locale",
                    pluginI18n.resolve("messages_" + locale.getLanguage() + ".properties"));
        }
    }

    /**
     * The resolved site record, canonically — what {@code site.yml} says <em>as loaded</em>. Folding the record and
     * not the file keeps a hand edit of {@code site.yml} from moving the ETag before a site reload applies it (the
     * render reads the record, so the old body would otherwise go out under the new ETag).
     */
    private String siteRecord(Site site) {
        return loaded == null ? CanonicalFingerprint.digest(site) : loaded.siteRecord(site);
    }

    /** The loaded plugin a {@code __plugin__/<id>/…} body template belongs to, or {@code null}. */
    private LoadedPlugin pluginFor(String bodyTemplate) {
        String pluginPrefix = "__plugin__/";
        if (runningPlugins == null || !bodyTemplate.startsWith(pluginPrefix)) {
            return null;
        }
        String rest = bodyTemplate.substring(pluginPrefix.length());
        int slash = rest.indexOf('/');
        if (slash <= 0 || slash == rest.length() - 1) {
            return null;
        }
        String pluginId = rest.substring(0, slash);
        for (LoadedPlugin plugin : runningPlugins.installed()) {
            if (plugin.id().equals(pluginId)) {
                return plugin;
            }
        }
        return null;
    }

    /**
     * Resolves a body-template logical name to one of its files: {@code __plugin__/<id>/<name>} under the
     * loaded plugin's {@code templates/} dir, {@code __site__/<siteId>/<name>} under the tenant's own —
     * {@code null} (folds as absent) for anything unresolvable, so a bad name can never read outside its
     * namespace.
     */
    private Path bodyTemplateFile(String siteId, Path siteTemplates, String bodyTemplate, String suffix) {
        String pluginPrefix = "__plugin__/";
        String sitePrefix = "__site__/" + siteId + "/";
        try {
            if (bodyTemplate.startsWith(pluginPrefix)) {
                LoadedPlugin plugin = pluginFor(bodyTemplate);
                if (plugin == null) {
                    return null;
                }
                String name = bodyTemplate.substring(bodyTemplate.indexOf('/', pluginPrefix.length()) + 1);
                return PathSafety.resolveConfined(plugin.installDir().resolve(TEMPLATES), name + suffix);
            }
            if (bodyTemplate.startsWith(sitePrefix)) {
                return PathSafety.resolveConfined(siteTemplates, bodyTemplate.substring(sitePrefix.length()) + suffix);
            }
        } catch (RuntimeException escapes) {
            return null;
        }
        return null;
    }

    /** Resolves {@code leaf} under {@code templates}, or {@code null} if it would escape the tenant. */
    private static Path confine(Path templates, String leaf) {
        try {
            return PathSafety.resolveConfined(templates, leaf);
        } catch (RuntimeException escapes) {
            return null;
        }
    }

    /** Folds a file's role label and content (or an {@code absent} marker). */
    private static void fold(MessageDigest digest, String label, Path file) {
        try {
            if (file != null && Files.isRegularFile(file)) {
                long size = Files.size(file);
                if (size > MAX_FOLD_BYTES) {
                    // An oversized operator-planted input must not be read whole into the heap on EVERY render.
                    // Fold a size+mtime fingerprint instead — still changes the ETag whenever the file is edited.
                    long mtime = Files.getLastModifiedTime(file).toMillis();
                    field(digest, label, KIND_LARGE, (size + ":" + mtime).getBytes(StandardCharsets.UTF_8));
                } else {
                    field(digest, label, KIND_FILE, Files.readAllBytes(file));
                }
            } else {
                field(digest, label, KIND_ABSENT, new byte[0]);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("hashing validator input " + file, ex);
        }
    }

    /** Folds a labelled string token; a {@code null} value folds distinctly from {@code ""}. */
    private static void token(MessageDigest digest, String label, String value) {
        if (value == null) {
            field(digest, label, KIND_NULL, new byte[0]);
        } else {
            field(digest, label, KIND_TOKEN, value.getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * Folds one self-delimiting field: {@code <labelLen>:<label><kind><valueLen>:<value>}. The decimal byte
     * lengths make the encoding prefix-free — every field boundary is recoverable from the digest input — so no
     * choice of file contents can shift bytes from one field into another and make two different input sets fold
     * to the same stream. (The {@code :} terminator is printable, never a control character.)
     */
    static void field(MessageDigest digest, String label, byte kind, byte[] value) {
        byte[] labelBytes = label.getBytes(StandardCharsets.UTF_8);
        digest.update((labelBytes.length + ":").getBytes(StandardCharsets.US_ASCII));
        digest.update(labelBytes);
        digest.update(kind);
        digest.update((value.length + ":").getBytes(StandardCharsets.US_ASCII));
        digest.update(value);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    /** First {@code bytes} of the digest as lowercase hex. */
    private static String hex(byte[] digest, int bytes) {
        StringBuilder out = new StringBuilder(bytes * 2);
        for (int i = 0; i < bytes && i < digest.length; i++) {
            out.append(Character.forDigit((digest[i] >> 4) & 0xF, 16));
            out.append(Character.forDigit(digest[i] & 0xF, 16));
        }
        return out.toString();
    }

    /**
     * Folds every input the head seam declares for this site, plus a token identifying the running plugin set.
     *
     * <h2>Why this is not optional</h2>
     * A strong {@code ETag} promises byte identity. Head markup is injected at the response edge by
     * {@code HeadInjectionFilter}, which runs <b>after</b> the controller has computed this validator and may
     * already have returned {@code 304} with no body at all. Before this, a change to a site's SEO config, its
     * icons, or a plugin's per-site settings changed the delivered page while leaving the validator identical
     * — so every client and CDN holding the old ETag revalidated, got {@code 304}, and kept the stale head
     * until some unrelated input (a page file, the master, i18n, {@code site.yml}) happened to move.
     *
     * <p>That is the same false-assurance shape as a config reload that does not evict: the operator checks
     * with a fresh fetch, sees the new value, and concludes it is live everywhere.
     *
     * <p>The plugin token covers the other half — a plugin upgrade that changes head-rendering <b>code</b>
     * rather than any file. It is coarse on purpose (an upgrade moves every page's ETag on the site) and it is
     * computed once, because plugins cannot change without a restart.
     *
     * <p>Paths come from {@code HeadContributor#headInputs}, are confined to the site folder, and are
     * de-duplicated in a stable order so two contributors naming the same file fold it once and the digest
     * does not depend on bean-registration order.
     *
     * <p><b>Configuration the kernel loads is folded as loaded, not as a file</b> ({@link LoadedSiteInputs}):
     * a declared {@code seo.yml}, {@code manifest.yml}, {@code icons}, {@code site.yml} or
     * {@code plugins/<id>.yml} of a running plugin with {@code siteSettings:} is skipped here, because its loaded
     * state is folded below — the SEO / icon / manifest head state, those plugins' per-site settings for this site
     * (declared as a head input or not), and
     * every running plugin's app-level settings. Folding the file as well would move the ETag the moment the file is
     * edited, while the render keeps the loaded copy until a save or a reload applies it.
     */
    private void foldHeadInputs(MessageDigest digest, String siteId) {
        Path siteDir = layout.siteDir(siteId);
        if (headContributors != null) {
            java.util.Set<String> replaced = loaded == null ? java.util.Set.of() : loaded.replacedDeclaredInputs();
            for (String relative : headContributors.declaredInputs(siteId)) {
                Path target = confine(siteDir, relative);
                if (target != null && replaced.contains(siteRelative(siteDir, target))) {
                    continue; // folded as loaded, below
                }
                if (target != null && Files.isDirectory(target)) {
                    foldListing(digest, "head." + relative, target);
                } else {
                    fold(digest, "head." + relative, target);
                }
            }
            token(digest, "plugins", pluginSetToken());
        }
        if (loaded != null) {
            token(digest, "head.loaded", loaded.headState(siteId));
            token(digest, "plugins.site", loaded.pluginSiteSettings(siteId));
            token(digest, "plugins.app", loaded.pluginAppSettings());
        }
    }

    /** {@code target} relative to {@code siteDir}, normalized and {@code /}-separated (e.g. {@code plugins/x.yml}). */
    private static String siteRelative(Path siteDir, Path target) {
        return siteDir.normalize().relativize(target.normalize()).toString().replace('\\', '/');
    }

    /**
     * Folds a folder's <b>listing</b> — sorted child names with their sizes and mtimes — not its contents.
     *
     * <p>The head advertises which icons a site HAS; the bytes of a PNG do not shape the markup and reading
     * them on every conditional request would be a real cost for no signal. Sorted because
     * {@link Files#list} order is filesystem-dependent, and a digest that depends on readdir order would
     * differ between two nodes of a cluster serving the same site.
     */
    private static void foldListing(MessageDigest digest, String label, Path dir) {
        try (java.util.stream.Stream<Path> children = Files.list(dir)) {
            java.util.List<String> entries = new java.util.ArrayList<>();
            for (Path child : children.toList()) {
                long mtime = Files.getLastModifiedTime(child).toMillis();
                long size = Files.isRegularFile(child) ? Files.size(child) : -1L;
                // The name is length-prefixed: a file name may itself contain ':' or ','.
                StringBuilder entry = new StringBuilder();
                lp(entry, child.getFileName().toString());
                entries.add(entry.append(size).append(':').append(mtime).append(';').toString());
            }
            java.util.Collections.sort(entries);
            field(digest, label + "/", KIND_TOKEN, String.join("", entries).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new UncheckedIOException("hashing validator input " + dir, ex);
        }
    }

    /** {@code id/version} of every running plugin, sorted; computed once (a plugin change needs a restart). */
    private String pluginSetToken() {
        String token = pluginSetToken;
        if (token == null) {
            token = runningPlugins == null ? "" : runningPlugins.installed().stream()
                    .map(plugin -> plugin.id() + "/" + plugin.descriptor().version())
                    .sorted()
                    .collect(java.util.stream.Collectors.joining(","));
            pluginSetToken = token;
        }
        return token;
    }
}
