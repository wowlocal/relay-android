package org.telegram.relay.mattermost.ids;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Allocates Telegram-style 32-bit per-chat message ids for Mattermost posts. A port of the iOS
 * {@code MessageIdAllocator} ("option 2: sparse allocation with a stored map", iOS
 * {@code docs/plan/02-domain-mapping.md}):
 *
 * <ul>
 *   <li>the first post seen in a channel gets {@code firstId} (2^30);</li>
 *   <li>a page extending history from an anchor gets ids spaced by {@code step} (16), moving away from it;</li>
 *   <li>a late post between two neighbours of the same island gets their midpoint;</li>
 *   <li>a run that lands in unloaded history (permalink jump) becomes a new island placed in the gap
 *       proportionally to time, leaving holes on both sides;</li>
 *   <li>a page that reaches another island closes the hole between them;</li>
 *   <li>when there is no room left, a minimal window around the insertion point is renumbered and the
 *       change is returned as an explicit {@link RenumberPlan} (the renumber signal).</li>
 * </ul>
 *
 * <p>Invariants (checked by the property tests): ids are unique, within {@code 1...maxId}, strictly
 * increasing in {@code (createAt, postId)} order; neighbouring islands are separated by at least one
 * free id, so every hole is a non-empty id range.
 *
 * <p>Stateless: all state lives in the {@link MessageIdStore}. Each operation reads through the
 * store's queries and writes exactly one {@link MessageIdChangeSet}.
 */
public final class MessageIdAllocator {
    public static final class Configuration {
        /** Spacing between consecutive posts of a page ({@code STEP}). */
        public final int step;
        /** Id of the first post seen in an empty channel. */
        public final int firstId;
        /** Largest usable id (ids are allocated in {@code 1...maxId}). */
        public final int maxId;
        /** A renumber window grows until posts inside islands are at least this far apart (or the whole channel is renumbered). */
        public final int minimumSpacingAfterRenumber;
        /** Assumed earliest post time (ms) when placing an island below every known post. */
        public final long historyFloorTime;
        /** Minimum weight (ms) of the open space above the newest island, which absorbs all future posts. */
        public final long futureSpan;
        /** Largest island part shifted as a whole when a hole runs out; beyond that a smaller window is renumbered. */
        public final int maximumIslandShift;

        public Configuration() {
            this(16, 1 << 30, Integer.MAX_VALUE, 4, 1_262_304_000_000L, 30L * 24 * 60 * 60 * 1000, 10_000);
        }

        public Configuration(int step, int firstId, int maxId, int minimumSpacingAfterRenumber, long historyFloorTime, long futureSpan, int maximumIslandShift) {
            if (step < 1 || maxId < 16 || firstId < 1 || firstId > maxId) {
                throw new IllegalArgumentException("invalid allocator configuration");
            }
            this.step = step;
            this.firstId = firstId;
            this.maxId = maxId;
            this.minimumSpacingAfterRenumber = Math.max(1, minimumSpacingAfterRenumber);
            this.historyFloorTime = historyFloorTime;
            this.futureSpan = Math.max(1, futureSpan);
            this.maximumIslandShift = Math.max(1, maximumIslandShift);
        }

        /** The defaults with another first id and id space. */
        public static Configuration withIdSpace(int step, int firstId, int maxId) {
            return new Configuration(step, firstId, maxId, 4, 1_262_304_000_000L, 30L * 24 * 60 * 60 * 1000, 10_000);
        }

        public static final Configuration DEFAULT = new Configuration();
    }

    static final Comparator<MessageIdEntry> ENTRY_ORDER = new Comparator<MessageIdEntry>() {
        @Override
        public int compare(MessageIdEntry a, MessageIdEntry b) {
            return a.key.compareTo(b.key);
        }
    };

    private static final Comparator<HistoryIsland> ISLAND_ORDER = new Comparator<HistoryIsland>() {
        @Override
        public int compare(HistoryIsland a, HistoryIsland b) {
            return a.lower.compareTo(b.lower);
        }
    };

    public final Configuration configuration;

    public MessageIdAllocator() {
        this(Configuration.DEFAULT);
    }

    public MessageIdAllocator(Configuration configuration) {
        this.configuration = configuration;
    }

    // MARK: - Operations

    /**
     * The newest page of a channel ({@code page=0}), e.g. when a chat opens or after catch-up. The
     * resulting island is marked as reaching the latest post.
     *
     * @param reachedStart the server returned fewer posts than requested (the page includes the oldest post).
     */
    public IngestResult ingestLatestPage(List<PostOrderKey> posts, boolean reachedStart, String channelId, MessageIdStore store) throws MessageIdAllocationException {
        return ingestRun(posts, Collections.<String>emptyList(), reachedStart, true, channelId, store);
    }

    /**
     * A page loaded with {@code before=anchor} ({@link HistoryDirection#OLDER}) or {@code after=anchor}
     * ({@link HistoryDirection#NEWER}). New posts get ids spaced by {@code step} moving away from the
     * anchor; reaching another island closes the hole between them.
     *
     * @param reachedEnd fewer posts than requested: nothing more in {@code direction}.
     */
    public IngestResult ingestPage(List<PostOrderKey> posts, String anchorPostId, HistoryDirection direction, boolean reachedEnd, String channelId, MessageIdStore store) throws MessageIdAllocationException {
        return ingestRun(posts, Collections.singletonList(anchorPostId),
                direction == HistoryDirection.OLDER && reachedEnd,
                direction == HistoryDirection.NEWER && reachedEnd,
                channelId, store);
    }

    /**
     * A permalink jump: the target plus the pages before and after it. If none of them is mapped yet,
     * they become a new island placed proportionally to time between the nearest known posts.
     */
    public IngestResult ingestAround(PostOrderKey target, List<PostOrderKey> older, List<PostOrderKey> newer, boolean olderReachedStart, boolean newerReachedLatest, String channelId, MessageIdStore store) throws MessageIdAllocationException {
        List<PostOrderKey> all = new ArrayList<PostOrderKey>(older.size() + 1 + newer.size());
        all.addAll(older);
        all.add(target);
        all.addAll(newer);
        return ingestRun(all, Collections.<String>emptyList(), olderReachedStart, newerReachedLatest, channelId, store);
    }

    /** {@link #insertPost(PostOrderKey, int, String, MessageIdStore)} for a post without parts. */
    public IngestResult insertPost(PostOrderKey post, String channelId, MessageIdStore store) throws MessageIdAllocationException {
        return insertPost(post, 1, channelId, store);
    }

