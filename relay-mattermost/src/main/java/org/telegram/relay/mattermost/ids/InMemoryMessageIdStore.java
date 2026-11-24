package org.telegram.relay.mattermost.ids;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A {@link MessageIdStore} in memory: used by tests and as the reference for a persistent store. */
public final class InMemoryMessageIdStore implements MessageIdStore {
    private static final class Channel {
        /** Sorted by key (and therefore by local id). */
        List<MessageIdEntry> entries = new ArrayList<MessageIdEntry>();
        final Map<String, PostOrderKey> keyByPostId = new HashMap<String, PostOrderKey>();
        List<HistoryIsland> islands = new ArrayList<HistoryIsland>();
        final Set<String> tombstones = new HashSet<String>();

        int firstKeyNotLessThan(PostOrderKey key) {
            int low = 0;
            int high = entries.size();
            while (low < high) {
                int mid = (low + high) >>> 1;
                if (entries.get(mid).key.compareTo(key) < 0) {
                    low = mid + 1;
                } else {
                    high = mid;
                }
            }
            return low;
        }

        int firstKeyGreaterThan(PostOrderKey key) {
            int low = 0;
            int high = entries.size();
            while (low < high) {
                int mid = (low + high) >>> 1;
                if (key.compareTo(entries.get(mid).key) < 0) {
                    high = mid;
                } else {
                    low = mid + 1;
                }
            }
            return low;
        }

        int firstLocalIdNotLessThan(int localId) {
            int low = 0;
            int high = entries.size();
            while (low < high) {
                int mid = (low + high) >>> 1;
                if (entries.get(mid).localId < localId) {
                    low = mid + 1;
                } else {
                    high = mid;
                }
            }
            return low;
        }

        int indexOf(PostOrderKey key) {
            int index = firstKeyNotLessThan(key);
            if (index < entries.size() && entries.get(index).key.equals(key)) {
                return index;
            }
            return -1;
        }
    }

    private final Map<String, Channel> channels = new HashMap<String, Channel>();

    private static List<HistoryIsland> copyIslands(List<HistoryIsland> islands) {
        List<HistoryIsland> result = new ArrayList<HistoryIsland>(islands.size());
        for (HistoryIsland island : islands) {
            result.add(island.copy());
        }
        return result;
    }

    @Override
    public synchronized Map<String, MessageIdEntry> entries(String channelId, List<String> postIds) {
        Map<String, MessageIdEntry> result = new HashMap<String, MessageIdEntry>();
        Channel channel = channels.get(channelId);
        if (channel == null) {
            return result;
        }
        for (String postId : postIds) {
            PostOrderKey key = channel.keyByPostId.get(postId);
            if (key != null) {
                int index = channel.indexOf(key);
                if (index >= 0) {
                    result.put(postId, channel.entries.get(index));
                }
            }
        }
        return result;
    }

    @Override
    public synchronized List<MessageIdEntry> entriesInRange(String channelId, PostOrderKey lower, PostOrderKey upper) {
        Channel channel = channels.get(channelId);
        if (channel == null) {
            return new ArrayList<MessageIdEntry>();
        }
        int start = channel.firstKeyNotLessThan(lower);
        int end = channel.firstKeyGreaterThan(upper);
        if (start >= end) {
            return new ArrayList<MessageIdEntry>();
        }
        return new ArrayList<MessageIdEntry>(channel.entries.subList(start, end));
    }

    @Override
    public synchronized List<MessageIdEntry> entriesOlderThan(String channelId, PostOrderKey key, int limit) {
        Channel channel = channels.get(channelId);
        List<MessageIdEntry> result = new ArrayList<MessageIdEntry>();
        if (channel == null || limit <= 0) {
            return result;
        }
        int end = key != null ? channel.firstKeyNotLessThan(key) : channel.entries.size();
        int start = Math.max(0, end - limit);
        for (int i = end - 1; i >= start; i--) {
            result.add(channel.entries.get(i));
        }
        return result;
    }

