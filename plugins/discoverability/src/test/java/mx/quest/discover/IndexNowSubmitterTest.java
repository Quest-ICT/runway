package mx.quest.discover;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import mx.quest.runway.spi.model.CatalogChange;
import mx.quest.runway.spi.model.LocalizedUrl;
import mx.quest.runway.spi.model.SiteLifecycleEvent;
import mx.quest.runway.spi.model.SiteView;
import mx.quest.runway.spi.service.PluginSettings;
import mx.quest.runway.spi.service.SiteEventListener;
import mx.quest.runway.spi.testkit.EventDispatch;
import mx.quest.runway.spi.testkit.FakeSiteServices;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.EmbeddedValueResolver;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.expression.StandardBeanExpressionResolver;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * IndexNow: the payload, the two switches, and the bounds.
 *
 * <h2>What is worth testing here</h2>
 * The protocol itself is four JSON fields — there is nothing clever to get right. What can go wrong is
 * everything <b>around</b> it, and all of it fails quietly:
 *
 * <ul>
 *   <li>a switch that does not actually gate, so a deployment starts making outbound calls it never agreed to;</li>
 *   <li>a bound that does not bound, so one tenant's bulk edit becomes an unbounded request;</li>
 *   <li>an unescaped tenant-controlled value in a hand-built JSON body.</li>
 * </ul>
 *
 * <p>The HTTP call is the injected {@link IndexNowSubmitter.Exchange} seam, so every one of those is exercised
 * with no network at all — and the recording exchange makes "did anything go out?" an assertion rather than an
 * assumption.
 */
class IndexNowSubmitterTest {

    /** An exchange that answers 200 and remembers every call — so "nothing went out" is provable. */
    private static final class Recorder implements IndexNowSubmitter.Exchange {

        private final List<String> payloads = new ArrayList<>();
        private int status = 200;

        @Override
        public Response post(String url, String json) {
            payloads.add(json);
            return new Response(status, "ok");
        }
    }

    private static DiscoverabilityProperties props(boolean platformEnabled) {
        DiscoverabilityProperties p = new DiscoverabilityProperties();
        p.getIndexnow().setEnabled(platformEnabled);
        return p;
    }

    private static PluginSettings config(Map<String, Object> cfg) {
        return TestSettings.declared().site("acme", cfg);
    }

    private static final Locale EN = Locale.ENGLISH;
    private static final Locale ES = Locale.forLanguageTag("es");

    private static LocalizedUrl url(Locale locale, String path) {
        return new LocalizedUrl(locale, path, null);
    }

    /** A local page change, as the host delivers it: the item's URLs before and after. */
    private static CatalogChange page(String slug, List<LocalizedUrl> before, List<LocalizedUrl> after) {
        return new CatalogChange("acme", CatalogChange.Cause.PAGE, "pages", slug, null, before, after, true);
    }

    /** A submitter with both switches on, a key, and a site at https://acme.test. */
    private static IndexNowSubmitter enabled(IndexNowSubmitter.Exchange exchange) {
        return new IndexNowSubmitter(sites("acme", "https://acme.test"),
                config(Map.of("indexnow.enabled", true, "indexnow.key", "k")), props(true), exchange);
    }

    // --- the payload ---------------------------------------------------------------------------------

    @Test
    void thePayloadCarriesTheFourFieldsTheProtocolNeeds() {
        String json = IndexNowSubmitter.payload("acme.test", "abc123",
                "https://acme.test/indexnow-key.txt", List.of("https://acme.test/a", "https://acme.test/b"));

        assertEquals("{\"host\":\"acme.test\",\"key\":\"abc123\","
                + "\"keyLocation\":\"https://acme.test/indexnow-key.txt\","
                + "\"urlList\":[\"https://acme.test/a\",\"https://acme.test/b\"]}", json);
    }

    /**
     * Tenant-controlled values are escaped.
     *
     * <p>{@code host}, {@code key} and {@code keyLocation} all come from per-site configuration, and this body
     * is assembled by hand. An unescaped quote there is a payload-injection hole, not a formatting bug — and
     * one that only shows up when somebody puts a quote in a setting.
     */
    @Test
    void tenantControlledValuesCannotBreakOutOfTheJson() {
        String json = IndexNowSubmitter.payload("acme.test", "ab\"c\\d\ne", "https://acme.test/k.txt",
                List.of("https://acme.test/a\"b"));

        assertFalse(json.contains("ab\"c"), json);
        assertTrue(json.contains("ab\\\"c\\\\d\\ne"), json);
        assertTrue(json.contains("https://acme.test/a\\\"b"), json);
    }