    /**
     * A single post that wasn't loaded through paging: a WebSocket {@code posted} event, the response
     * of our own {@code POST /posts}, or {@code GET /posts/{id}}.
     *
     * <ul>
     *   <li>Between two posts of the same island: joins it at their midpoint (late insert).</li>
     *   <li>Newer than everything while the newest island is live: appended to it.</li>
     *   <li>Older than everything while the oldest island reaches the start: prepended to it.</li>
     *   <li>Otherwise: a single-post island inside the hole.</li>
     * </ul>
     *
     * <p>{@code partCount > 1} maps the post's parts too ({@link PostParts}). For a mapped post, missing
     * parts are added right after its last mapped part (an edit that added files); fewer parts are not
     * removed here ({@link #removeParts}). Idempotent: a mapped post returns {@code EXISTING}, a
     * tombstoned one {@code DELETED}.
     */
    public IngestResult insertPost(PostOrderKey post, int partCount, String channelId, MessageIdStore store) throws MessageIdAllocationException {
        List<PostOrderKey> keys = Arrays.asList(post.parts(partCount));
        Map<String, MessageIdEntry> known = store.entries(channelId, postIds(keys));
        MessageIdEntry postEntry = known.get(post.postId);
        boolean postDeleted = postEntry != null ? postEntry.isDeleted : !store.unmappedTombstones(channelId, Collections.singletonList(post.postId)).isEmpty();
        List<PostOrderKey> missing = new ArrayList<PostOrderKey>();
        if (!postDeleted) {
            for (PostOrderKey key : keys) {
                if (!known.containsKey(key.postId)) {
                    missing.add(key);
                }
            }
        }
        if (missing.isEmpty()) {
            List<PostAllocation> allocations = new ArrayList<PostAllocation>();
            for (PostOrderKey key : keys) {
                MessageIdEntry entry = known.get(key.postId);
                boolean deleted = postDeleted || entry == null || entry.isDeleted;
                allocations.add(new PostAllocation(key.postId, entry != null ? entry.localId : null, deleted ? PostAllocation.Status.DELETED : PostAllocation.Status.EXISTING));
            }
            return new IngestResult(allocations, null, null, 0, holes(channelId, store));
        }
        MessageIdEntry lower = first(store.entriesOlderThan(channelId, missing.get(0), 1));
        MessageIdEntry upper = first(store.entriesNewerThan(channelId, missing.get(missing.size() - 1), 1));
        List<HistoryIsland> islands = store.islands(channelId);
        IslandIndex index = new IslandIndex(islands);
        List<String> anchors = new ArrayList<String>();
        if (lower != null && lower.serverPostId().equals(post.postId)) {
            // New parts of a mapped post: nothing else lies between the post and its parts.
            anchors.add(lower.postId());
            if (upper != null) {
                int lowerIsland = index.indexOf(lower.key);
                if (lowerIsland >= 0 && lowerIsland == index.indexOf(upper.key)) {
                    anchors.add(upper.postId());
                }
            }
        } else if (lower != null && upper != null) {
            int lowerIsland = index.indexOf(lower.key);
            if (lowerIsland >= 0 && lowerIsland == index.indexOf(upper.key)) {
                anchors.add(lower.postId());
                anchors.add(upper.postId());
            }
        } else if (lower != null) {
            HistoryIsland top = islands.isEmpty() ? null : islands.get(islands.size() - 1);
            if (top != null && top.reachesLatest && top.contains(lower.key)) {
                anchors.add(lower.postId());
            }
        } else if (upper != null) {
            HistoryIsland bottom = islands.isEmpty() ? null : islands.get(0);
            if (bottom != null && bottom.reachesStart && bottom.contains(upper.key)) {
                anchors.add(upper.postId());
            }
        }
        IngestResult result = ingestRun(missing, anchors, false, false, channelId, store);
        if (keys.size() > missing.size()) {
            // Report every part, with the current ids of the parts that were mapped already.
            Set<String> inserted = new HashSet<String>();
            for (PostAllocation allocation : result.allocations) {
                if (allocation.status == PostAllocation.Status.INSERTED) {
                    inserted.add(allocation.postId);
                }
            }
            Map<String, MessageIdEntry> current = store.entries(channelId, postIds(keys));
            List<PostAllocation> allocations = new ArrayList<PostAllocation>();
            for (PostOrderKey key : keys) {
                MessageIdEntry entry = current.get(key.postId);
                Integer id = entry != null ? entry.localId : null;
                if (inserted.contains(key.postId)) {
                    allocations.add(new PostAllocation(key.postId, id, PostAllocation.Status.INSERTED));
                } else {
                    allocations.add(new PostAllocation(key.postId, id, entry == null || entry.isDeleted ? PostAllocation.Status.DELETED : PostAllocation.Status.EXISTING));
                }
            }
            result.allocations = allocations;
        }
        return result;
    }

    /** The mapped entries of a post and its parts, in key order; empty when the post is unknown. */
    public List<MessageIdEntry> partEntries(String postId, String channelId, MessageIdStore store) {
        MessageIdEntry entry = store.entries(channelId, Collections.singletonList(postId)).get(postId);
        if (entry == null) {
            return new ArrayList<MessageIdEntry>();
        }
        return store.entriesInRange(channelId, entry.key.partsRangeLower(), entry.key.partsRangeUpper());
    }

    /** Tombstones the parts of a post from index {@code keeping} on (an edit removed files). Returns the newly tombstoned entries. */
    public List<MessageIdEntry> removeParts(String postId, int keeping, String channelId, MessageIdStore store) {
        List<String> extra = new ArrayList<String>();
        for (MessageIdEntry entry : partEntries(postId, channelId, store)) {
            if (entry.partIndex() >= Math.max(1, keeping) && !entry.isDeleted) {
                extra.add(entry.postId());
            }
        }
        if (extra.isEmpty()) {
            return new ArrayList<MessageIdEntry>();
        }
        return markDeleted(extra, channelId, store);
    }