    @Override
    public synchronized List<MessageIdEntry> entriesNewerThan(String channelId, PostOrderKey key, int limit) {
        Channel channel = channels.get(channelId);
        if (channel == null || limit <= 0) {
            return new ArrayList<MessageIdEntry>();
        }
        int start = key != null ? channel.firstKeyGreaterThan(key) : 0;
        int end = start + Math.min(limit, channel.entries.size() - start);
        if (start >= end) {
            return new ArrayList<MessageIdEntry>();
        }
        return new ArrayList<MessageIdEntry>(channel.entries.subList(start, end));
    }

    @Override
    public synchronized List<MessageIdEntry> entriesFromLocalId(String channelId, int localId, boolean ascending, int limit) {
        Channel channel = channels.get(channelId);
        List<MessageIdEntry> result = new ArrayList<MessageIdEntry>();
        if (channel == null || limit <= 0) {
            return result;
        }
        if (ascending) {
            int start = channel.firstLocalIdNotLessThan(localId);
            int end = start + Math.min(limit, channel.entries.size() - start);
            if (start < end) {
                result.addAll(channel.entries.subList(start, end));
            }
        } else {
            int end = channel.firstLocalIdNotLessThan(localId);
            if (end < channel.entries.size() && channel.entries.get(end).localId == localId) {
                end++;
            }
            int start = Math.max(0, end - limit);
            for (int i = end - 1; i >= start; i--) {
                result.add(channel.entries.get(i));
            }
        }
        return result;
    }

    @Override
    public synchronized List<HistoryIsland> islands(String channelId) {
        Channel channel = channels.get(channelId);
        return channel == null ? new ArrayList<HistoryIsland>() : copyIslands(channel.islands);
    }

    @Override
    public synchronized Set<String> unmappedTombstones(String channelId, List<String> postIds) {
        Set<String> result = new HashSet<String>();
        Channel channel = channels.get(channelId);
        if (channel == null || channel.tombstones.isEmpty()) {
            return result;
        }
        for (String postId : postIds) {
            if (channel.tombstones.contains(postId)) {
                result.add(postId);
            }
        }
        return result;
    }

    @Override
    public synchronized void apply(MessageIdChangeSet changes) {
        if (changes.isEmpty()) {
            return;
        }
        Channel channel = channels.get(changes.channelId);
        if (channel == null) {
            channel = new Channel();
            channels.put(changes.channelId, channel);
        }
        // Renumbering never changes key order, so entries are updated in place.
        for (RenumberMove move : changes.renumbered) {
            PostOrderKey key = channel.keyByPostId.get(move.postId);
            if (key != null) {
                int index = channel.indexOf(key);
                if (index >= 0) {
                    channel.entries.set(index, channel.entries.get(index).withLocalId(move.to));
                }
            }
        }
        if (!changes.inserted.isEmpty()) {
            List<MessageIdEntry> inserted = new ArrayList<MessageIdEntry>();
            Set<String> seen = new HashSet<String>();
            for (MessageIdEntry entry : changes.inserted) {
                if (!channel.keyByPostId.containsKey(entry.postId()) && seen.add(entry.postId())) {
                    inserted.add(entry);
                }
            }
            Collections.sort(inserted, MessageIdAllocator.ENTRY_ORDER);
            List<MessageIdEntry> merged = new ArrayList<MessageIdEntry>(channel.entries.size() + inserted.size());
            int i = 0;
            int j = 0;
            while (i < channel.entries.size() || j < inserted.size()) {
                if (j >= inserted.size() || (i < channel.entries.size() && channel.entries.get(i).key.compareTo(inserted.get(j).key) < 0)) {
                    merged.add(channel.entries.get(i++));
                } else {
                    merged.add(inserted.get(j++));
                }
            }
            channel.entries = merged;
            for (MessageIdEntry entry : inserted) {
                channel.keyByPostId.put(entry.postId(), entry.key);
            }
        }
        for (String postId : changes.deletedPostIds) {
            PostOrderKey key = channel.keyByPostId.get(postId);
            if (key != null) {
                int index = channel.indexOf(key);
                if (index >= 0) {
                    channel.entries.set(index, channel.entries.get(index).deleted());
                }
            }
        }
        for (String postId : changes.unmappedTombstones) {
            if (!channel.keyByPostId.containsKey(postId)) {
                channel.tombstones.add(postId);
            }
        }
        if (changes.islands != null) {
            channel.islands = copyIslands(changes.islands);
        }
    }

