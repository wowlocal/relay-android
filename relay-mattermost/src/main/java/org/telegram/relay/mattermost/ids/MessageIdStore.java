package org.telegram.relay.mattermost.ids;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Persistence for the post ↔ local message id map of every channel.
 *
 * <p>The allocator reads through these queries and writes through {@link #apply} once per operation,
 * so a SQLite implementation can run each operation in one transaction. Required indexes:
 * {@code (channel, post_id)}, {@code (channel, create_at, post_id)} and {@code (channel, local_id)}.
 * Within a channel the key order and the {@code local_id} order are always the same.
 */
public interface MessageIdStore {
    /** Mapped entries (tombstoned included) for the given post ids; unknown ids are absent. */
    Map<String, MessageIdEntry> entries(String channelId, List<String> postIds);

    /** Entries with {@code lower <= key <= upper}, ascending. */
    List<MessageIdEntry> entriesInRange(String channelId, PostOrderKey lower, PostOrderKey upper);

    /** Up to {@code limit} entries strictly older than {@code key} (all when null), nearest first (descending). */
    List<MessageIdEntry> entriesOlderThan(String channelId, PostOrderKey key, int limit);

    /** Up to {@code limit} entries strictly newer than {@code key} (all when null), nearest first (ascending). */
    List<MessageIdEntry> entriesNewerThan(String channelId, PostOrderKey key, int limit);

    /** Up to {@code limit} entries starting at {@code localId} (inclusive), ascending or descending by id. */
    List<MessageIdEntry> entriesFromLocalId(String channelId, int localId, boolean ascending, int limit);

    /** The channel's islands, ascending. */
    List<HistoryIsland> islands(String channelId);

    /** The subset of {@code postIds} that were deleted before ever being mapped. */
    Set<String> unmappedTombstones(String channelId, List<String> postIds);

    void apply(MessageIdChangeSet changes);
}