    /**
     * The general operation behind all the others: {@code posts} (any order, duplicates ignored)
     * together with the mapped {@code anchors} form one contiguous stretch of the channel's history,
     * i.e. the server has no other posts between the oldest and the newest of them.
     */
    public IngestResult ingestRun(List<PostOrderKey> posts, List<String> anchors, boolean reachesStart, boolean reachesLatest, String channelId, MessageIdStore store) throws MessageIdAllocationException {
        Set<String> seen = new HashSet<String>();
        List<PostOrderKey> unique = new ArrayList<PostOrderKey>(posts.size());
        for (PostOrderKey post : posts) {
            if (seen.add(post.postId)) {
                unique.add(post);
            }
        }

        List<String> lookup = postIds(unique);
        lookup.addAll(anchors);
        Map<String, MessageIdEntry> known = store.entries(channelId, lookup);
        for (String anchor : anchors) {
            if (!known.containsKey(anchor)) {
                throw new MessageIdAllocationException(MessageIdAllocationException.Kind.UNKNOWN_ANCHOR, anchor);
            }
        }
        List<String> unknownIds = new ArrayList<String>();
        for (PostOrderKey post : unique) {
            if (!known.containsKey(post.postId)) {
                unknownIds.add(post.postId);
            }
        }
        Set<String> tombstoned = unknownIds.isEmpty() ? new HashSet<String>() : new HashSet<String>(store.unmappedTombstones(channelId, unknownIds));
        // Parts of a deleted post are deleted too, even when they were never mapped.
        Set<String> partOwners = new LinkedHashSet<String>();
        for (String id : unknownIds) {
            if (PostParts.isPartId(id)) {
                partOwners.add(PostParts.postIdOfPartId(id));
            }
        }
        if (!partOwners.isEmpty()) {
            List<String> owners = new ArrayList<String>(partOwners);
            Map<String, MessageIdEntry> ownerEntries = store.entries(channelId, owners);
            Set<String> deletedOwners = new HashSet<String>(store.unmappedTombstones(channelId, owners));
            for (MessageIdEntry entry : ownerEntries.values()) {
                if (entry.isDeleted) {
                    deletedOwners.add(entry.postId());
                }
            }
            for (String id : unknownIds) {
                if (PostParts.isPartId(id) && deletedOwners.contains(PostParts.postIdOfPartId(id))) {
                    tombstoned.add(id);
                }
            }
        }
        List<PostOrderKey> newPosts = new ArrayList<PostOrderKey>();
        for (PostOrderKey post : unique) {
            if (!known.containsKey(post.postId) && !tombstoned.contains(post.postId)) {
                newPosts.add(post);
            }
        }
        Collections.sort(newPosts);

        // The key span covered by this run; islands overlapping it merge into one.
        PostOrderKey spanLow = null;
        PostOrderKey spanHigh = null;
        for (PostOrderKey key : newPosts) {
            spanLow = min(spanLow, key);
            spanHigh = max(spanHigh, key);
        }
        for (MessageIdEntry entry : known.values()) {
            spanLow = min(spanLow, entry.key);
            spanHigh = max(spanHigh, entry.key);
        }
        // Nothing older (newer) exists on the server, so every mapped post below (above) the run
        // belongs to it. Only that end of the span moves.
        if (reachesStart) {
            MessageIdEntry lowest = first(store.entriesNewerThan(channelId, null, 1));
            if (lowest != null) {
                spanLow = min(spanLow != null ? spanLow : lowest.key, lowest.key);
                if (spanHigh == null) {
                    spanHigh = lowest.key;
                }
            }
        }
        if (reachesLatest) {
            MessageIdEntry highest = first(store.entriesOlderThan(channelId, null, 1));
            if (highest != null) {
                spanHigh = max(spanHigh != null ? spanHigh : highest.key, highest.key);
                if (spanLow == null) {
                    spanLow = highest.key;
                }
            }
        }

        if (spanLow == null || spanHigh == null) {
            // Nothing to record (empty page of an empty channel, or only tombstoned posts).
            List<PostOrderKey> sorted = new ArrayList<PostOrderKey>(unique);
            Collections.sort(sorted);
            List<PostAllocation> allocations = new ArrayList<PostAllocation>();
            for (PostOrderKey key : sorted) {
                allocations.add(new PostAllocation(key.postId, null, PostAllocation.Status.DELETED));
            }
            return new IngestResult(allocations, null, null, 0, holes(channelId, store));
        }
        final PostOrderKey low = spanLow;
        final PostOrderKey high = spanHigh;

        // Merge overlapping islands.
        List<HistoryIsland> oldIslands = store.islands(channelId);
        HistoryIsland merged = new HistoryIsland(low, high, reachesStart, reachesLatest);
        List<HistoryIsland> newIslands = new ArrayList<HistoryIsland>();
        int joinedIslandCount = 0;
        for (HistoryIsland island : oldIslands) {
            if (island.upper.lessThan(low) || high.lessThan(island.lower)) {
                newIslands.add(island);
            } else {
                joinedIslandCount++;
                if (island.lower.lessThan(merged.lower)) {
                    merged.lower = island.lower;
                }
                if (merged.upper.lessThan(island.upper)) {
                    merged.upper = island.upper;
                }
                merged.reachesStart = merged.reachesStart || island.reachesStart;
                merged.reachesLatest = merged.reachesLatest || island.reachesLatest;
            }
        }
        newIslands.add(merged);
        Collections.sort(newIslands, ISLAND_ORDER);
        for (int i = 0; i < newIslands.size(); i++) {
            if (i != 0) {
                newIslands.get(i).reachesStart = false;
            }
            if (i != newIslands.size() - 1) {
                newIslands.get(i).reachesLatest = false;
            }
        }

        // Place the new posts.
        List<MessageIdEntry> inserted = new ArrayList<MessageIdEntry>();
        RenumberPlan renumberPlan = null;
        if (!newPosts.isEmpty()) {
            Placement placement = place(newPosts, new IslandIndex(newIslands), channelId, store);
            inserted = placement.inserted;
            renumberPlan = placement.renumberPlan;
        }

        store.apply(new MessageIdChangeSet(channelId,
                renumberPlan != null ? new ArrayList<RenumberMove>(renumberPlan.moves) : null,
                inserted, null, null, newIslands));

        // Report.
        Map<String, Integer> currentIds = new HashMap<String, Integer>();
        for (MessageIdEntry entry : known.values()) {
            currentIds.put(entry.postId(), entry.localId);
        }
        if (renumberPlan != null) {
            for (RenumberMove move : renumberPlan.moves) {
                currentIds.put(move.postId, move.to);
            }
        }
        Map<String, Integer> insertedIds = new HashMap<String, Integer>();
        for (MessageIdEntry entry : inserted) {
            insertedIds.put(entry.postId(), entry.localId);
        }
        final List<Object[]> keyed = new ArrayList<Object[]>(unique.size());
        for (PostOrderKey post : unique) {
            Integer id = insertedIds.get(post.postId);
            MessageIdEntry entry = known.get(post.postId);
            if (id != null) {
                keyed.add(new Object[]{post, new PostAllocation(post.postId, id, PostAllocation.Status.INSERTED)});
            } else if (entry != null) {
                keyed.add(new Object[]{entry.key, new PostAllocation(post.postId, currentIds.get(post.postId), entry.isDeleted ? PostAllocation.Status.DELETED : PostAllocation.Status.EXISTING)});
            } else {
                keyed.add(new Object[]{post, new PostAllocation(post.postId, null, PostAllocation.Status.DELETED)});
            }
        }
        Collections.sort(keyed, new Comparator<Object[]>() {
            @Override
            public int compare(Object[] a, Object[] b) {
                return ((PostOrderKey) a[0]).compareTo((PostOrderKey) b[0]);
            }
        });
        List<PostAllocation> allocations = new ArrayList<PostAllocation>(keyed.size());
        for (Object[] pair : keyed) {
            allocations.add((PostAllocation) pair[1]);
        }

        Map<String, MessageIdEntry> boundaries = store.entries(channelId, Arrays.asList(merged.lower.postId, merged.upper.postId));
        IdRange islandRange = null;
        MessageIdEntry lowerEntry = boundaries.get(merged.lower.postId);
        MessageIdEntry upperEntry = boundaries.get(merged.upper.postId);
        if (lowerEntry != null && upperEntry != null) {
            islandRange = new IdRange(lowerEntry.localId, upperEntry.localId);
        }
        return new IngestResult(allocations, renumberPlan, islandRange, joinedIslandCount, holes(channelId, store));
    }

