package org.telegram.relay.bridge;

import org.telegram.relay.RelayConfig;
import org.telegram.relay.account.RelayAccount;
import org.telegram.relay.account.SqliteMessageIdStore;
import org.telegram.relay.mattermost.MattermostException;
import org.telegram.relay.mattermost.PostsQuery;
import org.telegram.relay.mattermost.ids.HistoryDirection;
import org.telegram.relay.mattermost.ids.IngestResult;
import org.telegram.relay.mattermost.ids.MessageIdAllocationException;
import org.telegram.relay.mattermost.ids.MessageIdEntry;
import org.telegram.relay.mattermost.ids.PostOrderKey;
import org.telegram.relay.mattermost.ids.PostParts;
import org.telegram.relay.mattermost.model.Post;
import org.telegram.relay.mattermost.model.PostList;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * History windows for {@code messages.getHistory} (internals doc §5.3/§5.9) over the per-dialog
 * {@code MessageIdAllocator} id map.
 *
 * <p>Let L be the chat's messages newest first and p the index of the first message with
 * {@code id < offset_id}; the answer is {@code L[max(0, p+add_offset) … p+add_offset+limit)}, never
 * refilled when clamped at the top. The bridge fetches exactly the server posts that window needs, in
 * one {@code before=}/{@code after=} page on each side of the anchor post, ingests them into the
 * allocator anchored at that post (so ids stay contiguous with it: the client closes its holes between
 * the returned ids and {@code offset_id}), and returns the messages in id order. A post with N files is
 * N messages (parts), so windows are counted in messages, not posts.
 *
 * <p>Runs on the account's serial bridge executor (the allocator and its store are not thread-safe);
 * only HTTP may fan out to {@link #pool}.
 */
final class RelayHistory {
    private static final String TAG = RelayConfig.TAG_BRIDGE;
    static final int MAX_PER_PAGE = 200;
    private static final int POST_CACHE = 6000;

    /** One message: a part of a post with its local id. */
    static final class Unit {
        final RelayConvert.Layout layout;
        final int part;
        int localId;

        Unit(RelayConvert.Layout layout, int part) {
            this.layout = layout;
            this.part = part;
        }

        String partId() {
            return PostParts.partId(layout.post.id, part);
        }
    }

    final RelayAccount account;
    private final LinkedHashMap<String, Post> posts = new LinkedHashMap<>(256, 0.75f, true);
    private final Map<String, Integer> replyCounts = new HashMap<>();
    final AtomicInteger renumbers = new AtomicInteger();

    RelayHistory(RelayAccount account) {
        this.account = account;
    }

    // MARK: - Posts

    synchronized void remember(PostList list) {
        if (list == null || list.posts == null) {
            return;
        }
        for (Post post : list.posts.values()) {
            rememberPost(post);
        }
    }

    synchronized void rememberPost(Post post) {
        posts.put(post.id, post);
        if (posts.size() > POST_CACHE) {
            java.util.Iterator<String> it = posts.keySet().iterator();
            while (posts.size() > POST_CACHE && it.hasNext()) {
                it.next();
                it.remove();
            }
        }
    }

    synchronized Post cachedPost(String postId) {
        return posts.get(postId);
    }

    /** The post from the cache, else {@code GET /posts/{id}}; null when it is gone. */
    Post post(String postId) throws MattermostException {
        Post post = cachedPost(postId);
        if (post != null) {
            return post;
        }
        try {
            post = account.client.post(postId);
        } catch (MattermostException e) {
            if (e.isNotFound() || e.getStatusCode() == 403) {
                return null;
            }
            throw e;
        }
        rememberPost(post);
        return post;
    }

    // MARK: - Fetching

    PostList fetchLatest(String channelId, int count) throws MattermostException {
        PostList list = account.client.posts(channelId, PostsQuery.latest(clamp(count)));
        remember(list);
        return list;
    }

    private PostList fetchBefore(String channelId, String postId, int count) throws MattermostException {
        PostList list = account.client.posts(channelId, PostsQuery.before(postId, clamp(count)));
        remember(list);
        return list;
    }

    private PostList fetchAfter(String channelId, String postId, int count) throws MattermostException {
        PostList list = account.client.posts(channelId, PostsQuery.after(postId, clamp(count)));
        remember(list);
        return list;
    }

    private static int clamp(int count) {
        return Math.max(1, Math.min(MAX_PER_PAGE, count));
    }

    // MARK: - Ingest

    private static List<PostOrderKey> keys(List<Post> page) {
        List<PostOrderKey> keys = new ArrayList<>();
        for (Post post : page) {
            RelayConvert.Layout layout = new RelayConvert.Layout(post);
            Collections.addAll(keys, new PostOrderKey(post.createAt, post.id).parts(layout.parts));
        }
        return keys;
    }

    private void note(IngestResult result, String channelId, String what) {
        if (result != null && result.renumberPlan != null) {
            renumbers.incrementAndGet();
            RelayConfig.log(TAG, "RENUMBER in " + channelId + " (" + what + "): " + result.renumberPlan + " — cached client messages in that range are stale");
        }
    }

    /** The newest page as the latest island. */
    void ingestLatest(String channelId, List<Post> pageNewestFirst, boolean reachedStart) throws MessageIdAllocationException {
        if (pageNewestFirst.isEmpty()) {
            return;
        }
        note(account.messageIds.ingestLatestPage(keys(pageNewestFirst), reachedStart, channelId, account.messageIdStore()), channelId, "latest");
    }

    private void ingestPage(String channelId, List<Post> page, String anchorPostId, HistoryDirection direction, boolean reachedEnd) throws MessageIdAllocationException {
        if (page.isEmpty() && !reachedEnd) {
            return;
        }
        note(account.messageIds.ingestPage(keys(page), anchorPostId, direction, reachedEnd, channelId, account.messageIdStore()), channelId, direction.name());
    }

    /** A contiguous run that is not anchored to a mapped post (posts around the first unread). */
    void ingestRun(String channelId, List<Post> run, boolean reachesStart, boolean reachesLatest) throws MessageIdAllocationException {
        if (run.isEmpty()) {
            return;
        }
        note(account.messageIds.ingestRun(keys(run), Collections.<String>emptyList(), reachesStart, reachesLatest, channelId, account.messageIdStore()), channelId, "run");
    }

    /** Maps a single post that was not paged in (a reply's thread root): its own island, or a late insert. */
    int mapSingle(String channelId, Post post) throws MessageIdAllocationException {
        RelayConvert.Layout layout = new RelayConvert.Layout(post);
        IngestResult result = account.messageIds.insertPost(new PostOrderKey(post.createAt, post.id), layout.parts, channelId, account.messageIdStore());
        note(result, channelId, "single");
        Integer id = result.localId(post.id);
        return id != null ? id : 0;
    }

    /** Fills {@link Unit#localId} from the id map; units without an id are dropped. */
    List<Unit> assignIds(String channelId, List<Unit> units) {
        if (units.isEmpty()) {
            return units;
        }
        List<String> ids = new ArrayList<>(units.size());
        for (Unit unit : units) {
            ids.add(unit.partId());
        }
        Map<String, MessageIdEntry> entries = account.messageIdStore().entries(channelId, ids);
        List<Unit> result = new ArrayList<>(units.size());
        for (Unit unit : units) {
            MessageIdEntry entry = entries.get(unit.partId());
            if (entry != null && !entry.isDeleted) {
                unit.localId = entry.localId;
                result.add(unit);
            }
        }
        return result;
    }

    /** Messages of posts, newest first (parts in descending order). */
    static List<Unit> newestFirst(List<Post> postsNewestFirst) {
        List<Unit> units = new ArrayList<>();
        for (Post post : postsNewestFirst) {
            RelayConvert.Layout layout = new RelayConvert.Layout(post);
            for (int part = layout.parts - 1; part >= 0; part--) {
                units.add(new Unit(layout, part));
            }
        }
        return units;
    }

    /** Messages of posts, oldest first. */
    static List<Unit> oldestFirst(List<Post> postsNewestFirst) {
        List<Unit> units = new ArrayList<>();
        for (int i = postsNewestFirst.size() - 1; i >= 0; i--) {
            RelayConvert.Layout layout = new RelayConvert.Layout(postsNewestFirst.get(i));
            for (int part = 0; part < layout.parts; part++) {
                units.add(new Unit(layout, part));
            }
        }
        return units;
    }

    // MARK: - Windows

    /** The newest {@code count} messages (fetched now) and whether the channel start was reached. */
    static final class Latest {
        final List<Unit> units;
        final List<Post> posts;
        final boolean reachedStart;

        Latest(List<Unit> units, List<Post> posts, boolean reachedStart) {
            this.units = units;
            this.posts = posts;
            this.reachedStart = reachedStart;
        }
    }

    Latest latest(String channelId, int count) throws MattermostException, MessageIdAllocationException {
        return latest(channelId, fetchLatest(channelId, count), clamp(count));
    }

    /** Ingests an already fetched newest page. */
    Latest latest(String channelId, PostList list, int requested) throws MessageIdAllocationException {
        List<Post> page = list.getOrderedPosts();
        boolean reachedStart = page.size() < requested;
        ingestLatest(channelId, page, reachedStart);
        return new Latest(assignIds(channelId, newestFirst(page)), page, reachedStart);
    }

    /**
     * The {@code getHistory} window, newest first.
     *
     * @return the messages, or an empty list past the oldest message (the client then writes its
     *         start-of-history sentinel)
     */
    List<Unit> window(String channelId, int offsetId, int addOffset, int limit, int maxId, int minId) throws MattermostException, MessageIdAllocationException {
        return window(channelId, offsetId, addOffset, limit, maxId, minId, false);
    }

    /**
     * @param rootsOnly A7a: collapsed reply threads hide replies from a megagroup's main history. L is then
     *                  the chat's root posts only; pages are fetched (and ingested, so the id map stays
     *                  contiguous over all posts) until the window has enough of them.
     */
    List<Unit> window(String channelId, int offsetId, int addOffset, int limit, int maxId, int minId, boolean rootsOnly) throws MattermostException, MessageIdAllocationException {
        if (limit <= 0) {
            return new ArrayList<>();
        }
        List<Unit> result;
        MessageIdEntry anchor = offsetId != 0 ? anchorFor(channelId, offsetId) : null;
        if (anchor == null) {
            int skip = Math.max(0, addOffset);
            result = slice(latestVisible(channelId, skip + limit, rootsOnly), skip, limit);
        } else {
            result = around(channelId, anchor, offsetId, addOffset, limit, rootsOnly);
        }
        return filterIds(result, maxId, minId);
    }

    /**
     * A7a, jump to date (load type 4): the window around {@code anchor}, the newest post created before the
     * date (p = its index), as if {@code offset_id} were just above its last message; null anchor = the date
     * is before the chat's first post (p = past the oldest message).
     */
    List<Unit> windowAtPost(String channelId, Post anchor, Post oldest, int addOffset, int limit, boolean rootsOnly) throws MattermostException, MessageIdAllocationException {
        Post post = anchor != null ? anchor : oldest;
        if (post == null) {
            return new ArrayList<>();
        }
        if (localIdOf(channelId, post.id) == 0) {
            mapSingle(channelId, post);
        }
        int offsetId = anchor != null ? lastLocalIdOf(channelId, post) + 1 : localIdOf(channelId, post.id);
        MessageIdEntry entry = account.messageIdStore().entries(channelId, Collections.singletonList(post.id)).get(post.id);
        if (entry == null || offsetId <= 0) {
            return new ArrayList<>();
        }
        if (anchor == null) {
            // p = the chat's length: L[size+addOffset, size+addOffset+limit) = the oldest -addOffset messages.
            List<Unit> window = around(channelId, entry, offsetId, addOffset, limit, rootsOnly);
            List<Unit> result = new ArrayList<>();
            for (Unit unit : window) {
                if (unit.localId >= offsetId) {
                    result.add(unit);
                }
            }
            return result;
        }
        return around(channelId, entry, offsetId, addOffset, limit, rootsOnly);
    }

    private static List<Unit> filterIds(List<Unit> result, int maxId, int minId) {
        if (maxId != 0 || minId != 0) {
            List<Unit> filtered = new ArrayList<>(result.size());
            for (Unit unit : result) {
                if ((maxId == 0 || unit.localId < maxId) && (minId == 0 || unit.localId > minId)) {
                    filtered.add(unit);
                }
            }
            result = filtered;
        }
        return result;
    }

    /** The mapped, non-deleted entry at {@code localId}, else the nearest one below, else above; null when the chat has none. */
    private MessageIdEntry anchorFor(String channelId, int localId) {
        SqliteMessageIdStore store = account.messageIdStore();
        MessageIdEntry exact = store.entryByLocalId(channelId, localId);
        if (exact != null && !exact.isDeleted) {
            return exact;
        }
        for (MessageIdEntry entry : store.entriesFromLocalId(channelId, localId - 1, false, 32)) {
            if (!entry.isDeleted) {
                return entry;
            }
        }
        for (MessageIdEntry entry : store.entriesFromLocalId(channelId, localId + 1, true, 32)) {
            if (!entry.isDeleted) {
                return entry;
            }
        }
        return null;
    }

    private List<Unit> around(String channelId, MessageIdEntry anchorEntry, int offsetId, int addOffset, int limit, boolean rootsOnly) throws MattermostException, MessageIdAllocationException {
        String anchorPostId = anchorEntry.serverPostId();
        Post anchorPost = post(anchorPostId);
        if (anchorPost == null) {
            // The anchor is gone on the server (deleted); fall back to the newest messages.
            RelayConfig.log(TAG, "history anchor " + anchorPostId + " is gone, answering the newest page");
            int skip = Math.max(0, addOffset);
            return slice(latestVisible(channelId, skip + limit, rootsOnly), skip, limit);
        }
        int newerWanted = addOffset < 0 ? -addOffset : 0;
        int olderSkip = Math.max(0, addOffset);
        int olderWanted = addOffset < 0 ? Math.max(0, limit + addOffset) : olderSkip + limit;

        // The anchor post's own parts, split at offset_id.
        List<Unit> anchorUnits = visibleOnly(assignIds(channelId, oldestFirst(Collections.singletonList(anchorPost))), rootsOnly);
        List<Unit> newer = new ArrayList<>(); // ids >= offset_id, nearest first (ascending)
        List<Unit> older = new ArrayList<>(); // ids < offset_id, nearest first (descending)
        for (Unit unit : anchorUnits) {
            if (unit.localId >= offsetId) {
                newer.add(unit);
            }
        }
        for (int i = anchorUnits.size() - 1; i >= 0; i--) {
            if (anchorUnits.get(i).localId < offsetId) {
                older.add(anchorUnits.get(i));
            }
        }

        if (newerWanted > newer.size()) {
            newer.addAll(newerThan(channelId, anchorPostId, newerWanted - newer.size(), rootsOnly));
        }
        if (olderWanted > older.size()) {
            older.addAll(olderThan(channelId, anchorPostId, olderWanted - older.size(), rootsOnly));
        }

        // Window positions relative to p (the first message below offset_id): newer.get(k) sits at p-1-k,
        // older.get(j) at p+j. The window is [p+addOffset, p+addOffset+limit).
        List<Unit> result = new ArrayList<>(limit);
        if (addOffset < 0) {
            int kMax = Math.min(-addOffset - 1, newer.size() - 1);
            int kMin = Math.max(0, -addOffset - limit);
            for (int k = kMax; k >= kMin; k--) {
                result.add(newer.get(k));
            }
            result.addAll(slice(older, 0, olderWanted));
        } else {
            result.addAll(slice(older, olderSkip, limit));
        }
        return result;
    }

    // MARK: - Paging with collapsed reply threads (A7a)

    /** Pages fetched per window at most while looking for root posts (5,000 posts with replies only). */
    private static final int MAX_ROUNDS = 25;

    private static List<Unit> visibleOnly(List<Unit> units, boolean rootsOnly) {
        if (!rootsOnly) {
            return units;
        }
        List<Unit> result = new ArrayList<>(units.size());
        for (Unit unit : units) {
            if (!unit.layout.post.isReply()) {
                result.add(unit);
            }
        }
        return result;
    }

    private static int pageSize(int wanted, boolean rootsOnly) {
        return rootsOnly ? Math.min(MAX_PER_PAGE, Math.max(50, 2 * wanted)) : clamp(wanted);
    }

    /** The newest {@code wanted} visible messages, newest first (fewer at the channel start). */
    private List<Unit> latestVisible(String channelId, int wanted, boolean rootsOnly) throws MattermostException, MessageIdAllocationException {
        if (!rootsOnly) {
            return latest(channelId, wanted).units;
        }
        Latest latest = latest(channelId, pageSize(wanted, true));
        List<Unit> result = visibleOnly(latest.units, true);
        if (result.size() < wanted && !latest.reachedStart && !latest.posts.isEmpty()) {
            result.addAll(olderThan(channelId, latest.posts.get(latest.posts.size() - 1).id, wanted - result.size(), true));
        }
        return result;
    }

    /** Visible messages older than a mapped post, nearest first, until {@code wanted} or the channel start. */
    private List<Unit> olderThan(String channelId, String fromPostId, int wanted, boolean rootsOnly) throws MattermostException, MessageIdAllocationException {
        List<Unit> result = new ArrayList<>();
        String cursor = fromPostId;
        for (int round = 0; round < MAX_ROUNDS && result.size() < wanted; round++) {
            int count = pageSize(wanted - result.size(), rootsOnly);
            List<Post> page = fetchBefore(channelId, cursor, count).getOrderedPosts();
            boolean end = page.size() < count;
            ingestPage(channelId, page, cursor, HistoryDirection.OLDER, end);
            result.addAll(visibleOnly(assignIds(channelId, newestFirst(page)), rootsOnly));
            if (end || page.isEmpty()) {
                break;
            }
            cursor = page.get(page.size() - 1).id;
        }
        return result;
    }

    /** Visible messages newer than a mapped post, nearest first, until {@code wanted} or the newest post. */
    private List<Unit> newerThan(String channelId, String fromPostId, int wanted, boolean rootsOnly) throws MattermostException, MessageIdAllocationException {
        List<Unit> result = new ArrayList<>();
        String cursor = fromPostId;
        for (int round = 0; round < MAX_ROUNDS && result.size() < wanted; round++) {
            int count = pageSize(wanted - result.size(), rootsOnly);
            List<Post> page = fetchAfter(channelId, cursor, count).getOrderedPosts();
            boolean end = page.size() < count;
            ingestPage(channelId, page, cursor, HistoryDirection.NEWER, end);
            result.addAll(visibleOnly(assignIds(channelId, oldestFirst(page)), rootsOnly));
            if (end || page.isEmpty()) {
                break;
            }
            cursor = page.get(0).id;
        }
        return result;
    }

    private static List<Unit> slice(List<Unit> units, int from, int count) {
        if (from >= units.size() || count <= 0) {
            return new ArrayList<>();
        }
        return new ArrayList<>(units.subList(from, Math.min(units.size(), from + count)));
    }

    // MARK: - Lookups

    /** The message at an exact local id, or null (unmapped, deleted, or gone on the server). */
    Unit unitAt(String channelId, int localId) throws MattermostException {
        MessageIdEntry entry = account.messageIdStore().entryByLocalId(channelId, localId);
        if (entry == null || entry.isDeleted) {
            return null;
        }
        Post post = post(entry.serverPostId());
        if (post == null) {
            return null;
        }
        RelayConvert.Layout layout = new RelayConvert.Layout(post);
        if (entry.partIndex() >= layout.parts) {
            return null;
        }
        Unit unit = new Unit(layout, entry.partIndex());
        unit.localId = localId;
        return unit;
    }

    /** The local id of a mapped post's part 0, or 0. */
    int localIdOf(String channelId, String postId) {
        MessageIdEntry entry = account.messageIdStore().entries(channelId, Collections.singletonList(postId)).get(postId);
        return entry != null && !entry.isDeleted ? entry.localId : 0;
    }

    /** The local id of the last part of a post (its newest message), or 0. */
    int lastLocalIdOf(String channelId, Post post) {
        RelayConvert.Layout layout = new RelayConvert.Layout(post);
        String partId = PostParts.partId(post.id, layout.parts - 1);
        MessageIdEntry entry = account.messageIdStore().entries(channelId, Collections.singletonList(partId)).get(partId);
        return entry != null && !entry.isDeleted ? entry.localId : 0;
    }

    /**
     * Thread roots of the replies in {@code units}: their local ids (mapping unloaded roots as single-post
     * islands, so the reply header can jump there) and reply counts.
     */
    Map<String, Integer> rootIds(String channelId, List<Unit> units) throws MattermostException, MessageIdAllocationException {
        Map<String, Integer> ids = new HashMap<>();
        for (Unit unit : units) {
            Post post = unit.layout.post;
            if (!post.isReply() || ids.containsKey(post.rootId)) {
                continue;
            }
            int id = localIdOf(channelId, post.rootId);
            if (id == 0) {
                Post root = post(post.rootId);
                if (root != null && channelId.equals(root.channelId) && !root.isDeleted()) {
                    id = mapSingle(channelId, root);
                }
            }
            ids.put(post.rootId, id);
        }
        return ids;
    }

    /** Reply counts of the root posts in {@code units} that have replies (from {@code GET /posts/{id}}, cached). */
    Map<String, Integer> replyCounts(List<Unit> units) {
        Map<String, Integer> counts = new HashMap<>();
        java.util.Set<String> roots = new java.util.HashSet<>();
        synchronized (this) {
            for (Post post : posts.values()) {
                if (post.isReply()) {
                    roots.add(post.rootId);
                }
            }
        }
        for (Unit unit : units) {
            Post post = unit.layout.post;
            if (unit.part != 0 || post.isReply()) {
                continue;
            }
            if (post.replyCount > 0) {
                counts.put(post.id, (int) post.replyCount);
                continue;
            }
            if (!roots.contains(post.id)) {
                continue;
            }
            Integer known;
            synchronized (this) {
                known = replyCounts.get(post.id);
            }
            if (known == null) {
                try {
                    Post fresh = account.client.post(post.id);
                    known = (int) fresh.replyCount;
                } catch (MattermostException e) {
                    known = 0;
                }
                synchronized (this) {
                    replyCounts.put(post.id, known);
                }
            }
            if (known > 0) {
                counts.put(post.id, known);
            }
        }
        return counts;
    }
}
