package mx.quest.runway.cluster;

import java.io.Serializable;

/**
 * The wire form of a cross-node cache-invalidation event, carried over the Hazelcast events topic by
 * {@link ClusterEventRelay}. One flat envelope covers all three relayed kinds — a site lifecycle change, a
 * per-tenant plugin-config change, and a page edit — so the topic carries a single {@code Serializable} type
 * with predictable round-trip.
 *
 * <p>Deliberately a hand-rolled POJO (no-arg constructor + getters/setters + a fixed {@code serialVersionUID}),
 * not a {@code record}: Hazelcast's default Java serializer round-trips a mutable JavaBean most predictably
 * across versions, and every field is a {@link String} or an enum for the same reason (the
 * {@code SiteChangedEvent.Kind} is carried as its {@link Enum#name() name} rather than the enum itself, so an
 * older node that lacks a newly-added kind fails to reconstruct that one event rather than to deserialize the
 * whole envelope). Only the fields relevant to a given {@link #getKind() kind} are set; the rest stay
 * {@code null}.
 *
 * <p>{@link #getOriginNodeId()} tags the node that first published the event, so the relay drops the
 * self-echo Hazelcast delivers back to the publisher (the mandatory loop guard).
 */
public class ClusterEventEnvelope implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Which local {@code ApplicationEvent} this envelope carries across the cluster.
     *
     * <p><b>Rolling-upgrade note.</b> Adding a constant means an older node cannot deserialize <em>that
     * kind's</em> envelopes during a mixed-version window (Java enum deserialization is by name) — the
     * deserialization failure surfaces in <em>Hazelcast's</em> topic-listener machinery (logged there and the
     * message dropped; the relay's own malformed-envelope guard only covers envelopes that deserialized), and
     * the older node's caches converge via their TTLs. Acceptable pre-1.0 while clustering is opt-in and
     * default-OFF; upgrade all nodes of a cluster together.
     */
    public enum Kind {
        /** A {@code site.SiteChangedEvent} — {@link #getSiteChangedKind()} + optional {@link #getTemplateId()}. */
        SITE_CHANGED,
        /** A {@code plugin.SitePluginConfigChangedEvent} — {@link #getSiteId()} + {@link #getPluginId()}. */
        SITE_PLUGIN_CONFIG_CHANGED,
        /** A {@code page.PageChangedEvent} — {@link #getSiteId()} + {@link #getSlug()} (slug nullable = whole site). */
        PAGE_CHANGED,
        /**
         * A {@code content.ContentChangedEvent} — {@link #getSiteId()} + {@link #getSourceKey()} +
         * {@link #getContentId()} (nullable ⇒ the source's whole corpus for the site).
         */
        CONTENT_CHANGED
    }

    private Kind kind;
    private String siteId;
    /** For {@link Kind#SITE_CHANGED}: the {@code SiteChangedEvent.Kind} name; otherwise {@code null}. */
    private String siteChangedKind;
    /** For {@link Kind#SITE_CHANGED}: the birth-only seed-template id (nullable); otherwise {@code null}. */
    private String templateId;
    /**
     * For {@link Kind#SITE_CHANGED}: whether a {@code CREATED} imports the site from a backup (so peers' birth-time
     * seeders stand down too — review m14a). Boxed on purpose: an envelope from a node that predates the field
     * deserializes with {@code null}, which reads as {@code false}; adding a field keeps Java serialization
     * compatible both ways, so the {@code serialVersionUID} is unchanged.
     */
    private Boolean fromBackup;
    /** For {@link Kind#SITE_PLUGIN_CONFIG_CHANGED}: the plugin id; otherwise {@code null}. */
    private String pluginId;
    /** For {@link Kind#PAGE_CHANGED}: the page slug (nullable ⇒ whole site); otherwise {@code null}. */
    private String slug;
    /** For {@link Kind#CONTENT_CHANGED}: the content source's key; otherwise {@code null}. */
    private String sourceKey;
    /** For {@link Kind#CONTENT_CHANGED}: the content id (nullable ⇒ whole corpus); otherwise {@code null}. */
    private String contentId;
    /** The id of the node that first published this event — used to drop the self-echo. */
    private String originNodeId;

    /** Required no-arg constructor for the Hazelcast Java serializer. */
    public ClusterEventEnvelope() {
    }

    public ClusterEventEnvelope(Kind kind, String siteId, String siteChangedKind, String templateId,
                                String pluginId, String slug, String originNodeId) {
        this.kind = kind;
        this.siteId = siteId;
        this.siteChangedKind = siteChangedKind;
        this.templateId = templateId;
        this.pluginId = pluginId;
        this.slug = slug;
        this.originNodeId = originNodeId;
    }

    /** A {@link Kind#SITE_CHANGED} envelope that is not a backup import. */
    public static ClusterEventEnvelope siteChanged(String siteId, String siteChangedKind, String templateId,
                                                   String originNodeId) {
        return siteChanged(siteId, siteChangedKind, templateId, false, originNodeId);
    }

    /** A {@link Kind#SITE_CHANGED} envelope, carrying whether a {@code CREATED} imports the site from a backup. */
    public static ClusterEventEnvelope siteChanged(String siteId, String siteChangedKind, String templateId,
                                                   boolean fromBackup, String originNodeId) {
        ClusterEventEnvelope env = new ClusterEventEnvelope(Kind.SITE_CHANGED, siteId, siteChangedKind, templateId,
                null, null, originNodeId);
        env.setFromBackup(fromBackup);
        return env;
    }

    /** A {@link Kind#SITE_PLUGIN_CONFIG_CHANGED} envelope. */
    public static ClusterEventEnvelope sitePluginConfigChanged(String siteId, String pluginId,
                                                               String originNodeId) {
        return new ClusterEventEnvelope(Kind.SITE_PLUGIN_CONFIG_CHANGED, siteId, null, null, pluginId, null,
                originNodeId);
    }

    /** A {@link Kind#PAGE_CHANGED} envelope. */
    public static ClusterEventEnvelope pageChanged(String siteId, String slug, String originNodeId) {
        return new ClusterEventEnvelope(Kind.PAGE_CHANGED, siteId, null, null, null, slug, originNodeId);
    }

    /** A {@link Kind#CONTENT_CHANGED} envelope ({@code contentId} nullable ⇒ the source's whole corpus). */
    public static ClusterEventEnvelope contentChanged(String siteId, String sourceKey, String contentId,
                                                      String originNodeId) {
        ClusterEventEnvelope env = new ClusterEventEnvelope(Kind.CONTENT_CHANGED, siteId, null, null, null, null,
                originNodeId);
        env.setSourceKey(sourceKey);
        env.setContentId(contentId);
        return env;
    }

    public Kind getKind() {
        return kind;
    }

    public void setKind(Kind kind) {
        this.kind = kind;
    }

    public String getSiteId() {
        return siteId;
    }

    public void setSiteId(String siteId) {
        this.siteId = siteId;
    }

    public String getSiteChangedKind() {
        return siteChangedKind;
    }

    public void setSiteChangedKind(String siteChangedKind) {
        this.siteChangedKind = siteChangedKind;
    }

    public String getTemplateId() {
        return templateId;
    }

    public void setTemplateId(String templateId) {
        this.templateId = templateId;
    }

    /** {@code null} on an envelope from a node that predates the field (read it as {@code false}). */
    public Boolean getFromBackup() {
        return fromBackup;
    }

    public void setFromBackup(Boolean fromBackup) {
        this.fromBackup = fromBackup;
    }

    public String getPluginId() {
        return pluginId;
    }

    public void setPluginId(String pluginId) {
        this.pluginId = pluginId;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public String getSourceKey() {
        return sourceKey;
    }

    public void setSourceKey(String sourceKey) {
        this.sourceKey = sourceKey;
    }

    public String getContentId() {
        return contentId;
    }

    public void setContentId(String contentId) {
        this.contentId = contentId;
    }

    public String getOriginNodeId() {
        return originNodeId;
    }

    public void setOriginNodeId(String originNodeId) {
        this.originNodeId = originNodeId;
    }
}