    /**
     * Tombstones posts (WebSocket {@code post_deleted}, or deletions found during catch-up), with their
     * parts. Mapped posts keep their id and are flagged; unmapped ones are remembered so they're never
     * inserted later. Returns the newly tombstoned mapped entries (parts included), in key order.
     */
    public List<MessageIdEntry> markDeleted(List<String> postIdsToDelete, String channelId, MessageIdStore store) {
        List<String> postIds = new ArrayList<String>(postIdsToDelete);
        Map<String, MessageIdEntry> known = new HashMap<String, MessageIdEntry>(store.entries(channelId, postIds));
        // A deleted post takes its parts with it.
        for (String postId : new ArrayList<String>(postIds)) {
            if (PostParts.isPartId(postId)) {
                continue;
            }
            MessageIdEntry entry = known.get(postId);
            if (entry == null) {
                continue;
            }
            for (MessageIdEntry part : store.entriesInRange(channelId, entry.key.partsRangeLower(), entry.key.partsRangeUpper())) {
                if (!part.postId().equals(postId) && !known.containsKey(part.postId())) {
                    known.put(part.postId(), part);
                    postIds.add(part.postId());
                }
            }
        }
        List<MessageIdEntry> newlyDeleted = new ArrayList<MessageIdEntry>();
        List<String> unmapped = new ArrayList<String>();
        Set<String> seen = new HashSet<String>();
        for (String postId : postIds) {
            if (!seen.add(postId)) {
                continue;
            }
            MessageIdEntry entry = known.get(postId);
            if (entry != null) {
                if (!entry.isDeleted) {
                    newlyDeleted.add(entry.deleted());
                }
            } else {
                unmapped.add(postId);
            }
        }
        List<String> deletedIds = new ArrayList<String>();
        for (MessageIdEntry entry : newlyDeleted) {
            deletedIds.add(entry.postId());
        }
        store.apply(new MessageIdChangeSet(channelId, null, null, deletedIds, unmapped, null));
        Collections.sort(newlyDeleted, ENTRY_ORDER);
        return newlyDeleted;
    }

    /**
     * Marks the newest island as no longer live (after a non-resumed WebSocket reconnect, or a catch-up
     * too large to apply). A hole opens above it.
     */
    public void invalidateLatest(String channelId, MessageIdStore store) {
        List<HistoryIsland> islands = store.islands(channelId);
        if (islands.isEmpty() || !islands.get(islands.size() - 1).reachesLatest) {
            return;
        }
        islands.get(islands.size() - 1).reachesLatest = false;
        store.apply(new MessageIdChangeSet(channelId, null, null, null, null, islands));
    }

    // MARK: - Queries

    /** The channel's islands with resolved id ranges, ascending. */
    public List<HistoryIslandSummary> islandSummaries(String channelId, MessageIdStore store) {
        List<HistoryIsland> islands = store.islands(channelId);
        List<HistoryIslandSummary> result = new ArrayList<HistoryIslandSummary>();
        if (islands.isEmpty()) {
            return result;
        }
        List<String> ids = new ArrayList<String>();
        for (HistoryIsland island : islands) {
            ids.add(island.lower.postId);
            ids.add(island.upper.postId);
        }
        Map<String, MessageIdEntry> boundaries = store.entries(channelId, ids);
        for (HistoryIsland island : islands) {
            MessageIdEntry lower = boundaries.get(island.lower.postId);
            MessageIdEntry upper = boundaries.get(island.upper.postId);
            if (lower != null && upper != null) {
                result.add(new HistoryIslandSummary(island, new IdRange(lower.localId, upper.localId)));
            }
        }
        return result;
    }

    /**
     * The gaps between islands as id ranges, plus the open space below the oldest island (unless it
     * reaches the channel start) and above the newest (unless it is live). An empty channel is one hole
     * covering the whole id space.
     */
    public List<HistoryHole> holes(String channelId, MessageIdStore store) {
        List<HistoryIslandSummary> summaries = islandSummaries(channelId, store);
        int maxId = configuration.maxId;
        List<HistoryHole> result = new ArrayList<HistoryHole>();
        if (summaries.isEmpty()) {
            result.add(new HistoryHole(new IdRange(1, maxId), null, null));
            return result;
        }
        HistoryIslandSummary previous = null;
        for (HistoryIslandSummary summary : summaries) {
            int low = previous != null ? previous.range.upper + 1 : 1;
            if (previous != null || !summary.island.reachesStart) {
                if (summary.range.lower > low) {
                    result.add(new HistoryHole(new IdRange(low, summary.range.lower - 1), previous != null ? previous.island.upper : null, summary.island.lower));
                }
            }
            previous = summary;
        }
        if (previous != null && !previous.island.reachesLatest && previous.range.upper < maxId) {
            result.add(new HistoryHole(new IdRange(previous.range.upper + 1, maxId), previous.island.upper, null));
        }
        return result;
    }