    // MARK: - Inspection (tests, debugging)

    /** All entries of a channel in key order. */
    public synchronized List<MessageIdEntry> allEntries(String channelId) {
        Channel channel = channels.get(channelId);
        return channel == null ? new ArrayList<MessageIdEntry>() : new ArrayList<MessageIdEntry>(channel.entries);
    }

    /** Forgets a channel (full resync). */
    public synchronized void removeChannel(String channelId) {
        channels.remove(channelId);
    }

    /** Checks the store invariants; returns a description of every violation. */
    public synchronized List<String> validate(String channelId, int maxId) {
        Channel channel = channels.get(channelId);
        List<String> problems = new ArrayList<String>();
        if (channel == null) {
            return problems;
        }
        for (MessageIdEntry entry : channel.entries) {
            if (!entry.key.equals(channel.keyByPostId.get(entry.postId()))) {
                problems.add("post id index out of sync: " + entry);
            }
        }
        if (channel.keyByPostId.size() != channel.entries.size()) {
            problems.add("post id index has " + channel.keyByPostId.size() + " rows for " + channel.entries.size() + " entries");
        }
        List<String> invariant = invariantProblems(channel.entries, channel.islands, maxId);
        invariant.addAll(problems);
        return invariant;
    }

    /** The id map invariants of one channel, given its entries in key order and its islands. */
    public static List<String> invariantProblems(List<MessageIdEntry> entries, List<HistoryIsland> islands, int maxId) {
        Channel channel = new Channel();
        channel.entries = entries;
        channel.islands = islands;
        List<String> problems = new ArrayList<String>();
        MessageIdEntry previous = null;
        Set<Integer> seenIds = new HashSet<Integer>();
        for (MessageIdEntry entry : entries) {
            if (entry.localId < 1 || entry.localId > maxId) {
                problems.add("id out of range: " + entry);
            }
            if (!seenIds.add(entry.localId)) {
                problems.add("duplicate id: " + entry);
            }
            if (previous != null) {
                if (!previous.key.lessThan(entry.key)) {
                    problems.add("keys not strictly increasing: " + previous + " then " + entry);
                }
                if (!(previous.localId < entry.localId)) {
                    problems.add("ids not strictly increasing: " + previous + " then " + entry);
                }
            }
            previous = entry;
        }
        HistoryIsland previousIsland = null;
        List<int[]> boundaryIds = new ArrayList<int[]>();
        for (HistoryIsland island : islands) {
            if (island.upper.lessThan(island.lower)) {
                problems.add("inverted island " + island);
            }
            if (previousIsland != null && !previousIsland.upper.lessThan(island.lower)) {
                problems.add("overlapping or unsorted islands " + previousIsland + " " + island);
            }
            int lowerIndex = channel.indexOf(island.lower);
            int upperIndex = channel.indexOf(island.upper);
            if (lowerIndex < 0 || upperIndex < 0) {
                problems.add("island boundary is not an entry: " + island);
            } else {
                boundaryIds.add(new int[]{entries.get(lowerIndex).localId, entries.get(upperIndex).localId});
            }
            previousIsland = island;
        }
        for (int i = 1; i < boundaryIds.size(); i++) {
            if ((long) boundaryIds.get(i - 1)[1] + 1 > (long) boundaryIds.get(i)[0] - 1) {
                problems.add("no free id between islands " + (i - 1) + " and " + i);
            }
        }
        int islandIndex = 0;
        for (MessageIdEntry entry : entries) {
            while (islandIndex < islands.size() && islands.get(islandIndex).upper.lessThan(entry.key)) {
                islandIndex++;
            }
            if (islandIndex >= islands.size() || !islands.get(islandIndex).contains(entry.key)) {
                problems.add("entry outside every island: " + entry);
            }
        }
        for (int index = 0; index < islands.size(); index++) {
            HistoryIsland island = islands.get(index);
            if (island.reachesStart && index != 0) {
                problems.add("reachesStart on a non-lowest island " + island);
            }
            if (island.reachesLatest && index != islands.size() - 1) {
                problems.add("reachesLatest on a non-highest island " + island);
            }
        }
        return problems;
    }
}