    @Test
    void theHostFieldIsTheBareHostname() {
        assertEquals("acme.test", IndexNowSubmitter.hostOf("https://acme.test"));
        assertEquals("acme.test", IndexNowSubmitter.hostOf("https://acme.test/some/path"));
        assertEquals("", IndexNowSubmitter.hostOf("not a url"));
    }

    // --- the two switches ----------------------------------------------------------------------------

    /**
     * A tenant cannot switch on outbound traffic by itself.
     *
     * <p>The platform operator owns the network, the egress firewall and the incident. This is the assertion
     * that the operator's switch is a real gate rather than a label — and it is made against whether anything
     * left the process, because that is what "off" has to mean.
     */
    @Test
    void nothingIsSubmittedWhenThePlatformHasNotPermittedIt() {
        Recorder exchange = new Recorder();
        IndexNowSubmitter submitter = new IndexNowSubmitter(sites("acme", "https://acme.test"),
                config(Map.of("indexnow.enabled", true, "indexnow.key", "abc")), props(false), exchange);

        // Through submit(), NOT flush(): flush() returns early on an empty queue, so a flush-based version of
        // this test passes whether or not the gate exists. A test of a switch has to be able to fail when the
        // switch is removed, and this one does — it is the same "asserting nothing" shape as scanning a
        // compressed archive for a plaintext secret.
        submitter.submit("acme", List.of("https://acme.test/a"));

        assertTrue(exchange.payloads.isEmpty(), "the platform switch must gate the network, not just the UI");
    }

    /** The documented WARN for a site that wants IndexNow while the platform says no — and only for such a site. */
    @Test
    void aSiteThatEnabledItWhileThePlatformHasNotIsWarnedOnce() {
        Recorder exchange = new Recorder();
        IndexNowSubmitter wants = new IndexNowSubmitter(sites("acme", "https://acme.test"),
                config(Map.of("indexnow.enabled", true, "indexnow.key", "abc")), props(false), exchange);
        wants.onCatalogChanged(page("home", List.of(), List.of(url(EN, "/"))));
        wants.onCatalogChanged(page("about", List.of(url(EN, "/about")), List.of(url(EN, "/about"))));
        assertTrue(wants.warnedInert("acme"), "the operator is told which setting to flip");
        assertTrue(exchange.payloads.isEmpty(), "and still nothing leaves the process");

        IndexNowSubmitter doesNot = new IndexNowSubmitter(sites("acme", "https://acme.test"),
                config(Map.of()), props(false), new Recorder());
        doesNot.onCatalogChanged(page("home", List.of(), List.of(url(EN, "/"))));
        assertFalse(doesNot.warnedInert("acme"), "a site that never asked for it is not warned about it");
    }

    /** …and the positive control: the identical call DOES submit once the platform permits it. */
    @Test
    void theSameCallSubmitsOnceThePlatformPermitsIt() {
        Recorder exchange = new Recorder();
        new IndexNowSubmitter(sites("acme", "https://acme.test"),
                config(Map.of("indexnow.enabled", true, "indexnow.key", "abc")), props(true), exchange)
                .submit("acme", List.of("https://acme.test/a"));

        assertEquals(1, exchange.payloads.size(), "otherwise the test above proves only that nothing works");
    }

    /** …and a site that has not enabled it is not submitted for either. */
    @Test
    void nothingIsSubmittedForASiteThatHasNotEnabledIt() {
        Recorder exchange = new Recorder();
        IndexNowSubmitter submitter = new IndexNowSubmitter(sites("acme", "https://acme.test"),
                config(Map.of("indexnow.key", "abc")), props(true), exchange);

        submitter.submit("acme", List.of("https://acme.test/a"));

        assertTrue(exchange.payloads.isEmpty(), "indexnow.enabled defaults false — silence is the default");
    }

    /** A site with the switch on but no key submits nothing: there would be nothing for the engine to verify. */
    @Test
    void nothingIsSubmittedWithoutAKey() {
        Recorder exchange = new Recorder();
        IndexNowSubmitter submitter = new IndexNowSubmitter(sites("acme", "https://acme.test"),
                config(Map.of("indexnow.enabled", true)), props(true), exchange);

        submitter.submit("acme", List.of("https://acme.test/a"));

        assertTrue(exchange.payloads.isEmpty());
    }

