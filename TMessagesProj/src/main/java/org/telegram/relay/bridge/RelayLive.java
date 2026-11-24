package org.telegram.relay.bridge;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.Utilities;
import org.telegram.relay.RelayConfig;
import org.telegram.relay.account.RelayAccount;
import org.telegram.relay.account.RelayDatabase;
import org.telegram.relay.account.RelayUsers;
import org.telegram.relay.mattermost.MattermostException;
import org.telegram.relay.mattermost.PostsQuery;
import org.telegram.relay.mattermost.ids.IngestResult;
import org.telegram.relay.mattermost.ids.MessageIdEntry;
import org.telegram.relay.mattermost.ids.PostAllocation;
import org.telegram.relay.mattermost.ids.PostOrderKey;
import org.telegram.relay.mattermost.model.Channel;
import org.telegram.relay.mattermost.model.ChannelMember;
import org.telegram.relay.mattermost.model.Post;
import org.telegram.relay.mattermost.model.PostList;
import org.telegram.relay.mattermost.model.Reaction;
import org.telegram.relay.mattermost.model.User;
import org.telegram.relay.mattermost.model.UserStatus;
import org.telegram.relay.mattermost.ws.ConnectionInfo;
import org.telegram.relay.mattermost.ws.ConnectionState;
import org.telegram.relay.mattermost.ws.MattermostWebSocket;
import org.telegram.relay.mattermost.ws.WebSocketEvent;
import org.telegram.relay.mattermost.ws.WebSocketListener;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_update;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Phase A4, the live path: one Mattermost WebSocket per account, its events turned into TL updates
 * ({@link RelayUpdates}), and REST catch-up after a gap.
 *
 * <ul>
 *   <li>Started with the bridge (login or boot), stopped on logout. Reconnects with backoff
 *       ({@code MattermostWebSocket}); Android network changes drop or restart it.</li>
 *   <li>Connection state → {@code ConnectionsManager.onConnectionStateChanged}: "Connecting…" while
 *       the socket is down, "Waiting for network…" without a network; "Updating…" comes from the
 *       client's own {@code getDifference} while the bridge catches up.</li>
 *   <li>A connection that was not resumed (or the first one of a process) sets {@link #catchUpNeeded}
 *       and asks the client for {@code getDifference}; the bridge answers it after a REST catch-up
 *       (changed channels since {@code sync_at}) written to the update log, or
 *       {@code differenceTooLong} when the gap is too large.</li>
 *   <li>Every event is handled on the account's serial bridge executor, like requests, so pts order
 *       is delivery order and our own sends (mapped before their {@code posted} echo is handled) are
 *       never shown twice.</li>
 * </ul>
 */
final class RelayLive implements WebSocketListener {
    private static final String TAG = RelayConfig.TAG_BRIDGE;
    static final String KV_SYNC_AT = "sync_at";
    /** Catch-up looks this far before {@code sync_at} (clock skew between device and server). */
    private static final long SYNC_MARGIN_MS = 120_000;
    /** Older gaps are answered with differenceTooLong (re-list). */
    private static final long MAX_CATCH_UP_MS = 2L * 24 * 3600 * 1000;
    /** More changed posts than this in one channel: that channel is reloaded instead. */
    private static final int CHANNEL_CATCH_UP_LIMIT = 200;
    /** Presence poll / housekeeping interval. */
    private static final int TICK_MS = 15_000;

    private static final WeakHashMap<RelayAccount, RelayLive> instances = new WeakHashMap<>();

    static RelayLive of(RelayBridge bridge, RelayAccount account) {
        synchronized (instances) {
            RelayLive live = instances.get(account);
            if (live == null) {
                instances.put(account, live = new RelayLive(bridge, account));
            }
            return live;
        }
    }

    /** The live path of an account if it was created, else null. */
    static RelayLive peek(RelayAccount account) {
        synchronized (instances) {
            return instances.get(account);
        }
    }

    final RelayBridge bridge;
    final RelayAccount account;
    final RelayReadPath path;
    final RelayUpdates updates;

    private MattermostWebSocket socket;
    private volatile boolean started;
    volatile boolean networkAvailable = true;
    volatile boolean catchUpNeeded = true;
    private volatile boolean connected;
    private volatile long lastSyncWrite;
    private volatile long lastEventAt;
    private ConnectivityManager.NetworkCallback networkCallback;
    private Runnable keepAlive;
    /** Post ids the write path is creating right now (their {@code posted} echo is ignored). */
    final Set<String> pendingPostIds = Collections.synchronizedSet(new HashSet<String>());
    /** Listeners woken when the socket connects or the network comes back (offline sends). */
    private final List<Runnable> onlineListeners = new ArrayList<>();

    private RelayLive(RelayBridge bridge, RelayAccount account) {
        this.bridge = bridge;
        this.account = account;
        this.path = RelayReadPath.of(account);
        this.updates = new RelayUpdates(bridge, account, path);
    }

    // MARK: - Lifecycle

    synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        updates.prune();
        MattermostWebSocket.Configuration config = new MattermostWebSocket.Configuration();
        config.logger = message -> RelayConfig.log(TAG, "ws: " + message);
        socket = account.client.createWebSocket(config, this);
        registerNetwork();
        if (networkAvailable) {
            setConnectionState(ConnectionsManager.ConnectionStateConnecting);
            socket.connect();
        } else {
            setConnectionState(ConnectionsManager.ConnectionStateWaitingForNetwork);
        }
        keepAlive = new Runnable() {
            @Override
            public void run() {
                if (!started) {
                    return;
                }
                bridge.post(RelayLive.this::tick);
                Utilities.stageQueue.postRunnable(this, TICK_MS);
            }
        };
        Utilities.stageQueue.postRunnable(keepAlive, TICK_MS);
        RelayConfig.log(TAG, "live: started account=" + account.account + " network=" + networkAvailable);
    }

    synchronized void stop() {
        if (!started) {
            return;
        }
        started = false;
        if (keepAlive != null) {
            Utilities.stageQueue.cancelRunnable(keepAlive);
            keepAlive = null;
        }
        unregisterNetwork();
        if (socket != null) {
            socket.shutdown();
            socket = null;
        }
        connected = false;
        RelayConfig.log(TAG, "live: stopped account=" + account.account);
    }

    boolean isOnline() {
        return started && networkAvailable && connected;
    }

    MattermostWebSocket socket() {
        return socket;
    }

    void addOnlineListener(Runnable listener) {
        synchronized (onlineListeners) {
            onlineListeners.add(listener);
        }
    }

    private void fireOnline() {
        List<Runnable> copy;
        synchronized (onlineListeners) {
            copy = new ArrayList<>(onlineListeners);
        }
        for (Runnable r : copy) {
            try {
                r.run();
            } catch (Throwable t) {
                RelayConfig.logError(TAG, "online listener", t);
            }
        }
    }

    /** Debug: drops the socket; {@code resume=false} also forgets the resume state (forces a catch-up). */
    void debugReconnect(boolean resume) {
        MattermostWebSocket s = socket;
        if (s == null) {
            return;
        }
        if (!resume) {
            s.resetResumeState();
        }
        s.reconnectNow();
    }

    private void setConnectionState(int state) {
        ConnectionsManager.onConnectionStateChanged(state, account.account);
    }

    // MARK: - Network

    private void registerNetwork() {
        try {
            ConnectivityManager cm = (ConnectivityManager) ApplicationLoader.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            networkAvailable = hasNetwork(cm);
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    onNetworkChanged(null);
                }

                @Override
                public void onLost(Network network) {
                    // The lost network can still be listed for a moment: leave it out, and look again shortly.
                    onNetworkChanged(network);
                    Utilities.stageQueue.postRunnable(() -> onNetworkChanged(null), 1500);
                }
            };
            NetworkRequest request = new NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build();
            cm.registerNetworkCallback(request, networkCallback);
        } catch (Throwable t) {
            RelayConfig.logError(TAG, "network callback", t);
            networkAvailable = true;
        }
    }

    private void unregisterNetwork() {
        if (networkCallback == null) {
            return;
        }
        try {
            ConnectivityManager cm = (ConnectivityManager) ApplicationLoader.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            cm.unregisterNetworkCallback(networkCallback);
        } catch (Throwable ignore) {
        }
        networkCallback = null;
    }

    private static boolean hasNetwork(ConnectivityManager cm) {
        return hasNetwork(cm, null);
    }

    private static boolean hasNetwork(ConnectivityManager cm, Network except) {
        if (cm == null) {
            return true;
        }
        for (Network network : cm.getAllNetworks()) {
            if (network.equals(except)) {
                continue;
            }
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                return true;
            }
        }
        return false;
    }

    private synchronized void onNetworkChanged(Network lost) {
        if (!started || socket == null) {
            return;
        }
        ConnectivityManager cm = (ConnectivityManager) ApplicationLoader.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        boolean available = hasNetwork(cm, lost);
        if (available == networkAvailable) {
            return;
        }
        networkAvailable = available;
        RelayConfig.log(TAG, "live: network " + (available ? "available" : "lost"));
        if (available) {
            setConnectionState(ConnectionsManager.ConnectionStateConnecting);
            socket.reconnectNow();
            fireOnline();
        } else {
            // A real server becomes unreachable with the network; the dev server behind adb reverse would
            // not, so the socket is dropped explicitly (events are then resumed or caught up).
            socket.disconnect();
            connected = false;
            setConnectionState(ConnectionsManager.ConnectionStateWaitingForNetwork);
        }
    }

    // MARK: - WebSocketListener (socket thread)

    @Override
    public void onStateChanged(MattermostWebSocket source, ConnectionState state) {
        if (source != socket) {
            return;
        }
        switch (state.kind) {
            case CONNECTED:
                connected = true;
                setConnectionState(ConnectionsManager.ConnectionStateConnected);
                onConnected(state.info);
                break;
            case CONNECTING:
            case WAITING_TO_RECONNECT:
                connected = false;
                setConnectionState(networkAvailable ? ConnectionsManager.ConnectionStateConnecting : ConnectionsManager.ConnectionStateWaitingForNetwork);
                break;
            case DISCONNECTED:
                connected = false;
                setConnectionState(networkAvailable ? ConnectionsManager.ConnectionStateConnecting : ConnectionsManager.ConnectionStateWaitingForNetwork);
                break;
            case UNAUTHORIZED:
                connected = false;
                RelayConfig.log(TAG, "live: token rejected by the WebSocket");
                bridge.post(() -> {
                    try {
                        account.client.me();
                    } catch (MattermostException e) {
                        if (e.isUnauthorized()) {
                            bridge.sessionRejected();
                        }
                    }
                });
                break;
        }
    }

    private void onConnected(ConnectionInfo info) {
        RelayConfig.log(TAG, "live: connected " + info);
        if (info == null || !info.resumed) {
            catchUpNeeded = true;
            // The client asks for the difference; the bridge catches up before answering it.
            Utilities.stageQueue.postRunnable(() -> MessagesController.getInstance(account.account).getDifference());
        }
        fireOnline();
    }

    @Override
    public void onEvent(MattermostWebSocket source, WebSocketEvent event) {
        if (source != socket) {
            return;
        }
        long receivedAt = System.currentTimeMillis();
        bridge.post(() -> handle(event, receivedAt));
    }

    // MARK: - Events (bridge executor)

    private void handle(WebSocketEvent event, long receivedAt) {
        if (!started) {
            return;
        }
        lastEventAt = System.currentTimeMillis();
        try {
            switch (event.type) {
                case POSTED:
                    onPosted(event.getPosted() != null ? event.getPosted().post : null, receivedAt);
                    break;
                case POST_EDITED:
                    onEdited(event.getEditedPost());
                    break;
                case POST_DELETED:
                    onDeleted(event.getDeletedPost());
                    break;
                case REACTION_ADDED:
                case REACTION_REMOVED:
                    onReaction(event.getReaction(), event.type == org.telegram.relay.mattermost.ws.WebSocketEventType.REACTION_ADDED, event.broadcast.channelId);
                    break;
                case TYPING:
                    onTyping(event.getTyping());
                    break;
                case STATUS_CHANGE:
                    onStatus(event.getStatusChange());
                    break;
                case USER_UPDATED:
                    onUserUpdated(event.getUser());
                    break;
                case CHANNEL_VIEWED:
                case MULTIPLE_CHANNELS_VIEWED:
                    onViewed(event.getChannelsViewed());
                    break;
                case POST_UNREAD:
                    onPostUnread(event.getPostUnread());
                    break;
                case CHANNEL_UPDATED:
                case CHANNEL_CONVERTED:
                    onChannelUpdated(event.getChannel());
                    break;
                case CHANNEL_CREATED:
                case CHANNEL_RESTORED:
                    onChannelJoined(event.getChannelId());
                    break;
                case CHANNEL_DELETED:
                    onChannelGone(event.getChannelId());
                    break;
                case USER_ADDED: {
                    WebSocketEvent.Membership m = event.getMembership();
                    if (m != null && account.session.userId.equals(m.userId)) {
                        onChannelJoined(m.channelId);
                    }
                    break;
                }
                case USER_REMOVED: {
                    WebSocketEvent.Membership m = event.getMembership();
                    String channelId = m != null && m.channelId != null && !m.channelId.isEmpty() ? m.channelId : event.dataString("channel_id");
                    String userId = m != null ? m.userId : null;
                    if (channelId != null && (userId == null || userId.isEmpty() || account.session.userId.equals(userId)
                            || account.session.userId.equals(event.broadcast.userId))) {
                        onChannelGone(channelId);
                    }
                    break;
                }
                case DIRECT_ADDED:
                case GROUP_ADDED: {
                    WebSocketEvent.DirectAdded added = event.getDirectAdded();
                    if (added != null) {
                        onChannelJoined(added.channelId);
                    }
                    break;
                }
                case HELLO:
                default:
                    break;
            }
        } catch (Throwable t) {
            RelayConfig.logError(TAG, "live: " + event + " failed", t);
            if (t instanceof MattermostException && ((MattermostException) t).isUnauthorized()) {
                bridge.sessionRejected();
            }
        }
        noteSynced();
    }

    /** Remembers that everything up to now was delivered (the next catch-up starts here). */
    private void noteSynced() {
        long now = System.currentTimeMillis();
        if (!catchUpNeeded && connected && now - lastSyncWrite > 10_000) {
            lastSyncWrite = now;
            account.db().putKv(KV_SYNC_AT, Long.toString(now - 5_000));
        }
    }

    private int ticks;

    /**
     * Every {@link #TICK_MS}: Mattermost sends {@code status_change} only to the user whose status changed
     * (11.x), so presence of the DM partners and the users seen so far (≤ 400) is polled, as iOS does, and
     * changes become {@code updateUserStatus}; "online" is refreshed once a minute so it does not expire.
     * Also persists {@code sync_at} while idle.
     */
    private void tick() {
        noteSynced();
        if (!connected) {
            return;
        }
        ticks++;
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        try {
            ids.addAll(path.directory.any().dmByUser.keySet());
        } catch (MattermostException e) {
            return;
        }
        ids.addAll(account.onlineUserIds());
        ids.addAll(account.knownStatusUserIds());
        ids.remove(account.session.userId);
        List<String> list = new ArrayList<>(ids);
        if (list.size() > 400) {
            list = list.subList(0, 400);
        }
        List<UserStatus> fresh;
        try {
            fresh = account.client.userStatuses(list);
        } catch (MattermostException e) {
            RelayConfig.logError(TAG, "status poll", e);
            return;
        }
        boolean refreshOnline = ticks % (60_000 / TICK_MS) == 0;
        ArrayList<TLRPC.Update> updatesList = new ArrayList<>();
        RelayUpdates.Refs refs = new RelayUpdates.Refs();
        for (UserStatus status : fresh) {
            UserStatus old = account.status(status.userId);
            boolean changed = old == null || !old.status.equals(status.status);
            account.putStatus(status);
            boolean online = UserStatus.ONLINE.equals(status.status) || UserStatus.DND.equals(status.status);
            if (changed || (online && refreshOnline)) {
                TL_update.TL_updateUserStatus u = new TL_update.TL_updateUserStatus();
                u.user_id = account.userTlId(status.userId);
                u.status = RelayUsers.status(false, status);
                updatesList.add(u);
                refs.user(status.userId);
            }
        }
        if (!updatesList.isEmpty()) {
            updates.deliver(updatesList, refs);
        }
    }

    // MARK: posts

    /** The channel of an event, from the snapshot or the server; null when the user is not a member. */
    private Channel channel(String channelId) throws MattermostException {
        if (channelId == null || channelId.isEmpty()) {
            return null;
        }
        Channel channel = path.directory.any().channels.get(channelId);
        if (channel == null) {
            path.directory.invalidate();
            channel = path.directory.get(0).channels.get(channelId);
        }
        return channel;
    }

    /** Maps a post (all parts); returns the newly mapped messages, oldest first (empty for a known post). */
    private List<RelayHistory.Unit> mapNew(String channelId, Post post) throws Exception {
        RelayConvert.Layout layout = new RelayConvert.Layout(post);
        IngestResult result = account.messageIds.insertPost(new PostOrderKey(post.createAt, post.id), layout.parts, channelId, account.messageIdStore());
        if (result.renumberPlan != null) {
            RelayConfig.log(TAG, "RENUMBER in " + channelId + " (live): " + result.renumberPlan);
        }
        Set<String> inserted = new HashSet<>();
        for (PostAllocation allocation : result.allocations) {
            if (allocation.status == PostAllocation.Status.INSERTED) {
                inserted.add(allocation.postId);
            }
        }
        List<RelayHistory.Unit> units = new ArrayList<>();
        for (RelayHistory.Unit unit : path.history.assignIds(channelId, RelayHistory.oldestFirst(Collections.singletonList(post)))) {
            if (inserted.contains(unit.partId())) {
                units.add(unit);
            }
        }
        return units;
    }

    /** The mapped, not deleted messages of a post, oldest first. */
    List<RelayHistory.Unit> mappedUnits(String channelId, Post post) {
        return path.history.assignIds(channelId, RelayHistory.oldestFirst(Collections.singletonList(post)));
    }

    /** New-message updates for {@code units} of one channel. */
    private void emitNew(Channel channel, List<RelayHistory.Unit> units) throws Exception {
        List<String> users = new ArrayList<>();
        ArrayList<TLRPC.Message> messages = path.messages(channel, units, users);
        ArrayList<TLRPC.Update> list = new ArrayList<>();
        for (TLRPC.Message message : messages) {
            list.add(newMessageUpdate(channel, message));
        }
        updates.emit(list, refs(channel, users), 0);
    }

    static TLRPC.Update newMessageUpdate(Channel channel, TLRPC.Message message) {
        if (channel.isDirect()) {
            TL_update.TL_updateNewMessage u = new TL_update.TL_updateNewMessage();
            u.message = message;
            return u;
        }
        TL_update.TL_updateNewChannelMessage u = new TL_update.TL_updateNewChannelMessage();
        u.message = message;
        return u;
    }

    static TLRPC.Update editUpdate(Channel channel, TLRPC.Message message) {
        if (channel.isDirect()) {
            TL_update.TL_updateEditMessage u = new TL_update.TL_updateEditMessage();
            u.message = message;
            return u;
        }
        TL_update.TL_updateEditChannelMessage u = new TL_update.TL_updateEditChannelMessage();
        u.message = message;
        return u;
    }

    RelayUpdates.Refs refs(Channel channel, java.util.Collection<String> users) {
        RelayUpdates.Refs refs = new RelayUpdates.Refs().users(users);
        String dmUser = path.directory.dmUser(channel);
        if (dmUser != null) {
            refs.user(dmUser);
        } else {
            refs.chat(path.directory.tlChannel(channel));
        }
        return refs;
    }

    private void onPosted(Post post, long receivedAt) throws Exception {
        if (post == null || post.isEphemeral()) {
            return;
        }
        if (post.pendingPostId != null && !post.pendingPostId.isEmpty() && pendingPostIds.contains(post.pendingPostId)) {
            return; // our own send, answered by the write path
        }
        Channel channel = channel(post.channelId);
        if (channel == null) {
            return;
        }
        path.history.rememberPost(post);
        if (!channel.isDirect() && !path.isKnown(channel.id)) {
            announce(channel);
            return;
        }
        List<RelayHistory.Unit> units = mapNew(channel.id, post);
        if (units.isEmpty()) {
            return;
        }
        emitNew(channel, units);
        path.noteKnown(Collections.singletonList(channel.id));
        if (post.isReply()) {
            refreshRoot(channel, post.rootId);
            if (!account.session.userId.equals(post.userId) && path.directory.any().collapsedThreads) {
                fixCrtUnread(channel);
            }
        }
        RelayConfig.log(TAG, "live: posted " + post.id + " in " + channel.name + " -> " + units.size() + " message(s), "
                + (System.currentTimeMillis() - receivedAt) + " ms after the event");
    }

    /**
     * Collapsed reply threads: a reply does not make the chat unread in Mattermost (the {@code _root}
     * counters), but Telegram counts every incoming message. The badge is set back to the server's count
     * ({@code still_unread_count}, read max unchanged).
     */
    private void fixCrtUnread(Channel channel) {
        if (channel.isDirect()) {
            return;
        }
        try {
            ChannelMember member = account.client.channelMember(channel.id);
            Channel fresh = account.client.channel(channel.id);
            int unread = (int) Math.max(0, fresh.totalMsgCountRoot - member.msgCountRoot);
            // Megagroups only: a DM's read update has no still_unread_count in Telegram (known gap).
            ArrayList<TLRPC.Update> list = new ArrayList<>();
            TL_update.TL_updateReadChannelInbox u = new TL_update.TL_updateReadChannelInbox();
            u.channel_id = -path.directory.dialogId(channel);
            u.max_id = 0;
            u.still_unread_count = unread;
            u.pts = account.db().pts(RelayDatabase.channelBox(u.channel_id));
            list.add(u);
            updates.deliver(list, refs(channel, Collections.<String>emptyList()));
        } catch (MattermostException e) {
            RelayConfig.logError(TAG, "unread after reply", e);
        }
    }

    /** Re-sends a thread root with its current reply count ("N replies"). */
    void refreshRoot(Channel channel, String rootId) throws Exception {
        if (channel.isDirect()) {
            return;
        }
        Post root;
        try {
            root = account.client.post(rootId);
        } catch (MattermostException e) {
            if (e.isNotFound() || e.getStatusCode() == 403) {
                return;
            }
            throw e;
        }
        path.history.rememberPost(root);
        List<RelayHistory.Unit> units = mappedUnits(channel.id, root);
        if (units.isEmpty()) {
            return;
        }
        emitEdits(channel, units);
    }

    private void emitEdits(Channel channel, List<RelayHistory.Unit> units) throws Exception {
        List<String> users = new ArrayList<>();
        ArrayList<TLRPC.Message> messages = path.messages(channel, units, users);
        ArrayList<TLRPC.Update> list = new ArrayList<>();
        for (TLRPC.Message message : messages) {
            list.add(editUpdate(channel, message));
        }
        updates.emit(list, refs(channel, users), 0);
    }

    private void onEdited(Post post) throws Exception {
        if (post == null) {
            return;
        }
        Channel channel = channel(post.channelId);
        if (channel == null) {
            return;
        }
        path.history.rememberPost(post);
        List<RelayHistory.Unit> units = mappedUnits(channel.id, post);
        if (units.isEmpty()) {
            return;
        }
        emitEdits(channel, units);
    }

    private void onDeleted(Post post) throws Exception {
        if (post == null) {
            return;
        }
        Channel channel = channel(post.channelId);
        if (channel == null) {
            return;
        }
        emitDeleted(channel, Collections.singletonList(post.id));
        if (post.isReply()) {
            refreshRoot(channel, post.rootId);
        }
    }

    /** Tombstones posts and tells the client (peer-aware for DMs). Returns the deleted message ids. */
    ArrayList<Integer> emitDeleted(Channel channel, List<String> postIds) {
        List<MessageIdEntry> entries = account.messageIds.markDeleted(postIds, channel.id, account.messageIdStore());
        ArrayList<Integer> ids = new ArrayList<>();
        for (MessageIdEntry entry : entries) {
            ids.add(entry.localId);
        }
        if (ids.isEmpty()) {
            return ids;
        }
        ArrayList<TLRPC.Update> list = new ArrayList<>();
        list.add(deleteUpdate(channel, ids));
        long did = path.directory.dialogId(channel);
        updates.emit(list, refs(channel, Collections.<String>emptyList()), channel.isDirect() ? did : 0);
        return ids;
    }

    TLRPC.Update deleteUpdate(Channel channel, ArrayList<Integer> ids) {
        if (channel.isDirect()) {
            TL_update.TL_updateDeleteMessages u = new TL_update.TL_updateDeleteMessages();
            u.messages = ids;
            return u;
        }
        TL_update.TL_updateDeleteChannelMessages u = new TL_update.TL_updateDeleteChannelMessages();
        u.channel_id = -path.directory.dialogId(channel);
        u.messages = ids;
        return u;
    }

    // MARK: reactions, typing, presence, profiles

    private void onReaction(Reaction reaction, boolean added, String channelId) throws Exception {
        if (reaction == null) {
            return;
        }
        if (channelId == null || channelId.isEmpty()) {
            channelId = reaction.channelId;
        }
        Channel channel = channel(channelId);
        if (channel == null) {
            return;
        }
        int localId = path.history.localIdOf(channel.id, reaction.postId);
        if (localId == 0) {
            return;
        }
        Post post = path.history.cachedPost(reaction.postId);
        if (post != null) {
            applyReaction(post, reaction, added);
        } else {
            post = account.client.post(reaction.postId);
            path.history.rememberPost(post);
        }
        deliverReactions(channel, post, localId);
    }

    static void applyReaction(Post post, Reaction reaction, boolean added) {
        if (post.metadata == null) {
            post.metadata = new org.telegram.relay.mattermost.model.PostMetadata();
        }
        List<Reaction> list = post.metadata.reactions;
        for (int i = list.size() - 1; i >= 0; i--) {
            Reaction r = list.get(i);
            if (r.userId.equals(reaction.userId) && r.emojiName.equals(reaction.emojiName)) {
                list.remove(i);
            }
        }
        if (added) {
            list.add(reaction);
        }
        post.hasReactions = !list.isEmpty();
    }

    void deliverReactions(Channel channel, Post post, int localId) {
        TL_update.TL_updateMessageReactions u = reactionsUpdate(channel, post, localId);
        ArrayList<TLRPC.Update> list = new ArrayList<>();
        list.add(u);
        updates.deliver(list, refs(channel, Collections.<String>emptyList()));
    }

    TL_update.TL_updateMessageReactions reactionsUpdate(Channel channel, Post post, int localId) {
        TL_update.TL_updateMessageReactions u = new TL_update.TL_updateMessageReactions();
        u.peer = path.directory.peer(channel);
        u.msg_id = localId;
        TLRPC.TL_messageReactions reactions = path.convert.reactions(post);
        u.reactions = reactions != null ? reactions : new TLRPC.TL_messageReactions();
        return u;
    }

    private void onTyping(WebSocketEvent.Typing typing) throws Exception {
        if (typing == null || account.session.userId.equals(typing.userId)) {
            return;
        }
        Channel channel = path.directory.any().channels.get(typing.channelId);
        if (channel == null) {
            return;
        }
        account.ensureUsers(Collections.singletonList(typing.userId));
        int topId = typing.parentId != null && !typing.parentId.isEmpty() ? path.history.localIdOf(channel.id, typing.parentId) : 0;
        TLRPC.Update update;
        if (channel.isDirect()) {
            TL_update.TL_updateUserTyping u = new TL_update.TL_updateUserTyping();
            u.user_id = account.userTlId(typing.userId);
            u.action = new TLRPC.TL_sendMessageTypingAction();
            if (topId != 0) {
                u.top_msg_id = topId;
                u.flags |= TLObject.FLAG_0;
            }
            update = u;
        } else {
            TL_update.TL_updateChannelUserTyping u = new TL_update.TL_updateChannelUserTyping();
            u.channel_id = -path.directory.dialogId(channel);
            TLRPC.TL_peerUser from = new TLRPC.TL_peerUser();
            from.user_id = account.userTlId(typing.userId);
            u.from_id = from;
            u.action = new TLRPC.TL_sendMessageTypingAction();
            if (topId != 0) {
                u.top_msg_id = topId;
                u.flags |= TLObject.FLAG_0;
            }
            update = u;
        }
        ArrayList<TLRPC.Update> list = new ArrayList<>();
        list.add(update);
        updates.deliver(list, refs(channel, Collections.singletonList(typing.userId)));
    }

    /** Our composer is typing ({@code messages.setTyping}): {@code user_typing} over the WebSocket. */
    void sendTyping(String channelId, String parentId) {
        MattermostWebSocket s = socket;
        if (s != null && channelId != null) {
            s.userTyping(channelId, parentId);
        }
    }

    private void onStatus(WebSocketEvent.StatusChange change) {
        if (change == null) {
            return;
        }
        UserStatus status = new UserStatus();
        status.userId = change.userId;
        status.status = change.status;
        status.lastActivityAt = System.currentTimeMillis();
        account.putStatus(status);
        if (account.session.userId.equals(change.userId)) {
            return;
        }
        TL_update.TL_updateUserStatus u = new TL_update.TL_updateUserStatus();
        u.user_id = account.userTlId(change.userId);
        u.status = RelayUsers.status(false, status);
        ArrayList<TLRPC.Update> list = new ArrayList<>();
        list.add(u);
        updates.deliver(list, new RelayUpdates.Refs().user(change.userId));
    }

    private void onUserUpdated(User user) {
        if (user == null || user.id == null || user.id.isEmpty()) {
            return;
        }
        account.rememberUser(user);
        TLRPC.TL_user tl = account.tlUser(user.id);
        if (tl == null) {
            return;
        }
        TL_update.TL_updateUserName name = new TL_update.TL_updateUserName();
        name.user_id = tl.id;
        name.first_name = tl.first_name != null ? tl.first_name : "";
        name.last_name = tl.last_name != null ? tl.last_name : "";
        if (tl.username != null) {
            TLRPC.TL_username username = new TLRPC.TL_username();
            username.username = tl.username;
            username.active = true;
            username.editable = true;
            name.usernames.add(username);
        }
        TL_update.TL_updateUser photo = new TL_update.TL_updateUser();
        photo.user_id = tl.id;
        ArrayList<TLRPC.Update> list = new ArrayList<>();
        list.add(name);
        list.add(photo);
        updates.deliver(list, new RelayUpdates.Refs().user(user.id));
    }

    // MARK: read state

    /** The newest mapped, not deleted message of a chat, or 0. */
    int topLocalId(String channelId) {
        for (MessageIdEntry entry : account.messageIdStore().entriesFromLocalId(channelId, Integer.MAX_VALUE, false, 64)) {
            if (!entry.isDeleted) {
                return entry.localId;
            }
        }
        return 0;
    }

    private void onViewed(WebSocketEvent.ChannelsViewed viewed) throws Exception {
        if (viewed == null) {
            return;
        }
        path.directory.invalidate();
        for (String channelId : viewed.channelIds) {
            Channel channel = path.directory.any().channels.get(channelId);
            if (channel == null) {
                continue;
            }
            emitRead(channel);
        }
    }

    /** "Read up to the newest message" (read on another device, or read during an offline gap). */
    void emitRead(Channel channel) {
        int top = topLocalId(channel.id);
        if (top == 0) {
            return;
        }
        ArrayList<TLRPC.Update> list = new ArrayList<>();
        if (channel.isDirect()) {
            TL_update.TL_updateReadHistoryInbox u = new TL_update.TL_updateReadHistoryInbox();
            u.peer = path.directory.peer(channel);
            u.max_id = top;
            u.still_unread_count = 0;
            list.add(u);
            updates.emit(list, refs(channel, Collections.<String>emptyList()), 0);
        } else {
            TL_update.TL_updateReadChannelInbox u = new TL_update.TL_updateReadChannelInbox();
            u.channel_id = -path.directory.dialogId(channel);
            u.max_id = top;
            u.still_unread_count = 0;
            u.pts = account.db().pts(RelayDatabase.channelBox(u.channel_id));
            list.add(u);
            updates.deliver(list, refs(channel, Collections.<String>emptyList()));
        }
    }

    private void onPostUnread(WebSocketEvent.PostUnread unread) throws Exception {
        if (unread == null) {
            return;
        }
        path.directory.invalidate();
        Channel channel = path.directory.any().channels.get(unread.channelId);
        if (channel == null) {
            return;
        }
        TL_update.TL_updateDialogUnreadMark u = new TL_update.TL_updateDialogUnreadMark();
        TLRPC.TL_dialogPeer peer = new TLRPC.TL_dialogPeer();
        peer.peer = path.directory.peer(channel);
        u.peer = peer;
        u.unread = true;
        ArrayList<TLRPC.Update> list = new ArrayList<>();
        list.add(u);
        updates.deliver(list, refs(channel, Collections.<String>emptyList()));
    }

    // MARK: channels

    /** A megagroup the client has no dialog for: {@code updateChannel} makes it ask for the channel's difference. */
    void announce(Channel channel) {
        if (channel.isDirect()) {
            return;
        }
        path.noteKnown(Collections.singletonList(channel.id));
        TL_update.TL_updateChannel u = new TL_update.TL_updateChannel();
        u.channel_id = -path.directory.dialogId(channel);
        ArrayList<TLRPC.Update> list = new ArrayList<>();
        list.add(u);
        updates.deliver(list, new RelayUpdates.Refs().chat(path.directory.tlChannel(channel)));
        RelayConfig.log(TAG, "live: new chat " + channel.name);
    }

    private void onChannelUpdated(Channel channel) throws MattermostException {
        if (channel == null) {
            return;
        }
        path.directory.invalidate();
        Channel current = path.directory.get(0).channels.get(channel.id);
        if (current == null || current.isDirect()) {
            return;
        }
        if (!path.isKnown(current.id)) {
            announce(current);
            return;
        }
        TL_update.TL_updateChannel u = new TL_update.TL_updateChannel();
        u.channel_id = -path.directory.dialogId(current);
        ArrayList<TLRPC.Update> list = new ArrayList<>();
        list.add(u);
        updates.deliver(list, new RelayUpdates.Refs().chat(path.directory.tlChannel(current)));
    }

    private void onChannelJoined(String channelId) throws MattermostException {
        if (channelId == null || channelId.isEmpty()) {
            return;
        }
        path.directory.invalidate();
        Channel channel = path.directory.get(0).channels.get(channelId);
        if (channel == null || channel.isDirect() || path.isKnown(channelId)) {
            return;
        }
        announce(channel);
    }

    private void onChannelGone(String channelId) throws MattermostException {
        if (channelId == null || channelId.isEmpty()) {
            return;
        }
        long tlId = account.db().findTlId(RelayDatabase.KIND_CHANNEL, channelId);
        path.directory.invalidate();
        Channel still = path.directory.get(0).channels.get(channelId);
        if (still != null || tlId == 0) {
            return;
        }
        forget(channelId, tlId);
    }

    /** The user left (or lost) a megagroup: {@code channelForbidden} makes the client drop the dialog. */
    void forget(String channelId, long tlId) {
        path.forgetKnown(channelId);
        TLRPC.TL_channelForbidden gone = new TLRPC.TL_channelForbidden();
        gone.id = tlId;
        gone.access_hash = RelayUsers.accessHash(channelId);
        gone.megagroup = true;
        gone.title = "";
        TL_update.TL_updateChannel u = new TL_update.TL_updateChannel();
        u.channel_id = tlId;
        ArrayList<TLRPC.Update> list = new ArrayList<>();
        list.add(u);
        updates.deliver(list, new RelayUpdates.Refs().chat(gone));
        RelayConfig.log(TAG, "live: left chat " + channelId);
    }

    // MARK: - Catch-up (bridge executor, from getDifference)

    /**
     * REST catch-up since {@code sync_at}: new posts are mapped and logged as new messages, edited ones as
     * edits, deleted ones as deletes, reads as read updates; joined/left channels are announced. Logged
     * updates reach the client through the difference answer.
     *
     * @return true when the gap is too large (answer differenceTooLong)
     */
    boolean catchUp() throws Exception {
        long started = System.currentTimeMillis();
        catchUpNeeded = false;
        String stored = account.db().kv(KV_SYNC_AT);
        path.directory.invalidate();
        RelayDirectory.Snapshot snapshot = path.directory.get(0);
        if (stored == null) {
            account.db().putKv(KV_SYNC_AT, Long.toString(started));
            return false;
        }
        long syncAt = Long.parseLong(stored);
        if (started - syncAt > MAX_CATCH_UP_MS) {
            RelayConfig.log(TAG, "catch-up: last sync " + (started - syncAt) / 1000 + " s ago, too long");
            account.db().putKv(KV_SYNC_AT, Long.toString(started));
            return true;
        }
        long since = syncAt - SYNC_MARGIN_MS;
        int newCount = 0, editCount = 0, deleteCount = 0, readCount = 0, channels = 0;
        boolean tooLong = false;
        for (Channel channel : RelayDirectory.sortedChannels(snapshot)) {
            ChannelMember member = snapshot.members.get(channel.id);
            boolean read = member != null && member.lastViewedAt >= since && RelayDirectory.unreadCount(snapshot, channel, member) == 0;
            if (channel.lastPostAt < since && !read) {
                continue;
            }
            if (!channel.isDirect() && !path.isKnown(channel.id)) {
                announce(channel);
                continue;
            }
            List<Post> posts = new ArrayList<>();
            if (channel.lastPostAt >= since) {
                channels++;
                PostList list = account.client.posts(channel.id, PostsQuery.since(since));
                path.history.remember(list);
                posts.addAll(list.posts.values());
                Collections.sort(posts, (a, b) -> a.createAt != b.createAt ? Long.compare(a.createAt, b.createAt) : a.id.compareTo(b.id));
            }
            if (posts.size() > CHANNEL_CATCH_UP_LIMIT) {
                if (channel.isDirect()) {
                    tooLong = true;
                    continue;
                }
                account.messageIds.invalidateLatest(channel.id, account.messageIdStore());
                path.markTooLong(-path.directory.dialogId(channel));
                TL_update.TL_updateChannelTooLong u = new TL_update.TL_updateChannelTooLong();
                u.channel_id = -path.directory.dialogId(channel);
                ArrayList<TLRPC.Update> list = new ArrayList<>();
                list.add(u);
                updates.deliver(list, new RelayUpdates.Refs().chat(path.directory.tlChannel(channel)));
                continue;
            }
            List<String> users = new ArrayList<>();
            List<RelayHistory.Unit> fresh = new ArrayList<>();
            List<RelayHistory.Unit> edited = new ArrayList<>();
            List<String> deleted = new ArrayList<>();
            Set<String> roots = new HashSet<>();
            for (Post post : posts) {
                if (post.isDeleted()) {
                    deleted.add(post.id);
                    if (post.isReply()) {
                        roots.add(post.rootId);
                    }
                    continue;
                }
                List<RelayHistory.Unit> mapped = mappedUnits(channel.id, post);
                if (!mapped.isEmpty()) {
                    if (post.updateAt >= since) {
                        edited.addAll(mapped);
                    }
                } else if (post.createAt >= since && !post.isEphemeral()) {
                    List<RelayHistory.Unit> units = mapNew(channel.id, post);
                    fresh.addAll(units);
                    if (!units.isEmpty() && post.isReply()) {
                        roots.add(post.rootId);
                    }
                }
            }
            ArrayList<TLRPC.Update> list = new ArrayList<>();
            if (!fresh.isEmpty()) {
                for (TLRPC.Message message : path.messages(channel, fresh, users)) {
                    list.add(newMessageUpdate(channel, message));
                }
                newCount += fresh.size();
            }
            if (!edited.isEmpty()) {
                for (TLRPC.Message message : path.messages(channel, edited, users)) {
                    list.add(editUpdate(channel, message));
                }
                editCount += edited.size();
            }
            for (TLRPC.Update u : list) {
                updates.stamp(u, 0);
            }
            if (!deleted.isEmpty()) {
                List<MessageIdEntry> entries = account.messageIds.markDeleted(deleted, channel.id, account.messageIdStore());
                ArrayList<Integer> ids = new ArrayList<>();
                for (MessageIdEntry entry : entries) {
                    ids.add(entry.localId);
                }
                if (!ids.isEmpty()) {
                    updates.stamp(deleteUpdate(channel, ids), channel.isDirect() ? path.directory.dialogId(channel) : 0);
                    deleteCount += ids.size();
                }
            }
            for (String rootId : roots) {
                Post root = path.history.cachedPost(rootId);
                if (root == null || root.updateAt < since) {
                    try {
                        root = account.client.post(rootId);
                        path.history.rememberPost(root);
                    } catch (MattermostException e) {
                        continue;
                    }
                }
                List<RelayHistory.Unit> units = mappedUnits(channel.id, root);
                if (!units.isEmpty() && !channel.isDirect()) {
                    for (TLRPC.Message message : path.messages(channel, units, users)) {
                        updates.stamp(editUpdate(channel, message), 0);
                    }
                }
            }
            if (read) {
                emitReadLogged(channel);
                readCount++;
            }
        }
        // Chats the user left while away.
        for (String channelId : path.knownChannels()) {
            if (!snapshot.channels.containsKey(channelId)) {
                long tlId = account.db().findTlId(RelayDatabase.KIND_CHANNEL, channelId);
                if (tlId != 0) {
                    forget(channelId, tlId);
                } else {
                    path.forgetKnown(channelId);
                }
            }
        }
        account.db().putKv(KV_SYNC_AT, Long.toString(started));
        lastSyncWrite = System.currentTimeMillis();
        RelayConfig.log(TAG, String.format(java.util.Locale.US, "catch-up since %d s: %d channels, %d new, %d edited, %d deleted, %d read%s in %d ms",
                (started - since) / 1000, channels, newCount, editCount, deleteCount, readCount, tooLong ? ", too long" : "",
                System.currentTimeMillis() - started));
        return tooLong;
    }

    /** Read during the gap: DMs through the log (common pts), channels straight away (no pts). */
    private void emitReadLogged(Channel channel) {
        int top = topLocalId(channel.id);
        if (top == 0) {
            return;
        }
        if (channel.isDirect()) {
            TL_update.TL_updateReadHistoryInbox u = new TL_update.TL_updateReadHistoryInbox();
            u.peer = path.directory.peer(channel);
            u.max_id = top;
            u.still_unread_count = 0;
            updates.stamp(u, 0);
        } else {
            emitRead(channel);
        }
    }

    // MARK: - Difference requests

    TLObject getDifference(TLRPC.TL_updates_getDifference req) throws Exception {
        boolean tooLong = false;
        if (catchUpNeeded) {
            tooLong = catchUp();
        }
        if (tooLong) {
            TLRPC.TL_updates_differenceTooLong res = new TLRPC.TL_updates_differenceTooLong();
            res.pts = account.db().pts(RelayDatabase.BOX_COMMON);
            return res;
        }
        return updates.difference(req.pts, req.date);
    }

    TLObject getChannelDifference(TLRPC.TL_updates_getChannelDifference req) throws Exception {
        long channelTlId = req.channel != null ? req.channel.channel_id : 0;
        String box = RelayDatabase.channelBox(channelTlId);
        int channelPts = account.db().pts(box);
        boolean newDialog = req.pts <= 1 && req.limit == 1;
        if (!newDialog && !path.consumeTooLong(channelTlId)) {
            if (req.pts >= channelPts) {
                TLRPC.TL_updates_channelDifferenceEmpty empty = new TLRPC.TL_updates_channelDifferenceEmpty();
                empty.isFinal = true;
                empty.pts = Math.max(req.pts, channelPts);
                empty.timeout = 30;
                empty.flags |= TLObject.FLAG_1;
                return empty;
            }
            TLRPC.TL_updates_channelDifference diff = updates.channelDifference(channelTlId, req.pts);
            if (diff != null) {
                return diff;
            }
        }
        String channelId = account.db().mmId(RelayDatabase.KIND_CHANNEL, channelTlId);
        Channel channel = channelId != null ? path.directory.channel(channelId) : null;
        if (channel == null) {
            return RelayTl.error(400, "CHANNEL_PRIVATE");
        }
        RelayConfig.log(TAG, "getChannelDifference " + channel.name + " pts " + req.pts + " -> too long (" + (newDialog ? "new chat" : "reload") + ")");
        return path.channelTooLong(channel);
    }
}
