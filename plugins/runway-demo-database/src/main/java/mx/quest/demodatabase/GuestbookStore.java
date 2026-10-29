package mx.quest.demodatabase;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;
import mx.quest.database.spi.Databases;
import mx.quest.database.spi.Dialect;
import mx.quest.database.spi.PluginDatabase;
import mx.quest.database.spi.Scope;

/**
 * The guestbook's SQL: the one place this plugin touches its table. Plain JDBC through the database plugin's scopes:
 * one connection and one transaction per call, so reading back the new id with {@link Dialect#lastValue} is reliable.
 *
 * <p>The same SQL runs on PostgreSQL and MariaDB: ids from {@link Dialect#nextValue}, paging with
 * {@link Dialect#limitOffset}, times written by the server ({@code CURRENT_TIMESTAMP(6)}, in UTC on both) and read
 * as instants.
 */
public class GuestbookStore {

    static final String TABLE = "runway_demo_database__entry";
    static final String SEQUENCE = "runway_demo_database__entry_seq";
    static final int NAME_MAX = 80;
    static final int MESSAGE_MAX = 1000;
    private static final Calendar UTC = Calendar.getInstance(TimeZone.getTimeZone("UTC"));

    /** One entry, as visitors see it. */
    public record Entry(long id, String name, String message, Instant at) {
    }

    /** A page of entries, newest first, and whether an older page exists. */
    public record Page(List<Entry> entries, int page, boolean more) {
    }

    private final Databases databases;
    private final GuestbookConsumer consumer;
    private volatile PluginDatabase db;

    public GuestbookStore(Databases databases, GuestbookConsumer consumer) {
        this.databases = databases;
        this.consumer = consumer;
    }

    /** This plugin's handle, asked for on first use (never while the platform is starting). */
    public PluginDatabase db() {
        PluginDatabase handle = db;
        if (handle == null) {
            handle = databases.of(consumer);
            db = handle;
        }
        return handle;
    }

    /**
     * Adds an entry and, in the same transaction, deletes the oldest ones beyond {@code cap}, so a busy or abused
     * guestbook never grows past its limit.
     *
     * @return the new entry's id
     */
    public long add(Scope site, String name, String message, int cap) {
        Dialect d = db().dialect();
        return site.transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + TABLE + " (id, version, name, message, "
                    + "created_at, updated_at, updater) VALUES (" + d.nextValue(SEQUENCE) + ", 0, ?, ?, "
                    + "CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6), 'visitor')")) {
                ps.setString(1, name);
                ps.setString(2, message);
                ps.executeUpdate();
            }
            long id = d.lastValue(c, SEQUENCE);
            Long cutoff = null;
            try (PreparedStatement ps = c.prepareStatement("SELECT id FROM " + TABLE + " ORDER BY id DESC "
                    + d.limitOffset(1, cap)); ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    cutoff = rs.getLong(1);
                }
            }
            if (cutoff != null) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + TABLE + " WHERE id <= ?")) {
                    ps.setLong(1, cutoff);
                    ps.executeUpdate();
                }
            }
            return id;
        });
    }

    /** The most entries a site can hold (the top of the {@code maxEntries} setting's range). */
    static final int ENTRIES_CEILING = 10_000;

    /**
     * One page of entries, newest first. A page past {@link #ENTRIES_CEILING} is empty without a query: the offset is
     * computed in {@code long}, so a huge page number from a URL cannot overflow into a negative offset.
     */
    public Page page(Scope site, int page, int size) {
        Dialect d = db().dialect();
        int p = Math.max(0, page);
        if ((long) p * size >= ENTRIES_CEILING) {
            return new Page(List.of(), p, false);
        }
        return site.read(c -> {
            List<Entry> entries = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id, name, message, created_at FROM " + TABLE
                    + " ORDER BY id DESC " + d.limitOffset(size + 1, p * size)); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    entries.add(new Entry(rs.getLong(1), rs.getString(2), rs.getString(3),
                            rs.getTimestamp(4, UTC).toInstant()));
                }
            }
            boolean more = entries.size() > size;
            return new Page(more ? List.copyOf(entries.subList(0, size)) : List.copyOf(entries), p, more);
        });
    }

    /** Deletes one entry; false when it was not there. */
    public boolean delete(Scope site, long id) {
        return site.transaction(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + TABLE + " WHERE id = ?")) {
                ps.setLong(1, id);
                return ps.executeUpdate() > 0;
            }
        });
    }

    /** How many entries a site has. */
    public int count(Scope site) {
        return site.read(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + TABLE);
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        });
    }
}