    /** With both switches on and a key set, the submission goes out and names the key file's fixed location. */
    @Test
    void bothSwitchesOnSubmitsAndPointsAtTheKeyFile() {
        Recorder exchange = new Recorder();
        IndexNowSubmitter submitter = new IndexNowSubmitter(sites("acme", "https://acme.test"),
                config(Map.of("indexnow.enabled", true, "indexnow.key", "abc123")), props(true), exchange);

        submitter.submit("acme", List.of("https://acme.test/a"));

        assertEquals(1, exchange.payloads.size());
        assertTrue(exchange.payloads.get(0).contains("\"keyLocation\":\"https://acme.test/indexnow-key.txt\""),
                exchange.payloads.get(0));
        assertTrue(exchange.payloads.get(0).contains("\"host\":\"acme.test\""), exchange.payloads.get(0));
    }

    // --- the bounds ----------------------------------------------------------------------------------

    /**
     * A submission larger than the cap is trimmed, not sent whole.
     *
     * <p>A bulk edit or a restore can change every URL a site has. Without this the body is unbounded, which is
     * a problem for the endpoint, for the tenant's egress and for anyone reading the log.
     */
    @Test
    void aSubmissionOverTheCapIsTrimmedRatherThanSentWhole() {
        Recorder exchange = new Recorder();
        DiscoverabilityProperties p = props(true);
        p.getIndexnow().setMaxUrlsPerSubmission(3);
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            many.add("https://acme.test/p" + i);
        }

        new IndexNowSubmitter(sites("acme", "https://acme.test"),
                config(Map.of("indexnow.enabled", true, "indexnow.key", "k")), p, exchange)
                .submit("acme", many);

