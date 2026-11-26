package org.telegram.relay.bridge;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.relay.RelayConfig;
import org.telegram.relay.account.RelayAccount;
import org.telegram.relay.account.RelayDatabase;
import org.telegram.relay.mattermost.MattermostException;
import org.telegram.relay.mattermost.ThreadQuery;
import org.telegram.relay.mattermost.UserThreadsQuery;
import org.telegram.relay.mattermost.ids.MessageIdEntry;
import org.telegram.relay.mattermost.model.Channel;
import org.telegram.relay.mattermost.model.Post;
import org.telegram.relay.mattermost.model.PostList;
import org.telegram.relay.mattermost.model.Team;
import org.telegram.relay.mattermost.model.ThreadList;
import org.telegram.relay.mattermost.model.UserThread;
import org.telegram.relay.mattermost.ws.WebSocketEvent;
import org.telegram.relay.mattermost.ws.WebSocketEventType;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A7a: Mattermost collapsed reply threads behind Telegram's thread view (internals doc §5.8, iOS 3B).
 *
 * <ul>
 *   <li>{@code messages.getDiscussionMessage}: the thread root (a reply opens its root's thread), the newest
 *       reply id and the thread's read state ({@code GET /users/me/teams/{team}/threads/{root}}).</li>
 *   <li>{@code messages.getReplies}: the whole thread from {@code GET /posts/{root}/thread} (pages of 200,
 *       {@code direction=down}), every reply mapped into the chat's one id space (late inserts or islands,
 *       so ids keep the time order), answered with the §5.3 window rules.</li>
 *   <li>{@code messages.readDiscussion}: {@code PUT …/threads/{root}/read/{create_at of the reply read}}.</li>
 *   <li>Threads list (UI {@code RelayThreadsActivity}, the Threads tab), followed threads, unread totals (the
 *       Threads tab badge) and the live {@code thread_*} events.</li>
 * </ul>
 *
 * Request handlers run on the account's bridge executor; the public static API below posts there and
 * answers on the UI thread.
 */
public final class RelayThreads {
    private static final String TAG = RelayConfig.TAG_BRIDGE;
    private static final int THREAD_PAGE = 200;
    private static final int THREAD_LIST_PAGE = 200;
    private static final long THREAD_CACHE_MS = 15_000;

    private static final WeakHashMap<RelayAccount, RelayThreads> instances = new WeakHashMap<>();

    static RelayThreads of(RelayAccount account) {
        synchronized (instances) {
            RelayThreads threads = instances.get(account);
            if (threads == null) {
                instances.put(account, threads = new RelayThreads(account));
            }
            return threads;
        }
    }

    private final RelayAccount account;
    private final RelayReadPath path;

    private static final class ThreadData {
        final Post root;
        /** Replies, oldest first. */
        final List<Post> replies;
        final long fetchedAt;

        ThreadData(Post root, List<Post> replies) {
            this.root = root;
            this.replies = replies;
            this.fetchedAt = System.currentTimeMillis();
        }
    }

