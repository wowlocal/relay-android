package org.telegram.relay.mattermost.ids;

import java.util.ArrayList;
import java.util.List;

/**
 * The mutations of one allocator operation. A persistent store applies it atomically, in this order:
 * {@code renumbered}, {@code inserted}, {@code deletedPostIds}, {@code unmappedTombstones}, {@code islands}.
 */
public final class MessageIdChangeSet {
    public final String channelId;
    public final List<RenumberMove> renumbered;
    public final List<MessageIdEntry> inserted;
    public final List<String> deletedPostIds;
    /** Posts deleted before they were ever mapped; remembered so they're never inserted later. */
    public final List<String> unmappedTombstones;
    /** When non-null, replaces the channel's island list. */
    public final List<HistoryIsland> islands;

    public MessageIdChangeSet(String channelId, List<RenumberMove> renumbered, List<MessageIdEntry> inserted, List<String> deletedPostIds, List<String> unmappedTombstones, List<HistoryIsland> islands) {
        this.channelId = channelId;
        this.renumbered = renumbered != null ? renumbered : new ArrayList<RenumberMove>();
        this.inserted = inserted != null ? inserted : new ArrayList<MessageIdEntry>();
        this.deletedPostIds = deletedPostIds != null ? deletedPostIds : new ArrayList<String>();
        this.unmappedTombstones = unmappedTombstones != null ? unmappedTombstones : new ArrayList<String>();
        this.islands = islands;
    }

    public boolean isEmpty() {
        return renumbered.isEmpty() && inserted.isEmpty() && deletedPostIds.isEmpty() && unmappedTombstones.isEmpty() && islands == null;
    }
}