    /**
     * Translates a hole fill request into a Mattermost cursor.
     *
     * <ul>
     *   <li>{@code OLDER}: {@code boundaryLocalId} is the upper edge of the region to fill. Returns
     *       {@code before=} the nearest mapped post at or above it, or {@code LATEST} when nothing is
     *       mapped above.</li>
     *   <li>{@code NEWER}: {@code boundaryLocalId} is the lower edge. Returns {@code after=} the nearest
     *       mapped post at or below it, or null when nothing is mapped below.</li>
     * </ul>
     * Tombstoned boundary posts are skipped in favour of a live post of the same island; a part gives
     * the cursor of its post. Ingest the response with {@link #ingestPage} using the cursor's post as
     * the anchor (or {@link #ingestLatestPage} for {@code LATEST}).
     */
    public HistoryCursor fillCursor(HistoryDirection direction, int boundaryLocalId, String channelId, MessageIdStore store) {
        List<MessageIdEntry> candidates = store.entriesFromLocalId(channelId, boundaryLocalId, direction == HistoryDirection.OLDER, 16);
        if (candidates.isEmpty()) {
            return direction == HistoryDirection.OLDER ? HistoryCursor.LATEST : null;
        }
        MessageIdEntry chosen = candidates.get(0);
        if (chosen.isDeleted) {
            IslandIndex index = new IslandIndex(store.islands(channelId));
            int island = index.indexOf(chosen.key);
            for (MessageIdEntry candidate : candidates) {
                if (!candidate.isDeleted && index.indexOf(candidate.key) == island) {
                    chosen = candidate;
                    break;
                }
            }
        }
        return direction == HistoryDirection.OLDER ? HistoryCursor.before(chosen.serverPostId()) : HistoryCursor.after(chosen.serverPostId());
    }

    /** The cursor for an "around id" request, when the id is mapped. */
    public HistoryCursor aroundCursor(int localId, String channelId, MessageIdStore store) {
        MessageIdEntry entry = first(store.entriesFromLocalId(channelId, localId, true, 1));
        if (entry == null || entry.localId != localId) {
            return null;
        }
        return HistoryCursor.around(entry.serverPostId());
    }

    // MARK: - Placement

    /** Either an existing entry (moves only in a renumber) or a new post. */
    private static final class Slot {
        final MessageIdEntry existing;
        final PostOrderKey newKey;

        Slot(MessageIdEntry existing, PostOrderKey newKey) {
            this.existing = existing;
            this.newKey = newKey;
        }

        PostOrderKey key() {
            return existing != null ? existing.key : newKey;
        }

        boolean isNew() {
            return existing == null;
        }
    }

    private static final class Group {
        final MessageIdEntry lower;
        final MessageIdEntry upper;
        final List<PostOrderKey> keys;

        Group(MessageIdEntry lower, MessageIdEntry upper, List<PostOrderKey> keys) {
            this.lower = lower;
            this.upper = upper;
            this.keys = keys;
        }
    }

    private static final class Placement {
        final List<MessageIdEntry> inserted;
        final RenumberPlan renumberPlan;

        Placement(List<MessageIdEntry> inserted, RenumberPlan renumberPlan) {
            this.inserted = inserted;
            this.renumberPlan = renumberPlan;
        }
    }

    private static final class WindowLayout {
        final Placement placement;
        final int moveCount;

        WindowLayout(Placement placement, int moveCount) {
            this.placement = placement;
            this.moveCount = moveCount;
        }
    }

    private static final class WindowCandidate {
        final List<Slot> items;
        final MessageIdEntry lower;
        final MessageIdEntry upper;

        WindowCandidate(List<Slot> items, MessageIdEntry lower, MessageIdEntry upper) {
            this.items = items;
            this.lower = lower;
            this.upper = upper;
        }
    }

    private long upperSentinel() {
        return (long) configuration.maxId + 1;
    }

