package mx.quest.protection;

import java.util.ArrayList;
import java.util.List;

/**
 * The on-disk YAML shape of the manual IP blocklist ({@code external/blocklist.yml}) — a single container holding
 * the list of persisted operator blocks. Only <b>manual</b> blocks are stored (auto-escalated blocks are ephemeral
 * and never written); {@link IpBlocklist} rewrites this whole file on every manual change.
 *
 * <p>Mutable classes with no-arg constructors (not records) so Jackson can bind a partial/hand-edited file
 * field-by-field, defaulting any omitted field — {@link IpBlocklist} then normalizes (dropping already-expired
 * and blank-IP entries) on load.
 */
public class StoredBlocklist {

    private List<StoredEntry> entries = new ArrayList<>();

    public StoredBlocklist() {
    }

    public StoredBlocklist(List<StoredEntry> entries) {
        this.entries = entries != null ? entries : new ArrayList<>();
    }

    public List<StoredEntry> getEntries() {
        return entries;
    }

    public void setEntries(List<StoredEntry> entries) {
        this.entries = entries;
    }

    /**
     * One persisted manual block. {@code untilEpochSeconds == 0} means an indefinite block. There is no
     * {@code manual} field — everything in this file is manual by construction.
     */
    public static class StoredEntry {
        private String ip = "";
        private String reason = "";
        private long untilEpochSeconds;

        public StoredEntry() {
        }

        public StoredEntry(String ip, String reason, long untilEpochSeconds) {
            this.ip = ip;
            this.reason = reason;
            this.untilEpochSeconds = untilEpochSeconds;
        }

        public String getIp() {
            return ip;
        }

        public void setIp(String ip) {
            this.ip = ip;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }

        public long getUntilEpochSeconds() {
            return untilEpochSeconds;
        }

        public void setUntilEpochSeconds(long untilEpochSeconds) {
            this.untilEpochSeconds = untilEpochSeconds;
        }
    }
}
