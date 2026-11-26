package org.telegram.relay.bridge;

import org.telegram.relay.RelayConfig;
import org.telegram.relay.account.RelayAccount;
import org.telegram.relay.account.RelayDatabase;
import org.telegram.relay.account.RelayUsers;
import org.telegram.relay.mattermost.MattermostException;
import org.telegram.relay.mattermost.ids.MessageIdEntry;
import org.telegram.relay.mattermost.model.Channel;
import org.telegram.relay.mattermost.model.ChannelMember;
import org.telegram.relay.mattermost.model.ChannelStats;
import org.telegram.relay.mattermost.model.Post;
import org.telegram.relay.mattermost.model.PostList;
import org.telegram.relay.mattermost.model.Team;
import org.telegram.relay.mattermost.model.User;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Phase A3, the read path: chat list (dialogs, pinned = favourites, folders = teams), history,
 * message lookups, read marks, user profiles and file downloads. One instance per Mattermost session
 * ({@link #of}). Everything except {@code upload.getFile} runs on the account's serial bridge executor.
 */
final class RelayReadPath {
    private static final String TAG = RelayConfig.TAG_BRIDGE;
    /** Posts fetched per chat for the chat list (top message, read boundary). */
    static final int DIALOG_PAGE = 20;
    /** Posts on each side of the first unread one when the unread run is longer than {@link #DIALOG_PAGE}. */
    static final int UNREAD_CONTEXT = 20;

    private static final WeakHashMap<RelayAccount, RelayReadPath> instances = new WeakHashMap<>();
    private static final ExecutorService http = Executors.newFixedThreadPool(6, r -> new Thread(r, "RelayHttp"));

    static RelayReadPath of(RelayAccount account) {
        synchronized (instances) {
            RelayReadPath path = instances.get(account);
            if (path == null) {
                instances.put(account, path = new RelayReadPath(account));
            }
            return path;
        }
    }

    final RelayAccount account;
    final RelayDirectory directory;
    final RelayFiles files;
    final RelayHistory history;
    final RelayConvert convert;
    /** DM threads + replier avatars: the latest repliers of thread roots ("N Replies" avatars). */
    final RelayRepliers repliers;
    /** Chats this device marked read just now: their {@code channel_viewed} echo is not a read on another device. */
    final java.util.concurrent.ConcurrentHashMap<String, Long> viewedHere = new java.util.concurrent.ConcurrentHashMap<>();

    /** True when the chat was viewed from this app within {@code windowMs} (the WebSocket echoes our own view). */
    boolean viewedHereRecently(String channelId, long windowMs) {
        Long at = viewedHere.get(channelId);
        return at != null && System.currentTimeMillis() - at < windowMs;
    }

    /** Chats the client has a dialog for (megagroups need {@code updateChannel} first); null = unknown (pre-A4 state). */
    private Set<String> known;
    private final Set<Long> tooLongChannels = java.util.Collections.synchronizedSet(new java.util.HashSet<Long>());
    private static final String KV_KNOWN = "known_channels";

    private RelayReadPath(RelayAccount account) {
        this.account = account;
        this.directory = new RelayDirectory(account);
        this.files = new RelayFiles(account);
        this.history = new RelayHistory(account);
        this.convert = new RelayConvert(account, files);
        this.repliers = new RelayRepliers(account);
    }

    // MARK: - Users and chats

    /** TL users for Mattermost ids (loads missing users and stale presence first). */
    ArrayList<TLRPC.User> users(Collection<String> mmUserIds) throws MattermostException {
        Set<String> ids = new LinkedHashSet<>(mmUserIds);
        ids.add(account.session.userId);
        account.ensureUsers(ids);
        account.refreshStatuses(ids, 60_000);
        ArrayList<TLRPC.User> result = new ArrayList<>();
        for (String id : ids) {
            TLRPC.TL_user user = account.tlUser(id);
            if (user != null) {
                result.add(user);
            }
        }
        return result;
    }

    // MARK: - Messages

    /** TL messages for history units of one channel, newest first, with reply headers and counters. */
    ArrayList<TLRPC.Message> messages(Channel channel, List<RelayHistory.Unit> units, Collection<String> usersOut) throws Exception {
        Map<String, Integer> roots = history.rootIds(channel.id, units);
        Map<String, Integer> replyCounts = history.replyCounts(units);
        TLRPC.Peer peer = directory.peer(channel);
        boolean dm = channel.isDirect();
        boolean collapsed = RelayThreads.collapses(account, channel, RelayThreadsHooks.collapsedThreads(account.account));
        // DM threads + replier avatars: the latest repliers of the roots with replies (Telegram's recent_repliers).
        Map<String, List<String>> repliersByRoot = Collections.emptyMap();
        if (collapsed && !replyCounts.isEmpty()) {
            repliersByRoot = repliers.resolve(replyCounts.keySet(), http, history);
        }
        ArrayList<TLRPC.Message> result = new ArrayList<>(units.size());
        for (RelayHistory.Unit unit : units) {
            Post post = unit.layout.post;
            Integer rootId = post.isReply() ? roots.get(post.rootId) : null;
            Integer replies = replyCounts.get(post.id);
            List<String> latest = unit.part == 0 ? repliersByRoot.get(post.id) : null;
            if (latest != null) {
                usersOut.addAll(latest);
            }
            result.add(convert.message(unit.layout, unit.part, unit.localId, peer, dm, collapsed,
                    rootId != null ? rootId : 0, replies != null ? replies : 0, latest, usersOut));
        }
        return result;
    }

    // MARK: - Dialogs (§4)

    private static final class DialogRow {
        Channel channel;
        ChannelMember member;
        long dialogId;
        TLRPC.TL_dialog dialog;
        TLRPC.Message top;
        boolean pinned;
        /** The newest page's units, newest first (final regression: mentions of a chat that just appeared). */
        List<RelayHistory.Unit> units;
    }

    private Future<PostList> submit(java.util.concurrent.Callable<PostList> call) {
        return http.submit(call);
    }

    /** Builds dialog rows for {@code channels}: newest page per chat (in parallel), read boundary, counters. */
    private List<DialogRow> rows(RelayDirectory.Snapshot snapshot, Collection<Channel> channels, Set<String> usersOut) throws Exception {
        long started = System.currentTimeMillis();
        List<Channel> list = new ArrayList<>(channels);
        Map<String, Future<PostList>> latest = new HashMap<>();
        Map<String, Future<PostList>> unread = new HashMap<>();
        for (Channel channel : list) {
            latest.put(channel.id, submit(() -> account.client.posts(channel.id, org.telegram.relay.mattermost.PostsQuery.latest(DIALOG_PAGE))));
            ChannelMember member = snapshot.members.get(channel.id);
            if (member != null && channel.totalMsgCount - member.msgCount > DIALOG_PAGE - 2 && member.lastViewedAt > 0) {
                unread.put(channel.id, submit(() -> account.client.postsAroundUnread(channel.id, UNREAD_CONTEXT, UNREAD_CONTEXT, false)));
            }
        }
        List<DialogRow> rows = new ArrayList<>();
        for (Channel channel : list) {
            PostList page;
            try {
                page = latest.get(channel.id).get();
            } catch (java.util.concurrent.ExecutionException e) {
                RelayConfig.logError(TAG, "latest page of " + channel.id, e.getCause());
                if (e.getCause() instanceof MattermostException && ((MattermostException) e.getCause()).isUnauthorized()) {
                    throw (MattermostException) e.getCause();
                }
                continue;
            }
            history.remember(page);
            RelayHistory.Latest newest = history.latest(channel.id, page, DIALOG_PAGE);
            if (newest.units.isEmpty()) {
                continue;
            }
            ChannelMember member = snapshot.members.get(channel.id);
            List<Post> boundarySearch = new ArrayList<>(newest.posts);
            Future<PostList> unreadFuture = unread.get(channel.id);
            if (unreadFuture != null) {
                try {
                    PostList around = unreadFuture.get();
                    history.remember(around);
                    List<Post> run = around.getOrderedPosts();
                    int read = 0;
                    for (Post post : run) {
                        if (post.createAt <= member.lastViewedAt) {
                            read++;
                        }
                    }
                    history.ingestRun(channel.id, run, read < UNREAD_CONTEXT, run.size() - read < UNREAD_CONTEXT);
                    boundarySearch.addAll(run);
                } catch (java.util.concurrent.ExecutionException e) {
                    RelayConfig.logError(TAG, "posts around unread of " + channel.id, e.getCause());
                }
            }

            DialogRow row = new DialogRow();
            row.channel = channel;
            row.member = member;
            row.dialogId = directory.dialogId(channel);
            row.units = newest.units;
            RelayHistory.Unit newestUnit = newest.units.get(0);
            RelayHistory.Unit topUnit = previewUnit(channel, newest.units); // Polish 1: CRT previews the newest root
            ArrayList<TLRPC.Message> top = messages(channel, Collections.singletonList(topUnit), usersOut);
            row.top = top.get(0);

            TLRPC.TL_dialog d = new TLRPC.TL_dialog();
            d.peer = directory.peer(channel);
            d.top_message = topUnit.localId;
            int unreadCount = RelayDirectory.unreadCount(snapshot, channel, member);
            int readInbox = newestUnit.localId;
            if (unreadCount > 0 && member != null) {
                readInbox = 1;
                Post boundary = null;
                for (Post post : boundarySearch) {
                    if (post.createAt <= member.lastViewedAt && (boundary == null || post.createAt > boundary.createAt
                            || (post.createAt == boundary.createAt && post.id.compareTo(boundary.id) > 0))) {
                        boundary = post;
                    }
                }
                if (boundary != null) {
                    int id = history.lastLocalIdOf(channel.id, boundary);
                    if (id != 0) {
                        readInbox = id;
                    }
                }
            }
            d.read_inbox_max_id = readInbox;
            // Mattermost has no read receipts: outgoing messages keep one check (1, not 0: 0 makes the
            // client reload the read state with getPeerDialogs).
            d.read_outbox_max_id = 1;
            d.unread_count = unreadCount;
            d.unread_mentions_count = RelayDirectory.mentionCount(snapshot, channel, member);
            d.unread_reactions_count = 0;
            d.notify_settings = notifySettings(member);
            if (row.dialogId < 0) {
                d.pts = account.db().pts(RelayDatabase.channelBox(-row.dialogId));
                d.flags |= TLObject.FLAG_0;
            }
            row.pinned = snapshot.favorites.contains(channel.id);
            d.pinned = row.pinned;
            row.dialog = d;
            rows.add(row);
            String dmUser = directory.dmUser(channel);
            if (dmUser != null) {
                usersOut.add(dmUser);
            }
        }
        RelayConfig.log(TAG, "dialog rows: " + rows.size() + " chats in " + (System.currentTimeMillis() - started) + " ms");
        return rows;
    }

    /** DM threads: the bridge's dialog rows (dialog + preview message, users) of these chats, as {@code getDialogs} answers them. */
    TLRPC.TL_messages_dialogs dialogsOf(Collection<Channel> channels) throws Exception {
        RelayDirectory.Snapshot snapshot = directory.get(5_000);
        Set<String> users = new LinkedHashSet<>();
        List<DialogRow> rows = rows(snapshot, channels, users);
        TLRPC.TL_messages_dialogs res = new TLRPC.TL_messages_dialogs();
        for (DialogRow row : rows) {
            row.dialog.id = row.dialogId;
            res.dialogs.add(row.dialog);
            res.messages.add(row.top);
        }
        res.users.addAll(users(users));
        res.count = res.dialogs.size();
        return res;
    }

    /**
     * Polish 1: the chat list's preview (dialog {@code top_message}). With collapsed threads a chat previews its
     * newest root post, as iOS does (replies live in their threads): megagroups and, since the DM threads change, DMs
     * (the self-DM keeps replies inline, so its newest post). Falls back to the newest message when the page has no root.
     */
    RelayHistory.Unit previewUnit(Channel channel, List<RelayHistory.Unit> newestFirst) {
        RelayHistory.Unit newest = newestFirst.get(0);
        if (!RelayThreads.collapses(account, channel, RelayThreadsHooks.collapsedThreads(account.account))) {
            return newest;
        }
        for (RelayHistory.Unit unit : newestFirst) {
            if (!unit.layout.post.isReply()) {
                return unit;
            }
        }
        return newest;
    }

    static TLRPC.TL_peerNotifySettings notifySettings(ChannelMember member) {
        TLRPC.TL_peerNotifySettings settings = RelayStubs.notifySettings();
        if (member != null && member.isMuted()) {
            settings.mute_until = Integer.MAX_VALUE;
            settings.flags |= TLObject.FLAG_2;
        }
        return settings;
    }

    private void fill(TLRPC.messages_Dialogs res, List<DialogRow> rows, Set<String> users) throws MattermostException {
        for (DialogRow row : rows) {
            res.dialogs.add(row.dialog);
            res.messages.add(row.top);
            if (!row.channel.isDirect()) {
                res.chats.add(directory.tlChannel(row.channel));
            }
        }
        res.users.addAll(users(users));
    }

    TLObject getDialogs(TLRPC.TL_messages_getDialogs req) throws Exception {
        if (req.folder_id != 0) {
            return new TLRPC.TL_messages_dialogs();
        }
        RelayDirectory.Snapshot snapshot = directory.get(5_000);
        Set<String> users = new LinkedHashSet<>();
        List<DialogRow> all = rows(snapshot, snapshot.channels.values(), users);
        List<String> listed = new ArrayList<>();
        for (DialogRow row : all) {
            listed.add(row.channel.id);
        }
        noteKnown(listed);
        List<DialogRow> candidates = new ArrayList<>();
        for (DialogRow row : all) {
            if (!(req.exclude_pinned && row.pinned)) {
                row.dialog.pinned = false;
                candidates.add(row);
            }
        }
        Collections.sort(candidates, (a, b) -> {
            if (a.top.date != b.top.date) {
                return Integer.compare(b.top.date, a.top.date);
            }
            return Long.compare(b.dialogId, a.dialogId);
        });
        // Strictly older than the offset (date, then dialog id).
        List<DialogRow> remaining = new ArrayList<>();
        boolean hasOffset = req.offset_date != 0 || req.offset_id != 0;
        long offsetDid = hasOffset ? directory.dialogIdOf(req.offset_peer) : 0;
        for (DialogRow row : candidates) {
            if (!hasOffset || row.top.date < req.offset_date || (row.top.date == req.offset_date && row.dialogId < offsetDid)) {
                remaining.add(row);
            }
        }
        List<DialogRow> page = remaining.subList(0, Math.min(remaining.size(), Math.max(1, req.limit)));
        TLRPC.messages_Dialogs res;
        if (page.size() < remaining.size()) {
            TLRPC.TL_messages_dialogsSlice slice = new TLRPC.TL_messages_dialogsSlice();
            slice.count = candidates.size();
            res = slice;
        } else {
            res = new TLRPC.TL_messages_dialogs();
        }
        fill(res, page, users);
        return res;
    }

    /** {@code messages.peerDialogs} for the pinned (favourite) chats or the given peers, with the state. */
    TLObject peerDialogs(RelayBridge bridge, Collection<Channel> channels, boolean pinnedOnly) throws Exception {
        RelayDirectory.Snapshot snapshot = directory.get(5_000);
        Set<String> users = new LinkedHashSet<>();
        List<DialogRow> rows = rows(snapshot, channels, users);
        List<String> listed = new ArrayList<>();
        for (DialogRow row : rows) {
            listed.add(row.channel.id);
        }
        noteKnown(listed);
        TLRPC.TL_messages_peerDialogs res = new TLRPC.TL_messages_peerDialogs();
        for (DialogRow row : rows) {
            if (pinnedOnly) {
                row.dialog.pinned = true;
            }
            res.dialogs.add(row.dialog);
            res.messages.add(row.top);
            if (!row.channel.isDirect()) {
                res.chats.add(directory.tlChannel(row.channel));
            }
        }
        res.users.addAll(users(users));
        res.state = bridge.currentState(account);
        return res;
    }

    TLObject getPinnedDialogs(RelayBridge bridge, TLRPC.TL_messages_getPinnedDialogs req) throws Exception {
        RelayDirectory.Snapshot snapshot = directory.get(5_000);
        List<Channel> pinned = new ArrayList<>();
        if (req.folder_id == 0) {
            for (String id : snapshot.favorites) {
                Channel channel = snapshot.channels.get(id);
                if (channel != null) {
                    pinned.add(channel);
                }
            }
        }
        RelayFavorites.of(account).noteDelivered(snapshot.favorites); // polish 2a
        return peerDialogs(bridge, pinned, true);
    }

    TLObject getPeerDialogs(RelayBridge bridge, TLRPC.TL_messages_getPeerDialogs req) throws Exception {
        List<Channel> channels = new ArrayList<>();
        for (TLRPC.InputDialogPeer peer : req.peers) {
            if (peer instanceof TLRPC.TL_inputDialogPeer) {
                String channelId = directory.channelIdOf(((TLRPC.TL_inputDialogPeer) peer).peer);
                if (channelId != null) {
                    Channel channel = directory.channel(channelId);
                    if (channel != null) {
                        channels.add(channel);
                    }
                }
            }
        }
        return peerDialogs(bridge, channels, false);
    }

    /** Teams as folders (§4.4): "All" first, then one folder per team with its channels, every GM, and DMs via the user categories. */
    TLObject getDialogFilters() throws Exception {
        RelayDirectory.Snapshot snapshot = directory.get(5_000);
        TLRPC.TL_messages_dialogFilters res = new TLRPC.TL_messages_dialogFilters();
        res.filters.add(new TLRPC.TL_dialogFilterDefault());
        List<Team> teams = new ArrayList<>(snapshot.teams);
        Collections.sort(teams, (a, b) -> a.displayName.compareToIgnoreCase(b.displayName));
        for (Team team : teams) {
            TLRPC.TL_dialogFilter filter = new TLRPC.TL_dialogFilter();
            filter.id = (int) account.db().tlId(RelayDatabase.KIND_TEAM, team.id);
            filter.title = new TLRPC.TL_textWithEntities();
            filter.title.text = team.displayName != null && !team.displayName.isEmpty() ? team.displayName : team.name;
            filter.contacts = true;
            filter.non_contacts = true;
            filter.groups = false;
            filter.broadcasts = false;
            filter.bots = false;
            Set<String> pinned = new LinkedHashSet<>();
            for (String id : snapshot.favorites) {
                Channel channel = snapshot.channels.get(id);
                if (channel != null && (channel.isDirect() || channel.isGroup() || team.id.equals(channel.teamId))) {
                    pinned.add(id);
                    filter.pinned_peers.add(directory.inputPeer(channel));
                }
            }
            for (Channel channel : RelayDirectory.sortedChannels(snapshot)) {
                if (channel.isDirect() || pinned.contains(channel.id)) {
                    continue;
                }
                if (channel.isGroup() || team.id.equals(channel.teamId)) {
                    filter.include_peers.add(directory.inputPeer(channel));
                }
            }
            res.filters.add(filter);
        }
        return res;
    }

    // MARK: - Known chats (A4)

    private synchronized Set<String> knownSet() {
        if (known == null) {
            String stored = account.db().kv(KV_KNOWN);
            if (stored != null) {
                known = new LinkedHashSet<>();
                for (String id : stored.split(",")) {
                    if (!id.isEmpty()) {
                        known.add(id);
                    }
                }
            }
        }
        return known;
    }

    /** True when the client has (or is getting) a dialog for the chat. */
    synchronized boolean isKnown(String channelId) {
        Set<String> set = knownSet();
        return set == null || set.contains(channelId);
    }

    synchronized List<String> knownChannels() {
        Set<String> set = knownSet();
        return set == null ? new ArrayList<String>() : new ArrayList<>(set);
    }

    synchronized void noteKnown(Collection<String> channelIds) {
        Set<String> set = knownSet();
        if (set == null) {
            set = known = new LinkedHashSet<>();
        }
        if (set.addAll(channelIds)) {
            account.db().putKv(KV_KNOWN, android.text.TextUtils.join(",", set));
        }
    }

    synchronized void forgetKnown(String channelId) {
        Set<String> set = knownSet();
        if (set != null && set.remove(channelId)) {
            account.db().putKv(KV_KNOWN, android.text.TextUtils.join(",", set));
        }
    }

    /** A channel whose catch-up was too large: its next getChannelDifference is answered "too long". */
    void markTooLong(long channelTlId) {
        tooLongChannels.add(channelTlId);
    }

    boolean consumeTooLong(long channelTlId) {
        return tooLongChannels.remove(channelTlId);
    }

    /** {@code channelDifferenceTooLong} for one megagroup: its dialog and newest message (new or reloaded chat). */
    private final java.util.Map<String, Long> announcedLive = new java.util.concurrent.ConcurrentHashMap<>();

    void noteAnnouncedLive(String channelId) {
        announcedLive.put(channelId, System.currentTimeMillis());
    }

    private boolean consumeAnnouncedLive(String channelId) {
        Long at = announcedLive.remove(channelId);
        return at != null && System.currentTimeMillis() - at < 60_000;
    }

    /** Unread posts of {@code row}'s newest page that mention the user (roots only with collapsed threads), as the badge counts. */
    private int mappedMentions(DialogRow row, RelayDirectory.Snapshot snapshot) throws Exception {
        if (row.channel.isDirect() || row.channel.isGroup()) {
            return 0; // no mention badge for DMs and GMs (RelayDirectory.mentionCount)
        }
        List<RelayHistory.Unit> unread = new ArrayList<>();
        for (RelayHistory.Unit unit : row.units) {
            if (unit.localId > row.dialog.read_inbox_max_id && !(snapshot.collapsedThreads && unit.layout.post.isReply())) {
                unread.add(unit);
            }
        }
        Set<String> posts = new HashSet<>();
        ArrayList<TLRPC.Message> messages = messages(row.channel, unread, new LinkedHashSet<>());
        for (int i = 0; i < messages.size() && i < unread.size(); i++) {
            if (messages.get(i).mentioned && !messages.get(i).out) {
                posts.add(unread.get(i).layout.post.id);
            }
        }
        return posts.size();
    }

    TLObject channelTooLong(Channel channel) throws Exception {
        RelayDirectory.Snapshot snapshot = directory.get(5_000);
        Set<String> users = new LinkedHashSet<>();
        List<DialogRow> rows = rows(snapshot, Collections.singletonList(channel), users);
        TLRPC.TL_updates_channelDifferenceTooLong res = new TLRPC.TL_updates_channelDifferenceTooLong();
        res.isFinal = true;
        res.timeout = 30;
        res.flags |= TLObject.FLAG_1;
        if (!rows.isEmpty() && consumeAnnouncedLive(channel.id)) {
            // Polish 2a (QA-16): a chat that just appeared live (joined / added) counts the mentions of later posts
            // live (mentioned + media_unread); the server's count here would count those twice. The posts already
            // mapped now are never sent live again (or were sent before the client knew the chat, and dropped), so
            // they are counted here: the unread ones that mention the user (final regression: the "added to the
            // channel" post was lost when its live event came before this answer).
            rows.get(0).dialog.unread_mentions_count = mappedMentions(rows.get(0), snapshot);
        }
        if (rows.isEmpty()) {
            TLRPC.TL_dialog d = new TLRPC.TL_dialog();
            d.peer = directory.peer(channel);
            d.read_outbox_max_id = 1;
            d.notify_settings = notifySettings(snapshot.members.get(channel.id));
            d.pts = account.db().pts(RelayDatabase.channelBox(-directory.dialogId(channel)));
            d.flags |= TLObject.FLAG_0;
            res.dialog = d;
        } else {
            res.dialog = rows.get(0).dialog;
            res.messages.add(rows.get(0).top);
        }
        res.chats.add(directory.tlChannel(channel));
        res.users.addAll(users(users));
        noteKnown(Collections.singletonList(channel.id));
        return res;
    }

    // MARK: - History (§5)

    TLObject getHistory(TLRPC.TL_messages_getHistory req) throws Exception {
        String channelId = directory.channelIdOf(req.peer);
        if (channelId == null) {
            return emptyMessages(req.peer);
        }
        Channel channel = directory.channel(channelId);
        long started = System.currentTimeMillis();
        // A7a: collapsed reply threads hide replies from a main history (megagroups and DMs); load type 4 (jump to date).
        boolean rootsOnly = RelayThreads.collapses(account, channel, directory.any().collapsedThreads);
        List<RelayHistory.Unit> units = req.offset_date != 0 && req.offset_id == 0
                ? RelayJumpDate.window(this, channel, req.offset_date, req.add_offset, req.limit, req.max_id, req.min_id, rootsOnly)
                : history.window(channelId, req.offset_id, req.add_offset, req.limit, req.max_id, req.min_id, rootsOnly);
        TLObject res = messagesResponse(channel, units);
        RelayConfig.log(TAG, String.format(java.util.Locale.US, "getHistory %s offset=%d add=%d limit=%d -> %d msgs [%d..%d] in %d ms",
                channel.name, req.offset_id, req.add_offset, req.limit, units.size(),
                units.isEmpty() ? 0 : units.get(units.size() - 1).localId, units.isEmpty() ? 0 : units.get(0).localId,
                System.currentTimeMillis() - started));
        return res;
    }

    private TLObject emptyMessages(TLRPC.InputPeer peer) {
        if (peer instanceof TLRPC.TL_inputPeerChannel) {
            TLRPC.TL_messages_channelMessages res = new TLRPC.TL_messages_channelMessages();
            res.pts = account.db().pts(RelayDatabase.channelBox(peer.channel_id));
            return res;
        }
        return new TLRPC.TL_messages_messages();
    }

    TLRPC.messages_Messages messagesResponse(Channel channel, List<RelayHistory.Unit> units) throws Exception {
        Set<String> userIds = new LinkedHashSet<>();
        ArrayList<TLRPC.Message> messages = messages(channel, units, userIds);
        String dmUser = directory.dmUser(channel);
        if (dmUser != null) {
            userIds.add(dmUser);
        }
        TLRPC.messages_Messages res;
        if (channel.isDirect()) {
            res = new TLRPC.TL_messages_messages();
        } else {
            TLRPC.TL_messages_channelMessages cm = new TLRPC.TL_messages_channelMessages();
            cm.pts = account.db().pts(RelayDatabase.channelBox(-directory.dialogId(channel)));
            cm.count = (int) Math.max(messages.size(), Math.min(Integer.MAX_VALUE, channel.totalMsgCount));
            cm.chats.add(directory.tlChannel(channel));
            res = cm;
        }
        res.messages.addAll(messages);
        res.users.addAll(users(userIds));
        return res;
    }

    /** {@code channels.getMessages} / peer-tagged {@code messages.getMessages}: messageEmpty for unknown ids. */
    TLObject getMessages(String channelId, List<Integer> ids) throws Exception {
        if (channelId == null) {
            TLRPC.TL_messages_messages res = new TLRPC.TL_messages_messages();
            for (Integer id : ids) {
                res.messages.add(messageEmpty(id));
            }
            return res;
        }
        Channel channel = directory.channel(channelId);
        List<RelayHistory.Unit> units = new ArrayList<>();
        List<Integer> missing = new ArrayList<>();
        for (Integer id : ids) {
            RelayHistory.Unit unit = history.unitAt(channelId, id);
            if (unit != null) {
                units.add(unit);
            } else {
                missing.add(id);
            }
        }
        TLRPC.messages_Messages res = messagesResponse(channel, units);
        for (Integer id : missing) {
            res.messages.add(messageEmpty(id));
        }
        return res;
    }

    private static TLRPC.TL_messageEmpty messageEmpty(int id) {
        TLRPC.TL_messageEmpty empty = new TLRPC.TL_messageEmpty();
        empty.id = id;
        return empty;
    }

    // MARK: - Read state (§5.6)

    /** Marks a chat read on the server ({@code POST /channels/members/me/view}). */
    void markRead(String channelId) throws MattermostException {
        if (channelId == null) {
            return;
        }
        account.client.viewChannel(channelId);
        viewedHere.put(channelId, System.currentTimeMillis());
        directory.invalidate();
        RelayConfig.log(TAG, "viewed " + channelId);
    }

    // MARK: - Chats and users

    TLObject getChannels(TLRPC.TL_channels_getChannels req) throws Exception {
        TLRPC.TL_messages_chats res = new TLRPC.TL_messages_chats();
        for (TLRPC.InputChannel input : req.id) {
            String channelId = account.db().mmId(RelayDatabase.KIND_CHANNEL, input.channel_id);
            if (channelId != null) {
                Channel channel = directory.channel(channelId);
                if (channel != null) {
                    res.chats.add(directory.tlChannel(channel));
                }
            }
        }
        return res;
    }

    TLObject getFullChannel(TLRPC.TL_channels_getFullChannel req) throws Exception {
        String channelId = account.db().mmId(RelayDatabase.KIND_CHANNEL, req.channel.channel_id);
        if (channelId == null) {
            return RelayTl.error(400, "CHANNEL_INVALID");
        }
        Channel channel = directory.channel(channelId);
        RelayDirectory.Snapshot snapshot = directory.any();
        ChannelMember member = snapshot.members.get(channelId);
        TLRPC.TL_channelFull full = new TLRPC.TL_channelFull();
        full.id = req.channel.channel_id;
        String about = channel.purpose != null && !channel.purpose.trim().isEmpty() ? channel.purpose.trim() : (channel.header != null ? channel.header.trim() : "");
        full.about = about;
        try {
            ChannelStats stats = account.client.channelStats(channelId);
            full.participants_count = (int) stats.memberCount;
            full.flags |= TLObject.FLAG_0;
        } catch (MattermostException e) {
            RelayConfig.logError(TAG, "channel stats", e);
        }
        full.read_inbox_max_id = 0;
        full.read_outbox_max_id = 1;
        full.unread_count = RelayDirectory.unreadCount(snapshot, channel, member);
        full.chat_photo = new TLRPC.TL_photoEmpty();
        full.notify_settings = notifySettings(member);
        full.pts = account.db().pts(RelayDatabase.channelBox(full.id));
        full.can_view_participants = true;
        RelayFinding.fillFull(account, channel, full); // A7b: pinned message, purpose/header cache
        TLRPC.TL_messages_chatFull res = new TLRPC.TL_messages_chatFull();
        res.full_chat = full;
        TLRPC.TL_channel chat = directory.tlChannel(channel);
        // A8: the channel's live call (Telegram's join panel and video chat).
        org.telegram.relay.calls.RelayGroupSession.decorateFullChannel(account.account, channelId, full, chat);
        res.chats.add(chat);
        return res;
    }

    TLObject getFullUser(TLRPC.TL_users_getFullUser req) throws Exception {
        TLRPC.TL_user user = RelayHandlers.resolveUser(account, req.id);
        if (user == null) {
            return RelayTl.error(400, "USER_ID_INVALID");
        }
        String mmId = account.db().mmId(RelayDatabase.KIND_USER, user.id);
        if (mmId == null) {
            mmId = account.session.userId;
        }
        if (!RelayAccount.isSynthetic(mmId)) {
            account.refreshUsers(Collections.singletonList(mmId), 60_000);
            account.refreshStatuses(Collections.singletonList(mmId), 0);
        }
        User mm = account.cachedUser(mmId);
        TLRPC.TL_userFull full = new TLRPC.TL_userFull();
        full.id = user.id;
        full.settings = new TLRPC.TL_peerSettings();
        String dmChannel = directory.any().dmByUser.get(mmId);
        ChannelMember member = dmChannel != null ? directory.any().members.get(dmChannel) : null;
        full.notify_settings = notifySettings(member);
        full.common_chats_count = 0;
        String about = about(mm);
        if (!about.isEmpty()) {
            full.about = about;
            full.flags |= TLObject.FLAG_1;
        }
        TLRPC.TL_photo photo = mm != null ? files.profilePhoto(mm) : null;
        if (photo != null) {
            full.profile_photo = photo;
            full.flags |= TLObject.FLAG_2;
        }
        // A8: DM users are callable over the Relay calls plugin (not bots, deactivated users or Saved Messages).
        if (org.telegram.relay.RelayFeatures.RELAY_CALLS && mm != null && !mm.isBot && !mm.isDeactivated() && !mmId.equals(account.session.userId)) {
            full.phone_calls_available = true;
            full.video_calls_available = true;
            full.phone_calls_private = false;
        }
        // Polish 2a (QA-11): Mattermost pins posts in DMs too (iOS H5): Telegram's "Pin" in a private chat.
        full.can_pin_message = dmChannel != null || mmId.equals(account.session.userId);
        TLRPC.TL_users_userFull res = new TLRPC.TL_users_userFull();
        res.full_user = full;
        TLRPC.TL_user fresh = account.tlUser(mmId);
        res.users.add(fresh != null ? fresh : user);
        return res;
    }

    /** Bio: the position and the nickname ("iOS Engineer · Al"); bots show their description. */
    static String about(User user) {
        if (user == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        if (user.position != null && !user.position.trim().isEmpty()) {
            parts.add(user.position.trim());
        }
        if (user.nickname != null && !user.nickname.trim().isEmpty()) {
            parts.add(user.nickname.trim());
        }
        if (parts.isEmpty() && user.botDescription != null && !user.botDescription.trim().isEmpty()) {
            parts.add(user.botDescription.trim());
        }
        return android.text.TextUtils.join(" · ", parts);
    }

    TLObject getUserPhotos(TLRPC.TL_photos_getUserPhotos req) throws Exception {
        TLRPC.TL_photos_photos res = new TLRPC.TL_photos_photos();
        TLRPC.TL_user user = RelayHandlers.resolveUser(account, req.user_id);
        if (user != null && req.offset == 0) {
            String mmId = account.db().mmId(RelayDatabase.KIND_USER, user.id);
            User mm = mmId != null ? account.cachedUser(mmId) : null;
            TLRPC.TL_photo photo = mm != null ? files.profilePhoto(mm) : null;
            if (photo != null) {
                res.photos.add(photo);
            }
            res.users.add(user);
        }
        return res;
    }

    TLObject resolveUsername(TLRPC.TL_contacts_resolveUsername req) throws Exception {
        String name = req.username == null ? "" : req.username.replace("@", "").trim();
        User user;
        try {
            user = account.client.userByUsername(name);
        } catch (MattermostException e) {
            if (e.isNotFound()) {
                return RelayTl.error(400, "USERNAME_NOT_OCCUPIED");
            }
            throw e;
        }
        long tlId = account.rememberUser(user);
        TLRPC.TL_contacts_resolvedPeer res = new TLRPC.TL_contacts_resolvedPeer();
        TLRPC.TL_peerUser peer = new TLRPC.TL_peerUser();
        peer.user_id = tlId;
        res.peer = peer;
        res.users.addAll(users(Collections.singletonList(user.id)));
        return res;
    }

    TLObject unreadMentions(TLRPC.TL_messages_getUnreadMentions req) throws Exception {
        String channelId = directory.channelIdOf(req.peer);
        TLRPC.TL_messages_channelMessages res = new TLRPC.TL_messages_channelMessages();
        if (channelId != null) {
            RelayDirectory.Snapshot snapshot = directory.any();
            Channel channel = snapshot.channels.get(channelId);
            if (channel != null) {
                res.count = RelayDirectory.mentionCount(snapshot, channel, snapshot.members.get(channelId));
                res.pts = account.db().pts(RelayDatabase.channelBox(-directory.dialogId(channel)));
            }
        }
        return res;
    }

    // MARK: - Debug

    /** The bridge's id map of a chat (debug dump): {@code local_id post_id part deleted} per line. */
    String dumpDialog(long dialogId) throws MattermostException {
        String channelId = directory.channelIdOf(dialogId);
        StringBuilder text = new StringBuilder();
        text.append("dialog=").append(dialogId).append(" channel=").append(channelId).append(" renumbers=").append(history.renumbers.get()).append('\n');
        if (channelId == null) {
            return text.toString();
        }
        for (MessageIdEntry entry : account.messageIdStore().allEntries(channelId)) {
            text.append("map\t").append(entry.localId).append('\t').append(entry.serverPostId()).append('\t').append(entry.partIndex()).append('\t').append(entry.isDeleted ? 1 : 0).append('\n');
        }
        for (org.telegram.relay.mattermost.ids.HistoryIsland island : account.messageIdStore().islands(channelId)) {
            text.append("island\t").append(island).append('\n');
        }
        return text.toString();
    }
}