    private Placement place(List<PostOrderKey> newPosts, IslandIndex islands, String channelId, MessageIdStore store) throws MessageIdAllocationException {
        PostOrderKey firstKey = newPosts.get(0);
        PostOrderKey lastKey = newPosts.get(newPosts.size() - 1);
        List<MessageIdEntry> inside = newPosts.size() > 1 ? store.entriesInRange(channelId, firstKey, lastKey) : new ArrayList<MessageIdEntry>();
        MessageIdEntry lowerNeighbour = first(store.entriesOlderThan(channelId, firstKey, 1));
        MessageIdEntry upperNeighbour = first(store.entriesNewerThan(channelId, lastKey, 1));

        // Empty channel: the newest post of the run gets firstId.
        if (lowerNeighbour == null && upperNeighbour == null && inside.isEmpty()) {
            long step = configuration.step;
            long count = newPosts.size();
            if ((count - 1) * step < configuration.firstId) {
                long top = configuration.firstId;
                List<MessageIdEntry> inserted = new ArrayList<MessageIdEntry>(newPosts.size());
                for (int index = 0; index < newPosts.size(); index++) {
                    inserted.add(new MessageIdEntry(newPosts.get(index), (int) (top - (count - 1 - index) * step)));
                }
                return new Placement(inserted, null);
            }
        }

        // Interleave the new posts with the mapped posts between them.
        List<Slot> slots = new ArrayList<Slot>(newPosts.size() + inside.size());
        int i = 0;
        int j = 0;
        while (i < inside.size() || j < newPosts.size()) {
            if (j >= newPosts.size() || (i < inside.size() && inside.get(i).key.lessThan(newPosts.get(j)))) {
                slots.add(new Slot(inside.get(i++), null));
            } else {
                slots.add(new Slot(null, newPosts.get(j++)));
            }
        }

        List<Group> groups = new ArrayList<Group>();
        MessageIdEntry currentLower = lowerNeighbour;
        List<PostOrderKey> currentKeys = new ArrayList<PostOrderKey>();
        for (Slot slot : slots) {
            if (!slot.isNew()) {
                if (!currentKeys.isEmpty()) {
                    groups.add(new Group(currentLower, slot.existing, currentKeys));
                    currentKeys = new ArrayList<PostOrderKey>();
                }
                currentLower = slot.existing;
            } else {
                currentKeys.add(slot.newKey);
            }
        }
        if (!currentKeys.isEmpty()) {
            groups.add(new Group(currentLower, upperNeighbour, currentKeys));
        }

        // Fast path: every group fits into its own gap and nothing existing moves. A page next to a hole
        // may be compressed down to minimumSpacingAfterRenumber; a late post between two posts of the
        // same island takes their midpoint while a free id is left.
        long minimumSpacing = configuration.minimumSpacingAfterRenumber;
        List<MessageIdEntry> inserted = new ArrayList<MessageIdEntry>(newPosts.size());
        Boolean exhaustedHole = null;
        for (Group group : groups) {
            List<PostOrderKey> keys = new ArrayList<PostOrderKey>(group.keys.size() + 2);
            keys.add(group.lower != null ? group.lower.key : null);
            keys.addAll(group.keys);
            keys.add(group.upper != null ? group.upper.key : null);
            double[] gaps = layoutGaps(keys, islands, null);
            boolean touchesHole = false;
            for (double gap : gaps) {
                if (!Double.isNaN(gap)) {
                    touchesHole = true;
                    break;
                }
            }
            long lowerBound = group.lower != null ? group.lower.localId : 0;
            long upperBound = group.upper != null ? group.upper.localId : upperSentinel();
            IdLayout.Outcome outcome = IdLayout.layout(lowerBound, upperBound, gaps, configuration.step);
            if (outcome == null || (touchesHole && outcome.minimumIslandSpacing < minimumSpacing)) {
                exhaustedHole = touchesHole;
                break;
            }
            for (int k = 0; k < group.keys.size(); k++) {
                inserted.add(new MessageIdEntry(group.keys.get(k), (int) outcome.positions[k]));
            }
        }
        if (exhaustedHole == null) {
            return new Placement(inserted, null);
        }

        // No room: renumber.
        MessageIdEntry lower = groups.get(0).lower;
        MessageIdEntry upper = groups.get(groups.size() - 1).upper;
        if (exhaustedHole) {
            // A hole ran out ("renumber the smaller island"): shift the smaller of the island parts next
            // to the insertion into the hole beyond it. This restores STEP spacing and gives the hole
            // being filled at least half of the freed space.
            WindowLayout best = null;
            for (WindowCandidate candidate : islandShiftCandidates(slots, lower, upper, islands, configuration.maximumIslandShift, channelId, store)) {
                WindowLayout layout = layoutWindow(candidate.items, candidate.lower, candidate.upper, islands);
                if (layout != null && (best == null || layout.moveCount < best.moveCount)) {
                    best = layout;
                }
            }
            if (best != null) {
                return best.placement;
            }
        }
        // A gap inside an island ran out (late inserts), or no island could be shifted: renumber the
        // smallest window around the insertion that has room.
        return growingWindowLayout(slots, lower, upper, islands, channelId, store).placement;
    }

    /**
     * Lays out {@code items} strictly between {@code lower} and {@code upper} (null: the edge of the id
     * space). Null unless posts inside islands end up at least {@code minimumSpacingAfterRenumber}
     * apart; for the whole channel any layout that fits is accepted.
     */
    private WindowLayout layoutWindow(List<Slot> items, MessageIdEntry lower, MessageIdEntry upper, IslandIndex islands) {
        List<PostOrderKey> keys = new ArrayList<PostOrderKey>(items.size() + 2);
        keys.add(lower != null ? lower.key : null);
        for (Slot item : items) {
            keys.add(item.key());
        }
        keys.add(upper != null ? upper.key : null);
        // Holes next to the new posts are the ones being filled: give them at least half of the free
        // space so repeated pages into a dense stretch don't renumber every time.
        boolean[] hot = new boolean[keys.size() - 1];
        for (int index = 0; index < items.size(); index++) {
            if (items.get(index).isNew()) {
                hot[index] = true;
                hot[index + 1] = true;
            }
        }
        double[] gaps = layoutGaps(keys, islands, hot);
        long lowerBound = lower != null ? lower.localId : 0;
        long upperBound = upper != null ? upper.localId : upperSentinel();
        IdLayout.Outcome outcome = IdLayout.layout(lowerBound, upperBound, gaps, configuration.step);
        if (outcome == null) {
            return null;
        }
        boolean isWholeChannel = lower == null && upper == null;
        if (!isWholeChannel && outcome.minimumIslandSpacing < configuration.minimumSpacingAfterRenumber) {
            return null;
        }
        List<MessageIdEntry> inserted = new ArrayList<MessageIdEntry>();
        List<RenumberMove> moves = new ArrayList<RenumberMove>();
        for (int index = 0; index < items.size(); index++) {
            Slot item = items.get(index);
            int id = (int) outcome.positions[index];
            if (item.isNew()) {
                inserted.add(new MessageIdEntry(item.newKey, id));
            } else if (item.existing.localId != id) {
                moves.add(new RenumberMove(item.existing.postId(), item.existing.localId, id));
            }
        }
        IdRange affected = new IdRange((int) (lowerBound + 1), (int) (upperBound - 1));
        RenumberPlan plan = moves.isEmpty() ? null : new RenumberPlan(moves, affected);
        return new WindowLayout(new Placement(inserted, plan), moves.size());
    }