        assertEquals(1, exchange.payloads.size());
        assertEquals(3, exchange.payloads.get(0).split("https://acme.test/p", -1).length - 1,
                exchange.payloads.get(0));
    }

    /**
     * A failing endpoint does not throw into the caller, and does not retry.
     *
     * <p>A freshness ping that arrives late is worthless, and an unbounded retry queue during an outage is a
     * liability. The pages are still crawled the ordinary way, so the honest behaviour is to log and drop.
     */
    @Test
    void aFailingEndpointIsLoggedAndDroppedRatherThanRetried() {
        IndexNowSubmitter.Exchange failing = (url, json) -> {
            throw new IOException("connection refused");
        };
        IndexNowSubmitter submitter = new IndexNowSubmitter(sites("acme", "https://acme.test"),
                config(Map.of("indexnow.enabled", true, "indexnow.key", "k")), props(true), failing);

        submitter.submit("acme", List.of("https://acme.test/a"));   // must not throw
    }

    /** A refusal (403 = key file did not verify) is likewise survivable. */
    @Test
    void aRefusalIsSurvivable() {
        Recorder exchange = new Recorder();
        exchange.status = 403;
        new IndexNowSubmitter(sites("acme", "https://acme.test"),
                config(Map.of("indexnow.enabled", true, "indexnow.key", "k")), props(true), exchange)
                .submit("acme", List.of("https://acme.test/a"));

        assertEquals(1, exchange.payloads.size(), "it was attempted");
    }

    /** A site with no base URL cannot be submitted for — there is no host to name. */
    @Test
    void aSiteWithNoBaseUrlIsSkipped() {
        Recorder exchange = new Recorder();
        new IndexNowSubmitter(sites("acme", ""),
                config(Map.of("indexnow.enabled", true, "indexnow.key", "k")), props(true), exchange)
                .submit("acme", List.of("/a"));

        assertTrue(exchange.payloads.isEmpty());
    }

    // --- what a change queues (the SPI change events) ------------------------------------------------

    /**
     * A DELETED page reports where it was, and that address is submitted. 1.x resolved the URLs after
     * the host had dropped the page from its listing, so a deletion — the change most worth telling an engine
     * about — submitted nothing.
     */
    @Test
    void aDeletedPagesOldUrlsAreSubmitted() {
        Recorder exchange = new Recorder();
        IndexNowSubmitter submitter = enabled(exchange);

        submitter.onCatalogChanged(page("about", List.of(url(EN, "/about"), url(ES, "/es/acerca")), List.of()));
        submitter.flush();

        assertEquals(1, exchange.payloads.size());
        assertTrue(exchange.payloads.get(0).endsWith(
                "\"urlList\":[\"https://acme.test/about\",\"https://acme.test/es/acerca\"]}"), exchange.payloads.get(0));
    }

    /** A moved item submits the old address AND the new one, each once, previous first. */
    @Test
    void aRenameSubmitsTheUnionOfBeforeAndAfterWithoutDuplicates() {
        Recorder exchange = new Recorder();
        IndexNowSubmitter submitter = enabled(exchange);

        submitter.onCatalogChanged(page("about", List.of(url(EN, "/about"), url(ES, "/es/acerca")),
                List.of(url(EN, "/about-us"), url(ES, "/es/acerca"))));
        submitter.flush();

        assertTrue(exchange.payloads.get(0).endsWith("\"urlList\":[\"https://acme.test/about\","
                + "\"https://acme.test/es/acerca\",\"https://acme.test/about-us\"]}"), exchange.payloads.get(0));
    }

    /** A new page has no previous URLs: its current ones are submitted. */
    @Test
    void aNewPageSubmitsItsCurrentUrls() {
        Recorder exchange = new Recorder();
        IndexNowSubmitter submitter = enabled(exchange);
        submitter.onCatalogChanged(page("news", List.of(), List.of(url(EN, "/news"))));
        submitter.flush();
        assertTrue(exchange.payloads.get(0).contains("\"urlList\":[\"https://acme.test/news\"]"));
    }

    /** A content source's item (a blog entry) is handled exactly like a page. */
    @Test
    void aContentSourceChangeIsSubmittedLikeAPage() {
        Recorder exchange = new Recorder();
        IndexNowSubmitter submitter = enabled(exchange);
        submitter.onCatalogChanged(new CatalogChange("acme", CatalogChange.Cause.CONTENT, "blog", "hello", "blog",
                List.of(url(EN, "/blog/hello")), List.of(), true));
        submitter.flush();
        assertTrue(exchange.payloads.get(0).contains("https://acme.test/blog/hello"));
    }

    /**
     * A cluster replays every change on its other nodes ({@code local == false}). Only the node where
     * the change happened submits — otherwise N nodes send N identical submissions.
     */
    @Test
    void aPeersReplayedChangeIsIgnored() {
        Recorder exchange = new Recorder();
        IndexNowSubmitter submitter = enabled(exchange);
        submitter.onCatalogChanged(new CatalogChange("acme", CatalogChange.Cause.PAGE, "pages", "about", null,
                List.of(url(EN, "/about")), List.of(), false));
        submitter.flush();
        assertTrue(exchange.payloads.isEmpty(), "the originating node submits; a replay never does");
    }

    /** Changes that name no item carry no URLs: a lifecycle event, a settings save, a whole-site reload. */
    @Test
    void changesThatNameNoItemQueueNothing() {
        Recorder exchange = new Recorder();
        IndexNowSubmitter submitter = enabled(exchange);
        EventDispatch events = new EventDispatch("discoverability", Set.of()).listener(submitter);
        events.lifecycle("acme", SiteLifecycleEvent.Kind.RESTORED, true);
        events.settings("acme", "discoverability");
        events.page("acme", null, List.of(url(EN, "/x")), List.of());
        submitter.flush();
        assertTrue(exchange.payloads.isEmpty());
        assertTrue(events.failures().isEmpty(), events.failures().toString());
    }

    /** Delivered the kernel's way (phases, order), a page deletion reaches the queue. */
    @Test
    void itIsAConsumerListenerAndADispatchedDeletionReachesTheQueue() {
        Recorder exchange = new Recorder();
        IndexNowSubmitter submitter = enabled(exchange);
        assertEquals(SiteEventListener.Phase.CONSUMER, submitter.phase(), "it reads URLs after the sources dropped "
                + "their state — the phase whose currentUrls the host fills");
        new EventDispatch("discoverability", Set.of()).listener(submitter)
                .page("acme", "about", List.of(url(EN, "/about")), List.of());
        submitter.flush();
        assertEquals(1, exchange.payloads.size());
        assertTrue(exchange.payloads.get(0).contains("https://acme.test/about"));
    }

    /** One flush per batch: queued URLs are submitted once and the queue is emptied. */
    @Test
    void aFlushSubmitsOnceAndEmptiesTheQueue() {
        Recorder exchange = new Recorder();
        IndexNowSubmitter submitter = enabled(exchange);
        submitter.onCatalogChanged(page("a", List.of(), List.of(url(EN, "/a"))));
        submitter.onCatalogChanged(page("b", List.of(), List.of(url(EN, "/b"))));
        submitter.onCatalogChanged(page("a", List.of(url(EN, "/a")), List.of(url(EN, "/a"))));
        submitter.flush();
        submitter.flush();
        assertEquals(1, exchange.payloads.size(), "a second flush has nothing to send");
        assertTrue(exchange.payloads.get(0).endsWith("[\"https://acme.test/a\",\"https://acme.test/b\"]}"),
                exchange.payloads.get(0));
    }

    /** The per-site queue is bounded: overflow is dropped (with a WARN), not grown. */
    @Test
    void theQueueIsBoundedPerSite() {
        Recorder exchange = new Recorder();
        DiscoverabilityProperties p = props(true);
        p.getIndexnow().setMaxQueuedPerSite(2);
        IndexNowSubmitter submitter = new IndexNowSubmitter(sites("acme", "https://acme.test"),
                config(Map.of("indexnow.enabled", true, "indexnow.key", "k")), p, exchange);
        submitter.onCatalogChanged(page("x", List.of(), List.of(url(EN, "/1"), url(ES, "/es/1"))));
        submitter.onCatalogChanged(page("y", List.of(), List.of(url(EN, "/2"))));
        submitter.flush();
        assertTrue(exchange.payloads.get(0).endsWith("[\"https://acme.test/1\",\"https://acme.test/es/1\"]}"),
                "the overflow was dropped: " + exchange.payloads.get(0));
    }

    /** With the platform switch off, a change queues nothing — the flush has nothing even if the switch flips. */
    @Test
    void withThePlatformOffAChangeQueuesNothing() {
        Recorder exchange = new Recorder();
        DiscoverabilityProperties p = props(false);
        IndexNowSubmitter submitter = new IndexNowSubmitter(sites("acme", "https://acme.test"),
                config(Map.of("indexnow.enabled", true, "indexnow.key", "k")), p, exchange);
        submitter.onCatalogChanged(page("x", List.of(url(EN, "/x")), List.of()));
        p.getIndexnow().setEnabled(true);
        submitter.flush();
        assertTrue(exchange.payloads.isEmpty());
    }

    /** A site with no configured base URL cannot be submitted for, so nothing is even queued. */
    @Test
    void aChangeOnASiteWithNoBaseUrlQueuesNothing() {
        Recorder exchange = new Recorder();
        IndexNowSubmitter submitter = new IndexNowSubmitter(sites("acme", null),
                config(Map.of("indexnow.enabled", true, "indexnow.key", "k")), props(true), exchange);
        submitter.onCatalogChanged(page("x", List.of(url(EN, "/x")), List.of()));
        submitter.flush();
        assertTrue(exchange.payloads.isEmpty());
    }

    /** An unknown site (deleted between the change and now) queues nothing and does not fail the dispatch. */
    @Test
    void anUnknownSiteQueuesNothing() {
        Recorder exchange = new Recorder();
        IndexNowSubmitter submitter = new IndexNowSubmitter(new FakeSiteServices(),
                config(Map.of("indexnow.enabled", true, "indexnow.key", "k")), props(true), exchange);
        submitter.onCatalogChanged(page("x", List.of(url(EN, "/x")), List.of()));
        submitter.flush();
        assertTrue(exchange.payloads.isEmpty());
    }

    private static FakeSiteServices sites(String id, String baseUrl) {
        return new FakeSiteServices().site(new SiteView(id, "Acme", EN, List.of(EN, ES), baseUrl));
    }

    /** A hand-set batch interval outside the form's 5..3600 seconds is clamped, never handed to the scheduler as is. */
    @Test
    void aHandSetBatchIntervalIsKeptInsideTheFormsRange() throws Exception {
        String expression =
                IndexNowSubmitter.class.getMethod("flush").getAnnotation(Scheduled.class).fixedDelayString();
        assertEquals("60000", delayFor(expression, null), "unset: the 60-second default");
        assertEquals("60000", delayFor(expression, "60"));
        assertEquals("5000", delayFor(expression, "0"));
        assertEquals("5000", delayFor(expression, "-30"));
        assertEquals("3600000", delayFor(expression, "99999"));
    }

    private static String delayFor(String expression, String value) {
        MockEnvironment env = new MockEnvironment();
        if (value != null) {
            env.setProperty("runway.plugin.discoverability.indexnow.batch-seconds", value);
        }
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.addEmbeddedValueResolver(env::resolveRequiredPlaceholders);
        factory.setBeanExpressionResolver(new StandardBeanExpressionResolver());
        return new EmbeddedValueResolver(factory).resolveStringValue(expression);
    }
}