    private final LinkedHashMap<String, ThreadData> threadCache = new LinkedHashMap<String, ThreadData>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ThreadData> eldest) {
            return size() > 24;
        }
    };

    /** Followed threads of all teams (the user's thread memberships), by root post id. */
    private final Map<String, UserThread> followed = Collections.synchronizedMap(new LinkedHashMap<String, UserThread>());
    private volatile boolean followedLoaded;
    private volatile int unreadThreads;
    private volatile int unreadMentions;

    private RelayThreads(RelayAccount account) {
        this.account = account;
        this.path = RelayReadPath.of(account);
    }

    // MARK: - Registry

    static void register(Map<Class<?>, RelayHandlers.Handler> map) {
        map.put(TLRPC.TL_messages_getDiscussionMessage.class, (bridge, account, req) -> of(account).getDiscussionMessage((TLRPC.TL_messages_getDiscussionMessage) req));
        map.put(TLRPC.TL_messages_getReplies.class, (bridge, account, req) -> of(account).getReplies((TLRPC.TL_messages_getReplies) req));
        map.put(TLRPC.TL_messages_readDiscussion.class, (bridge, account, req) -> of(account).readDiscussion(bridge, (TLRPC.TL_messages_readDiscussion) req));
    }

    /**
     * DM threads: true when collapsed reply threads apply to the chat — megagroups (channels, GMs) and DMs with another
     * user; the self-DM (Saved Messages) keeps replies inline, as iOS does.
     */
    static boolean collapses(RelayAccount account, Channel channel, boolean collapsedThreads) {
        return collapsedThreads && channel != null && threadsIn(account, channel);
    }

    /** True when the chat can have thread views (not the self-DM). */
    static boolean threadsIn(RelayAccount account, Channel channel) {
        if (!channel.isDirect()) {
            return true;
        }
        String other = channel.getDirectMessageTeammateId(account.session.userId);
        return other != null && !other.equals(account.session.userId);
    }

    // MARK: - Thread contents

    /** The thread root for a message of the chat (a reply → its root), or null. */
    private Post rootOf(String channelId, int localId) throws MattermostException {
        RelayHistory.Unit unit = path.history.unitAt(channelId, localId);
        if (unit == null) {
            return null;
        }
        Post post = unit.layout.post;
        if (post.isReply()) {
            Post root = path.history.post(post.rootId);
            return root != null && !root.isDeleted() ? root : null;
        }
        return post.isSystemMessage() ? null : post;
    }

    void invalidate(String rootId) {
        synchronized (threadCache) {
            threadCache.remove(rootId);
        }
    }

    /** The root and every reply ({@code GET /posts/{root}/thread}, paged down), cached briefly. */
    private ThreadData thread(String rootId, boolean fresh) throws MattermostException {
        synchronized (threadCache) {
            ThreadData cached = threadCache.get(rootId);
            if (cached != null && !fresh && System.currentTimeMillis() - cached.fetchedAt < THREAD_CACHE_MS) {
                return cached;
            }
        }
        Map<String, Post> replies = new LinkedHashMap<>();
        Post root = null;
        ThreadQuery query = new ThreadQuery().perPage(THREAD_PAGE).direction("down");
        for (int page = 0; page < 100; page++) {
            PostList list = account.client.postThread(rootId, query);
            path.history.remember(list);
            Post newest = null;
            int added = 0;
            for (Post post : list.posts.values()) {
                if (rootId.equals(post.id)) {
                    root = post;
                } else if (rootId.equals(post.rootId) && !post.isDeleted() && !post.isEphemeral()) {
                    if (replies.put(post.id, post) == null) {
                        added++;
                    }
                    if (newest == null || post.createAt > newest.createAt || (post.createAt == newest.createAt && post.id.compareTo(newest.id) > 0)) {
                        newest = post;
                    }
                }
            }
            if (list.hasNext == null || !list.hasNext || newest == null || added == 0) {
                break;
            }
            query = new ThreadQuery().perPage(THREAD_PAGE).direction("down").from(newest.id, newest.createAt);
        }
        if (root == null) {
            root = path.history.post(rootId);
        }
        List<Post> sorted = new ArrayList<>(replies.values());
        Collections.sort(sorted, (a, b) -> a.createAt != b.createAt ? Long.compare(a.createAt, b.createAt) : a.id.compareTo(b.id));
        ThreadData data = new ThreadData(root, sorted);
        synchronized (threadCache) {
            threadCache.put(rootId, data);
        }
        path.repliers.fromThread(rootId, sorted); // DM threads + replier avatars: the exact latest repliers
        return data;
    }

    /** Maps the root and every reply into the chat's id space; returns the replies' messages, ascending ids. */
    private List<RelayHistory.Unit> mapThread(String channelId, ThreadData data) throws Exception {
        if (data.root != null && path.history.localIdOf(channelId, data.root.id) == 0) {
            path.history.mapSingle(channelId, data.root);
        }
        int mapped = 0;
        for (Post reply : data.replies) {
            if (path.history.localIdOf(channelId, reply.id) == 0) {
                path.history.mapSingle(channelId, reply);
                mapped++;
            }
        }
        if (mapped > 0) {
            RelayConfig.log(TAG, "thread " + (data.root != null ? data.root.id : "?") + ": mapped " + mapped + " replies");
        }
        List<Post> newestFirst = new ArrayList<>(data.replies);
        Collections.reverse(newestFirst);
        List<RelayHistory.Unit> units = path.history.assignIds(channelId, RelayHistory.oldestFirst(newestFirst));
        Collections.sort(units, (a, b) -> Integer.compare(a.localId, b.localId));
        return units;
    }

    String teamOf(Channel channel) throws MattermostException {
        if (channel != null && channel.teamId != null && !channel.teamId.isEmpty()) {
            return channel.teamId;
        }
        List<Team> teams = path.directory.any().teams;
        return teams.isEmpty() ? null : teams.get(0).id;
    }

    // MARK: - Handlers

    TLObject getDiscussionMessage(TLRPC.TL_messages_getDiscussionMessage req) throws Exception {
        String channelId = path.directory.channelIdOf(req.peer);
        if (channelId == null) {
            return RelayTl.error(400, "MSG_ID_INVALID");
        }
        Channel channel = path.directory.channel(channelId);
        if (channel == null || !threadsIn(account, channel)) { // DM threads: DMs have thread views too (not the self-DM)
            return RelayTl.error(400, "MSG_ID_INVALID");
        }
        Post root = rootOf(channelId, req.msg_id);
        if (root == null) {
            return RelayTl.error(400, "MSG_ID_INVALID");
        }
        ThreadData data = thread(root.id, true);
        if (data.root != null) {
            root = data.root;
        }
        List<RelayHistory.Unit> replies = mapThread(channelId, data);
        List<RelayHistory.Unit> rootUnits = path.history.assignIds(channelId, RelayHistory.newestFirst(Collections.singletonList(root)));

        TLRPC.TL_messages_discussionMessage res = new TLRPC.TL_messages_discussionMessage();
        Set<String> users = new LinkedHashSet<>();
        res.messages.addAll(path.messages(channel, rootUnits, users));
        if (!replies.isEmpty()) {
            res.max_id = replies.get(replies.size() - 1).localId;
            res.flags |= TLObject.FLAG_0;
        }
        UserThread state = null;
        String teamId = teamOf(channel);
        if (teamId != null) {
            try {
                state = account.client.userThread(teamId, root.id, false);
            } catch (MattermostException e) {
                if (!e.isNotFound() && e.getStatusCode() != 403 && e.getStatusCode() != 400) {
                    throw e;
                }
            }
        }
        int readInbox = 0;
        int unread = 0;
        if (state != null && state.lastViewedAt > 0) {
            for (RelayHistory.Unit unit : replies) {
                if (unit.layout.post.createAt <= state.lastViewedAt) {
                    readInbox = unit.localId;
                } else if (!account.session.userId.equals(unit.layout.post.userId)) {
                    unread++;
                }
            }
            if (state.unreadReplies >= 0 && unread > state.unreadReplies) {
                unread = (int) state.unreadReplies;
            }
        }
        if (readInbox != 0) {
            res.read_inbox_max_id = readInbox;
            res.flags |= TLObject.FLAG_1;
        }
        res.unread_count = unread;
        String dmUser = path.directory.dmUser(channel);
        if (dmUser != null) {
            users.add(dmUser); // DM threads: the chat is the other user
        } else {
            res.chats.add(path.directory.tlChannel(channel));
        }
        res.users.addAll(path.users(users));
        RelayConfig.log(TAG, "discussion " + root.id + " in " + channel.name + ": root=" + (rootUnits.isEmpty() ? 0 : rootUnits.get(0).localId)
                + " replies=" + replies.size() + " max=" + res.max_id + " read=" + readInbox + " unread=" + unread);
        return res;
    }

    TLObject getReplies(TLRPC.TL_messages_getReplies req) throws Exception {
        String channelId = path.directory.channelIdOf(req.peer);
        Channel channel = channelId != null ? path.directory.channel(channelId) : null;
        Post root = channel != null ? rootOf(channelId, req.msg_id) : null;
        if (root == null) {
            if (!(req.peer instanceof TLRPC.TL_inputPeerChannel)) {
                return new TLRPC.TL_messages_messages(); // DM threads: a private chat answers messages.messages
            }
            TLRPC.TL_messages_channelMessages empty = new TLRPC.TL_messages_channelMessages();
            empty.pts = account.db().pts(RelayDatabase.channelBox(req.peer.channel_id));
            return empty;
        }
        long started = System.currentTimeMillis();
        List<RelayHistory.Unit> ascending = mapThread(channelId, thread(root.id, false));
        List<RelayHistory.Unit> list = new ArrayList<>(ascending);
        Collections.reverse(list); // L: newest first
        int p = 0;
        if (req.offset_id != 0) {
            p = list.size();
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).localId < req.offset_id) {
                    p = i;
                    break;
                }
            }
        } else if (req.offset_date != 0) {
            p = list.size();
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).layout.post.createAt / 1000 < req.offset_date) {
                    p = i;
                    break;
                }
            }
        }
        int from = Math.max(0, p + req.add_offset);
        int to = Math.max(0, Math.min(list.size(), p + req.add_offset + req.limit));
        List<RelayHistory.Unit> window = new ArrayList<>();
        for (int i = from; i < to; i++) {
            RelayHistory.Unit unit = list.get(i);
            if ((req.max_id == 0 || unit.localId < req.max_id) && (req.min_id == 0 || unit.localId > req.min_id)) {
                window.add(unit);
            }
        }
        TLRPC.messages_Messages res = path.messagesResponse(channel, window);
        if (res instanceof TLRPC.TL_messages_channelMessages) {
            ((TLRPC.TL_messages_channelMessages) res).count = list.size();
        } else {
            // DM threads: a private chat's thread page is a slice with the thread's reply count.
            TLRPC.TL_messages_messagesSlice slice = new TLRPC.TL_messages_messagesSlice();
            slice.messages = res.messages;
            slice.users = res.users;
            slice.chats = res.chats;
            slice.count = list.size();
            res = slice;
        }
        RelayConfig.log(TAG, String.format(java.util.Locale.US, "getReplies %s root=%s offset=%d add=%d limit=%d -> %d of %d in %d ms",
                channel.name, root.id, req.offset_id, req.add_offset, req.limit, window.size(), list.size(), System.currentTimeMillis() - started));
        return res;
    }

    TLObject readDiscussion(RelayBridge bridge, TLRPC.TL_messages_readDiscussion req) throws Exception {
        String channelId = path.directory.channelIdOf(req.peer);
        Channel channel = channelId != null ? path.directory.channel(channelId) : null;
        Post root = channel != null ? rootOf(channelId, req.msg_id) : null;
        String teamId = channel != null ? teamOf(channel) : null;
        if (root == null || teamId == null) {
            return new TLRPC.TL_boolTrue();
        }
        long timestamp = System.currentTimeMillis();
        RelayHistory.Unit read = req.read_max_id != 0 ? path.history.unitAt(channelId, req.read_max_id) : null;
        ThreadData data = thread(root.id, false);
        if (read != null && read.layout.post.isReply() && !data.replies.isEmpty()
                && read.layout.post.createAt < data.replies.get(data.replies.size() - 1).createAt) {
            timestamp = read.layout.post.createAt;
        }
        account.client.markThreadRead(teamId, root.id, timestamp);
        UserThread known = followed.get(root.id);
        if (known != null && timestamp >= known.lastReplyAt) {
            known.unreadReplies = 0;
            known.unreadMentions = 0;
            known.lastViewedAt = timestamp;
            recomputeTotals();
        }
        RelayConfig.log(TAG, "thread read " + root.id + " at " + timestamp);
        return new TLRPC.TL_boolTrue();
    }

    // MARK: - Followed threads and totals

    /** Fetches the followed threads of every team (one page each) and recomputes the unread totals. */
    void refreshFollowed() throws MattermostException {
        Map<String, UserThread> fresh = new LinkedHashMap<>();
        for (Team team : path.directory.any().teams) {
            ThreadList list = account.client.userThreads(team.id, new UserThreadsQuery().perPage(THREAD_LIST_PAGE));
            for (UserThread thread : list.threads) {
                if (thread.deleteAt == 0 && thread.post != null) {
                    fresh.put(thread.id, thread);
                    path.repliers.fromParticipants(thread.id, thread.participants, false); // replier avatars
                }
            }
        }
        synchronized (followed) {
            followed.clear();
            followed.putAll(fresh);
        }
        followedLoaded = true;
        recomputeTotals(true);
    }

    private void recomputeTotals() {
        recomputeTotals(false);
    }

    /** Recomputes the unread totals; listeners hear about it when they changed (or {@code force}: the list itself changed). */
    private void recomputeTotals(boolean force) {
        int threads = 0;
        int mentions = 0;
        synchronized (followed) {
            for (UserThread thread : followed.values()) {
                if (thread.unreadReplies > 0 || thread.unreadMentions > 0) {
                    threads++;
                }
                mentions += (int) thread.unreadMentions;
            }
        }
        boolean changed = threads != unreadThreads || mentions != unreadMentions;
        unreadThreads = threads;
        unreadMentions = mentions;
        if (changed || force) {
            notifyListeners(account.account);
        }
    }

    /** Live {@code thread_*} events, preference changes, and thread cache invalidation (bridge executor). */
    void onEvent(WebSocketEvent event) {
        try {
            if (event.type == WebSocketEventType.THREAD_UPDATED || event.type == WebSocketEventType.THREAD_READ_CHANGED || event.type == WebSocketEventType.THREAD_FOLLOW_CHANGED) {
                RelayConfig.log(TAG, "threads: " + event.event + " " + (event.data != null ? event.data.keySet() : "") + " thread=" + event.dataString("thread_id") + " state=" + event.dataString("state"));
            }
            switch (event.type) {
                case POSTED:
                case POST_EDITED:
                case POST_DELETED: {
                    Post post = event.type == WebSocketEventType.POSTED ? (event.getPosted() != null ? event.getPosted().post : null)
                            : event.type == WebSocketEventType.POST_EDITED ? event.getEditedPost() : event.getDeletedPost();
                    if (post != null) {
                        invalidate(post.isReply() ? post.rootId : post.id);
                        if (post.isReply() && event.type == WebSocketEventType.POST_DELETED) {
                            path.repliers.forget(post.rootId); // the server's participants again
                        }
                    }
                    break;
                }
                case THREAD_UPDATED: {
                    UserThread thread = event.getThread();
                    if (thread != null && thread.id != null) {
                        path.repliers.fromParticipants(thread.id, thread.participants, false); // replier avatars
                        UserThread previous = followed.get(thread.id);
                        if (previous == null) {
                            // Not (or no longer) followed: the server's list decides (an unfollow also sends thread_updated).
                            refreshFollowed();
                            break;
                        }
                        if (thread.post == null) {
                            thread.post = previous.post;
                        }
                        followed.put(thread.id, thread);
                        recomputeTotals(true);
                    }
                    break;
                }
                case THREAD_READ_CHANGED: {
                    WebSocketEvent.ThreadReadChanged change = event.getThreadReadChanged();
                    if (change == null) {
                        break;
                    }
                    if (change.threadId == null) {
                        refreshFollowed();
                        break;
                    }
                    UserThread thread = followed.get(change.threadId);
                    if (thread != null) {
                        thread.unreadReplies = change.unreadReplies;
                        thread.unreadMentions = change.unreadMentions;
                        if (change.timestamp > 0) {
                            thread.lastViewedAt = change.timestamp;
                        }
                        recomputeTotals(true);
                    }
                    break;
                }
                case THREAD_FOLLOW_CHANGED: {
                    String threadId = event.dataString("thread_id");
                    String state = event.dataString("state");
                    if (threadId == null) {
                        break;
                    }
                    if ("true".equals(state)) {
                        if (!followed.containsKey(threadId)) {
                            refreshFollowed();
                        }
                    } else if (followed.remove(threadId) != null) {
                        recomputeTotals(true);
                    }
                    break;
                }
                case PREFERENCES_CHANGED:
                case PREFERENCE_CHANGED:
                case PREFERENCES_DELETED: {
                    List<org.telegram.relay.mattermost.model.Preference> preferences = event.getPreferences();
                    if (preferences != null) {
                        for (org.telegram.relay.mattermost.model.Preference p : preferences) {
                            if ("display_settings".equals(p.category) && "collapsed_reply_threads".equals(p.name)) {
                                path.directory.invalidate();
                                path.directory.get(0);
                                break;
                            }
                        }
                    }
                    break;
                }
                default:
                    break;
            }
        } catch (Throwable t) {
            RelayConfig.logError(TAG, "threads: " + event + " failed", t);
        }
    }

    /**
     * Collapsed threads switched: every chat's history is reloaded (replies appear in or leave the main history):
     * megagroups through {@code updateChannelTooLong}, DMs through {@link RelayDmCache} (DM threads).
     */
    static void onCollapsedThreadsChanged(RelayAccount account) {
        notifyListeners(account.account); // the Threads tab and its badge say whether threads are on
        RelayBridge bridge = RelayBridge.get(account.account);
        bridge.post(() -> {
            RelayLive live = bridge.live();
            if (live == null) {
                return;
            }
            try {
                RelayReadPath path = RelayReadPath.of(account);
                for (Channel channel : path.directory.any().channels.values()) {
                    live.reloadChannel(channel);
                }
                RelayConfig.log(TAG, "collapsed threads switched: megagroup histories reloaded");
            } catch (Throwable t) {
                RelayConfig.logError(TAG, "reload after the collapsed threads switch", t);
            }
            reloadDirectChats(account, "collapsed threads switched");
        });
    }

    private static final String KV_DM_THREADS = "dm_threads_v1";

    /**
     * DM threads: on the first start of a build that collapses DM threads, the DMs cached by an older build (replies
     * inline, roots without "N Replies") are reloaded once. Bridge executor, after the directory is known.
     */
    static void migrateDirectChats(RelayAccount account, boolean reload) {
        if (account.db().kv(KV_DM_THREADS) != null) {
            return;
        }
        account.db().putKv(KV_DM_THREADS, "1");
        if (reload) {
            RelayBridge.get(account.account).post(() -> reloadDirectChats(account, "DM threads: first start"));
        }
    }

    /** Drops the client's cached history of every DM (not the self-DM) and gives each the bridge's dialog row. Bridge executor. */
    private static void reloadDirectChats(RelayAccount account, String reason) {
        try {
            RelayReadPath path = RelayReadPath.of(account);
            List<Channel> dms = new ArrayList<>();
            for (Channel channel : path.directory.any().channels.values()) {
                if (channel.isDirect() && threadsIn(account, channel)) {
                    dms.add(channel);
                }
            }
            if (dms.isEmpty()) {
                return;
            }
            path.repliers.clear();
            TLRPC.TL_messages_dialogs dialogs = path.dialogsOf(dms);
            RelayDmCache.overwrite(account.account, dialogs);
            RelayConfig.log(TAG, reason + ": " + dialogs.dialogs.size() + " DM histories reloaded");
        } catch (Throwable t) {
            RelayConfig.logError(TAG, "DM reload (" + reason + ")", t);
        }
    }

    // MARK: - Public API for the UI (answers on the UI thread)

    public interface Callback<T> {
        void done(T result, String error);
    }

    /** A team of the account (threads list tabs). */
    public static final class TeamInfo {
        public final String id;
        public final String name;
        public final String title;

        TeamInfo(String id, String name, String title) {
            this.id = id;
            this.name = name;
            this.title = title;
        }
    }

    /** One row of the threads list. */
    public static final class ThreadRow {
        public String rootPostId;
        public long dialogId;
        public int rootMessageId;
        public String channelTitle;
        /** TL user id of the root's author (the users are put into MessagesController first). */
        public long authorId;
        public String authorName;
        public String text;
        public long replyCount;
        public long unreadReplies;
        public long unreadMentions;
        public long lastReplyAt;
        public final ArrayList<Long> participants = new ArrayList<>();
    }

    public static final class Totals {
        public final int unreadThreads;
        public final int unreadMentions;

        Totals(int unreadThreads, int unreadMentions) {
            this.unreadThreads = unreadThreads;
            this.unreadMentions = unreadMentions;
        }
    }

    /** Called on the UI thread when the account's followed threads or their unread state changed. */
    public interface Listener {
        void onThreadsChanged(int account);
    }

    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    public static void addListener(Listener listener) {
        listeners.addIfAbsent(listener);
    }

    public static void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private static void notifyListeners(int account) {
        AndroidUtilities.runOnUIThread(() -> {
            for (Listener l : listeners) {
                l.onThreadsChanged(account);
            }
        });
    }

    /** Unread followed threads of all teams (the chat list badge); zeros before the first load. */
    public static Totals totals(int account) {
        RelayAccount relayAccount = RelayAccount.get(account);
        if (relayAccount == null) {
            return new Totals(0, 0);
        }
        RelayThreads threads = of(relayAccount);
        return new Totals(threads.unreadThreads, threads.unreadMentions);
    }

    private static <T> void run(int account, Callback<T> callback, Job<T> job) {
        RelayAccount relayAccount = RelayAccount.get(account);
        if (relayAccount == null) {
            AndroidUtilities.runOnUIThread(() -> callback.done(null, "not logged in"));
            return;
        }
        RelayBridge.get(account).post(() -> {
            T result = null;
            String error = null;
            try {
                result = job.run(of(relayAccount));
            } catch (Throwable t) {
                RelayConfig.logError(TAG, "threads job failed", t);
                error = t.getMessage() != null ? t.getMessage() : t.toString();
            }
            final T r = result;
            final String e = error;
            AndroidUtilities.runOnUIThread(() -> callback.done(r, e));
        });
    }

    private interface Job<T> {
        T run(RelayThreads threads) throws Exception;
    }

    /** Loads the followed threads once (bridge start); later changes come from the WebSocket. */
    public static void prefetch(int account) {
        run(account, (Callback<Boolean>) (r, e) -> { }, threads -> {
            if (!threads.followedLoaded) {
                threads.refreshFollowed();
            }
            return true;
        });
    }

    public static void teams(int account, Callback<List<TeamInfo>> callback) {
        run(account, callback, threads -> {
            List<TeamInfo> result = new ArrayList<>();
            List<Team> teams = new ArrayList<>(threads.path.directory.get(60_000).teams);
            Collections.sort(teams, (a, b) -> a.displayName.compareToIgnoreCase(b.displayName));
            for (Team team : teams) {
                result.add(new TeamInfo(team.id, team.name, team.displayName != null && !team.displayName.isEmpty() ? team.displayName : team.name));
            }
            return result;
        });
    }

    /** The followed threads of a team (and of DMs/GMs), newest reply first; roots mapped so rows open their thread. */
    public static void loadThreads(int account, String teamId, Callback<List<ThreadRow>> callback) {
        run(account, callback, threads -> threads.rows(teamId));
    }

    private List<ThreadRow> rows(String teamId) throws Exception {
        ThreadList list = account.client.userThreads(teamId, new UserThreadsQuery().perPage(THREAD_LIST_PAGE));
        RelayDirectory.Snapshot snapshot = path.directory.get(60_000);
        List<ThreadRow> rows = new ArrayList<>();
        Set<String> userIds = new LinkedHashSet<>();
        for (UserThread thread : list.threads) {
            if (thread.deleteAt != 0 || thread.post == null) {
                continue;
            }
            followed.put(thread.id, thread);
            Post root = thread.post;
            Channel channel = snapshot.channels.get(root.channelId);
            if (channel == null || (channel.teamId != null && !channel.teamId.isEmpty() && !teamId.equals(channel.teamId))) {
                continue;
            }
            int localId = path.history.localIdOf(channel.id, root.id);
            if (localId == 0) {
                Post full = path.history.post(root.id);
                if (full == null) {
                    continue;
                }
                localId = path.history.mapSingle(channel.id, full);
            }
            ThreadRow row = new ThreadRow();
            row.rootPostId = root.id;
            row.dialogId = path.directory.dialogId(channel);
            row.rootMessageId = localId;
            row.channelTitle = channelTitle(channel, snapshot);
            String sender = path.convert.senderOf(root, channel.isDirect());
            userIds.add(sender);
            row.authorId = account.userTlId(sender);
            row.text = previewText(root);
            row.replyCount = thread.replyCount;
            row.unreadReplies = thread.unreadReplies;
            row.unreadMentions = thread.unreadMentions;
            row.lastReplyAt = thread.lastReplyAt;
            path.repliers.fromParticipants(thread.id, thread.participants, false); // replier avatars
            for (org.telegram.relay.mattermost.model.User user : thread.participants) {
                if (user != null && user.id != null && !user.id.isEmpty() && row.participants.size() < 3) {
                    userIds.add(user.id);
                    row.participants.add(account.userTlId(user.id));
                }
            }
            rows.add(row);
        }
        Collections.sort(rows, (a, b) -> Long.compare(b.lastReplyAt, a.lastReplyAt));
        ArrayList<TLRPC.User> users = path.users(userIds);
        for (ThreadRow row : rows) {
            TLRPC.TL_user author = account.tlUserById(row.authorId);
            row.authorName = author != null ? org.telegram.messenger.UserObject.getUserName(author) : "";
        }
        AndroidUtilities.runOnUIThread(() -> org.telegram.messenger.MessagesController.getInstance(account.account).putUsers(users, false));
        recomputeTotals();
        return rows;
    }

    /** Plain text of a post for list rows (markdown rendered as text; files named when there is no text). */
    static String previewText(Post post) {
        String text;
        try {
            text = org.telegram.relay.mattermost.text.MarkdownToEntities.convert(RelayConvert.messageText(post), new org.telegram.relay.mattermost.text.MarkdownToEntities.Options()).getText();
        } catch (Throwable t) {
            text = RelayConvert.messageText(post);
        }
        text = text == null ? "" : text.trim();
        if (text.isEmpty() && !post.getFiles().isEmpty()) {
            text = post.getFiles().get(0).name;
        }
        return text.length() > 300 ? text.substring(0, 300) : text;
    }

    private String channelTitle(Channel channel, RelayDirectory.Snapshot snapshot) {
        if (channel.isDirect()) {
            String other = path.directory.dmUser(channel);
            TLRPC.TL_user user = other != null ? account.tlUser(other) : null;
            return user != null ? org.telegram.messenger.UserObject.getUserName(user) : channel.displayName;
        }
        String title = channel.displayName != null ? channel.displayName.trim() : "";
        return title.isEmpty() ? channel.name : title;
    }

    /** Follow or unfollow the thread of a message (a reply's root). Answers the new state. */
    public static void setFollowing(int account, long dialogId, int messageId, boolean follow, Callback<Boolean> callback) {
        run(account, callback, threads -> {
            String channelId = threads.path.directory.channelIdOf(dialogId);
            Post root = channelId != null ? threads.rootOf(channelId, messageId) : null;
            if (root == null) {
                throw new IllegalStateException("no thread");
            }
            String teamId = threads.teamOf(threads.path.directory.channel(channelId));
            threads.account.client.setThreadFollowing(teamId, root.id, follow);
            if (follow) {
                threads.refreshFollowed();
            } else if (threads.followed.remove(root.id) != null) {
                threads.recomputeTotals();
            }
            RelayConfig.log(TAG, (follow ? "followed " : "unfollowed ") + root.id);
            return follow;
        });
    }

    /** Marks a thread (by its root post) read now. */
    public static void markRead(int account, String rootPostId, Callback<Boolean> callback) {
        run(account, callback, threads -> {
            Post root = threads.path.history.post(rootPostId);
            if (root == null) {
                throw new IllegalStateException("no thread");
            }
            String teamId = threads.teamOf(threads.path.directory.channel(root.channelId));
            long now = System.currentTimeMillis();
            threads.account.client.markThreadRead(teamId, rootPostId, now);
            UserThread thread = threads.followed.get(rootPostId);
            if (thread != null) {
                thread.unreadReplies = 0;
                thread.unreadMentions = 0;
                thread.lastViewedAt = now;
            }
            threads.recomputeTotals();
            return true;
        });
    }

    /**
     * The thread root post id of a message, read synchronously (UI thread: the message menu): the id map is
     * SQLite, the post comes from the bridge's post cache. Null when unknown.
     */
    public static String rootPostIdOf(int account, long dialogId, int messageId) {
        RelayAccount relayAccount = RelayAccount.get(account);
        if (relayAccount == null || !RelayThreadsHooks.threadsIn(account, dialogId) || messageId <= 0) {
            return null;
        }
        try {
            String channelId = cachedChannelIdOf(relayAccount, dialogId);
            if (channelId == null) {
                return null;
            }
            MessageIdEntry entry = relayAccount.messageIdStore().entryByLocalId(channelId, messageId);
            if (entry == null || entry.isDeleted) {
                return null;
            }
            Post post = RelayReadPath.of(relayAccount).history.cachedPost(entry.serverPostId());
            if (post == null) {
                return null;
            }
            return post.isReply() ? post.rootId : (post.replyCount > 0 || of(relayAccount).followed.containsKey(post.id) ? post.id : null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** The Mattermost channel of a dialog without network access (UI thread): megagroups from the id map, DMs from the directory snapshot. */
    private static String cachedChannelIdOf(RelayAccount account, long dialogId) {
        if (dialogId < 0) {
            return account.db().mmId(RelayDatabase.KIND_CHANNEL, -dialogId);
        }
        String userId = account.db().mmId(RelayDatabase.KIND_USER, dialogId);
        RelayDirectory.Snapshot snapshot = RelayReadPath.of(account).directory.peek();
        return userId != null && snapshot != null ? snapshot.dmByUser.get(userId) : null;
    }

    /** The Mattermost team of a chat-list folder (teams are folders, A3), or null ("All"). */
    public static String teamIdOfFolder(int account, int filterId) {
        RelayAccount relayAccount = RelayAccount.get(account);
        if (relayAccount == null || filterId < 2) {
            return null;
        }
        try {
            return relayAccount.db().mmId(RelayDatabase.KIND_TEAM, filterId);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Whether the user follows the thread (null: the followed list is not loaded yet). */
    public static Boolean isFollowing(int account, String rootPostId) {
        RelayAccount relayAccount = RelayAccount.get(account);
        if (relayAccount == null || rootPostId == null) {
            return null;
        }
        RelayThreads threads = of(relayAccount);
        if (!threads.followedLoaded) {
            return null;
        }
        return threads.followed.containsKey(rootPostId);
    }
}