    /**
     * Grows a window around the insertion until it has enough room. Each step extends the side that
     * yields more free ids per renumbered post, doubling the step on that side.
     */
    private WindowLayout growingWindowLayout(List<Slot> initialItems, MessageIdEntry lower, MessageIdEntry upper, IslandIndex islands, String channelId, MessageIdStore store) throws MessageIdAllocationException {
        List<Slot> items = new ArrayList<Slot>(initialItems);
        MessageIdEntry windowLower = lower;
        MessageIdEntry windowUpper = upper;
        int lowerStep = 1;
        int upperStep = 1;

        while (true) {
            WindowLayout layout = layoutWindow(items, windowLower, windowUpper, islands);
            if (layout != null) {
                return layout;
            }

            MessageIdEntry lowerBound = null;
            List<MessageIdEntry> lowerAdded = null;
            long lowerGain = 0;
            if (windowLower != null) {
                List<MessageIdEntry> fetched = store.entriesOlderThan(channelId, windowLower.key, lowerStep);
                lowerBound = fetched.size() == lowerStep ? fetched.get(fetched.size() - 1) : null;
                List<MessageIdEntry> added = new ArrayList<MessageIdEntry>();
                added.add(windowLower);
                added.addAll(lowerBound != null ? fetched.subList(0, fetched.size() - 1) : fetched);
                Collections.reverse(added);
                lowerAdded = added;
                lowerGain = (long) windowLower.localId - (lowerBound != null ? lowerBound.localId : 0);
            }
            MessageIdEntry upperBound = null;
            List<MessageIdEntry> upperAdded = null;
            long upperGain = 0;
            if (windowUpper != null) {
                List<MessageIdEntry> fetched = store.entriesNewerThan(channelId, windowUpper.key, upperStep);
                upperBound = fetched.size() == upperStep ? fetched.get(fetched.size() - 1) : null;
                List<MessageIdEntry> added = new ArrayList<MessageIdEntry>();
                added.add(windowUpper);
                added.addAll(upperBound != null ? fetched.subList(0, fetched.size() - 1) : fetched);
                upperAdded = added;
                upperGain = (upperBound != null ? (long) upperBound.localId : upperSentinel()) - windowUpper.localId;
            }

            boolean growLower;
            if (lowerAdded == null && upperAdded == null) {
                throw new MessageIdAllocationException(MessageIdAllocationException.Kind.CAPACITY_EXHAUSTED, channelId);
            } else if (upperAdded == null) {
                growLower = true;
            } else if (lowerAdded == null) {
                growLower = false;
            } else {
                double lowerRatio = (double) lowerGain / lowerAdded.size();
                double upperRatio = (double) upperGain / upperAdded.size();
                growLower = lowerRatio != upperRatio ? lowerRatio > upperRatio : lowerStep <= upperStep;
            }
            if (growLower) {
                List<Slot> grown = new ArrayList<Slot>(lowerAdded.size() + items.size());
                for (MessageIdEntry entry : lowerAdded) {
                    grown.add(new Slot(entry, null));
                }
                grown.addAll(items);
                items = grown;
                windowLower = lowerBound;
                lowerStep = lowerStep < (1 << 29) ? lowerStep * 2 : lowerStep;
            } else {
                for (MessageIdEntry entry : upperAdded) {
                    items.add(new Slot(entry, null));
                }
                windowUpper = upperBound;
                upperStep = upperStep < (1 << 29) ? upperStep * 2 : upperStep;
            }
        }
    }

    /**
     * Windows that move the rest of the island below {@code lower} down into the hole under it, or the
     * rest of the island above {@code upper} up into the hole over it. Only island parts with at most
     * {@code moveLimit} posts are considered, so store reads stay bounded.
     */
    private List<WindowCandidate> islandShiftCandidates(List<Slot> items, MessageIdEntry lower, MessageIdEntry upper, IslandIndex islands, int moveLimit, String channelId, MessageIdStore store) {
        List<WindowCandidate> candidates = new ArrayList<WindowCandidate>();
        if (lower != null) {
            int index = islands.indexOf(lower.key);
            if (index >= 0) {
                HistoryIsland island = islands.islands.get(index);
                List<MessageIdEntry> fetched = store.entriesOlderThan(channelId, lower.key, moveLimit);
                List<MessageIdEntry> part = new ArrayList<MessageIdEntry>();
                part.add(lower);
                MessageIdEntry bound = null;
                boolean complete = fetched.size() < moveLimit;
                for (MessageIdEntry entry : fetched) {
                    if (entry.key.lessThan(island.lower)) {
                        bound = entry;
                        complete = true;
                        break;
                    }
                    part.add(entry);
                }
                if (complete && part.size() <= moveLimit) {
                    List<Slot> window = new ArrayList<Slot>(part.size() + items.size());
                    for (int k = part.size() - 1; k >= 0; k--) {
                        window.add(new Slot(part.get(k), null));
                    }
                    window.addAll(items);
                    candidates.add(new WindowCandidate(window, bound, upper));
                }
            }
        }
        if (upper != null) {
            int index = islands.indexOf(upper.key);
            if (index >= 0) {
                HistoryIsland island = islands.islands.get(index);
                List<MessageIdEntry> fetched = store.entriesNewerThan(channelId, upper.key, moveLimit);
                List<MessageIdEntry> part = new ArrayList<MessageIdEntry>();
                part.add(upper);
                MessageIdEntry bound = null;
                boolean complete = fetched.size() < moveLimit;
                for (MessageIdEntry entry : fetched) {
                    if (island.upper.lessThan(entry.key)) {
                        bound = entry;
                        complete = true;
                        break;
                    }
                    part.add(entry);
                }
                if (complete && part.size() <= moveLimit) {
                    List<Slot> window = new ArrayList<Slot>(items.size() + part.size());
                    window.addAll(items);
                    for (MessageIdEntry entry : part) {
                        window.add(new Slot(entry, null));
                    }
                    candidates.add(new WindowCandidate(window, lower, bound));
                }
            }
        }
        return candidates;
    }

    /**
     * Classifies each gap of {@code keys} (null = the edge of the id space) as inside an island
     * ({@code NaN}) or a hole weighted by the time it spans.
     */
    private double[] layoutGaps(List<PostOrderKey> keys, IslandIndex islands, boolean[] hot) {
        int gapCount = keys.size() - 1;
        Double[] raw = new Double[gapCount];
        int topSentinelIndex = -1;
        double otherHoleWeight = 0;
        int previousIsland = keys.get(0) != null ? islands.indexOf(keys.get(0)) : -1;
        for (int index = 1; index < keys.size(); index++) {
            PostOrderKey lower = keys.get(index - 1);
            PostOrderKey upper = keys.get(index);
            int upperIsland = upper != null ? islands.indexOf(upper) : -1;
            if (lower != null && upper != null && previousIsland >= 0 && previousIsland == upperIsland) {
                raw[index - 1] = null;
            } else if (lower != null && upper != null) {
                double weight = Math.max(0, upper.createAt - lower.createAt);
                raw[index - 1] = weight;
                otherHoleWeight += weight;
            } else if (upper != null) {
                double weight = Math.max(0, upper.createAt - configuration.historyFloorTime);
                raw[index - 1] = weight;
                otherHoleWeight += weight;
            } else {
                topSentinelIndex = index - 1;
                raw[index - 1] = 0.0;
            }
            previousIsland = upperIsland;
        }
        if (topSentinelIndex >= 0) {
            raw[topSentinelIndex] = Math.max(otherHoleWeight, (double) configuration.futureSpan);
        }
        int holeCount = 0;
        double total = 0;
        for (Double weight : raw) {
            if (weight != null) {
                holeCount++;
                total += weight;
            }
        }
        // Every hole gets a floor share so a time-adjacent hole still has room to grow.
        double floor = Math.max(1, total / (8 * Math.max(1, holeCount)));
        double[] weights = new double[gapCount];
        for (int index = 0; index < gapCount; index++) {
            weights[index] = raw[index] != null ? raw[index] + floor : Double.NaN;
        }
        if (hot != null) {
            int hotCount = 0;
            double coldWeight = 0;
            for (int index = 0; index < gapCount; index++) {
                if (!Double.isNaN(weights[index])) {
                    if (hot[index]) {
                        hotCount++;
                    } else {
                        coldWeight += weights[index];
                    }
                }
            }
            if (hotCount > 0 && coldWeight > 0) {
                for (int index = 0; index < gapCount; index++) {
                    if (hot[index] && !Double.isNaN(weights[index])) {
                        weights[index] = Math.max(weights[index], coldWeight / hotCount);
                    }
                }
            }
        }
        return weights;
    }

    // MARK: - Helpers

    private static MessageIdEntry first(List<MessageIdEntry> entries) {
        return entries.isEmpty() ? null : entries.get(0);
    }

    private static PostOrderKey min(PostOrderKey a, PostOrderKey b) {
        if (a == null) {
            return b;
        }
        return b.lessThan(a) ? b : a;
    }

    private static PostOrderKey max(PostOrderKey a, PostOrderKey b) {
        if (a == null) {
            return b;
        }
        return a.lessThan(b) ? b : a;
    }

    private static List<String> postIds(List<PostOrderKey> keys) {
        List<String> ids = new ArrayList<String>(keys.size());
        for (PostOrderKey key : keys) {
            ids.add(key.postId);
        }
        return ids;
    }

    /** Binary search over a sorted island list. */
    static final class IslandIndex {
        final List<HistoryIsland> islands;

        IslandIndex(List<HistoryIsland> islands) {
            this.islands = islands;
        }

        /** The index of the island containing {@code key}, or -1. */
        int indexOf(PostOrderKey key) {
            int low = 0;
            int high = islands.size();
            while (low < high) {
                int mid = (low + high) >>> 1;
                if (islands.get(mid).upper.lessThan(key)) {
                    low = mid + 1;
                } else {
                    high = mid;
                }
            }
            if (low < islands.size() && islands.get(low).contains(key)) {
                return low;
            }
            return -1;
        }
    }

    /** Distributes positions inside an exclusive id interval. Gaps: {@code NaN} = island gap, else a hole weight. */
    static final class IdLayout {
        static final long MINIMUM_HOLE_DISTANCE = 2;

        static final class Outcome {
            final long[] positions;
            final long minimumIslandSpacing;

            Outcome(long[] positions, long minimumIslandSpacing) {
                this.positions = positions;
                this.minimumIslandSpacing = minimumIslandSpacing;
            }
        }

        private IdLayout() {
        }

        /**
         * Places {@code gaps.length - 1} positions strictly between {@code lower} and {@code upper}.
         * Only island gaps: spread evenly (a single post lands on the midpoint). With holes: island gaps
         * get {@code step}, reduced so the holes keep at least half the space; holes split the rest by
         * weight, each keeping at least one free id.
         */
        static Outcome layout(long lower, long upper, double[] gaps, long step) {
            long capacity = upper - lower;
            int gapCount = gaps.length;
            if (gapCount < 1 || capacity < gapCount) {
                return null;
            }
            long islandGapCount = 0;
            List<Integer> holeIndices = new ArrayList<Integer>();
            List<Double> holeWeights = new ArrayList<Double>();
            for (int index = 0; index < gapCount; index++) {
                if (Double.isNaN(gaps[index])) {
                    islandGapCount++;
                } else {
                    holeIndices.add(index);
                    holeWeights.add(gaps[index]);
                }
            }

            long[] distances = new long[gapCount];
            long minimumIslandSpacing = Long.MAX_VALUE;
            if (holeIndices.isEmpty()) {
                long base = capacity / gapCount;
                long remainder = capacity - base * gapCount;
                for (int index = 0; index < gapCount; index++) {
                    distances[index] = base + (remainder > 0 ? 1 : 0);
                    if (remainder > 0) {
                        remainder--;
                    }
                }
                minimumIslandSpacing = base;
            } else {
                long spacing = 0;
                if (islandGapCount > 0) {
                    spacing = Math.min(step, capacity / (2 * islandGapCount));
                    if (spacing < 1) {
                        spacing = (capacity - MINIMUM_HOLE_DISTANCE * holeIndices.size()) / islandGapCount;
                        if (spacing < 1) {
                            return null;
                        }
                    }
                    minimumIslandSpacing = spacing;
                }
                long[] holeDistances = distribute(capacity - spacing * islandGapCount, holeWeights, MINIMUM_HOLE_DISTANCE);
                if (holeDistances == null) {
                    return null;
                }
                for (int index = 0; index < gapCount; index++) {
                    distances[index] = spacing;
                }
                for (int k = 0; k < holeIndices.size(); k++) {
                    distances[holeIndices.get(k)] = holeDistances[k];
                }
            }

            long[] positions = new long[gapCount - 1];
            long current = lower;
            for (int index = 0; index < gapCount - 1; index++) {
                current += distances[index];
                positions[index] = current;
            }
            return new Outcome(positions, minimumIslandSpacing);
        }

        static long[] distribute(long total, List<Double> weights, long minimum) {
            int count = weights.size();
            if (count == 0 || total < minimum * count) {
                return null;
            }
            long spare = total - minimum * count;
            double sum = 0;
            for (double weight : weights) {
                sum += weight;
            }
            long[] result = new long[count];
            Arrays.fill(result, minimum);
            long used = 0;
            if (sum > 0) {
                for (int index = 0; index < count; index++) {
                    long share = (long) Math.floor((double) spare * weights.get(index) / sum);
                    long clamped = Math.max(0, Math.min(share, spare - used));
                    result[index] += clamped;
                    used += clamped;
                }
            }
            if (used < spare) {
                int largest = 0;
                for (int index = 0; index < count; index++) {
                    if (weights.get(index) > weights.get(largest)) {
                        largest = index;
                    }
                }
                result[largest] += spare - used;
            }
            return result;
        }
    }
}
