package org.telegram.relay.bridge;

import org.telegram.relay.RelayConfig;
import org.telegram.relay.account.RelayAccount;
import org.telegram.relay.account.RelayDatabase;
import org.telegram.relay.mattermost.MattermostDirectory;
import org.telegram.relay.mattermost.model.Channel;
import org.telegram.relay.mattermost.model.Preference;
import org.telegram.relay.mattermost.ws.WebSocketEvent;
import org.telegram.relay.mattermost.ws.WebSocketEventType;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_update;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Polish 2a: pinned chats = Mattermost favourites, both ways and live (QA-03, iOS B2), and the team folders kept in
 * step with the sidebar (QA-04).
 *
 * <ul>
 *   <li><b>Pin / unpin</b> ({@code messages.toggleDialogPin}, {@code reorderPinnedDialogs}, a pin inside a team
 *       folder = {@code messages.updateDialogFilter}) → the {@code favorite_channel} preference. The server keeps
 *       its Favorites sidebar category in step with that preference (checked on 11.7: saving it moves the channel
 *       into Favorites, deleting it moves it back to Channels), and older servers have only the preference.</li>
 *   <li><b>Live</b>: {@code preferences_changed}/{@code preferences_deleted} with {@code favorite_channel} and
 *       {@code sidebar_category_updated} (another device, the web app) → the favourites are read again; when they
 *       differ from what the client has, {@code updatePinnedDialogs} (the client reloads
 *       {@code messages.getPinnedDialogs}) and {@code updateDialogFilters} (team folders' pinned chats).</li>
 *   <li><b>Team folders</b>: Telegram loads the folders once and caches them ({@code UserConfig.filtersLoaded}), so
 *       a channel created or joined later stayed in All Chats only, also after a restart. Any change of the user's
 *       channels (joined, created, left) and every bridge start send {@code updateDialogFilters}, and the client
 *       reloads {@code messages.getDialogFilters}.</li>
 * </ul>
 */
final class RelayFavorites {
    private static final String TAG = RelayConfig.TAG_BRIDGE;
    static final String CATEGORY = "favorite_channel";

    private static final WeakHashMap<RelayAccount, RelayFavorites> instances = new WeakHashMap<>();

    static synchronized RelayFavorites of(RelayAccount account) {
        RelayFavorites f = instances.get(account);
        if (f == null) {
            instances.put(account, f = new RelayFavorites(account));
        }
        return f;
    }

    private final RelayAccount account;
    private final RelayReadPath path;
    /** Favourites as the client last got them (pinned dialogs); null until the first answer. */
    private volatile List<String> delivered;
    private volatile boolean refreshScheduled;
    private volatile boolean foldersScheduled;

    private RelayFavorites(RelayAccount account) {
        this.account = account;
        this.path = RelayReadPath.of(account);
    }

    static void register(Map<Class<?>, RelayHandlers.Handler> map) {
        map.put(TLRPC.TL_messages_toggleDialogPin.class, (bridge, account, req) -> of(account).toggle(bridge, (TLRPC.TL_messages_toggleDialogPin) req));
        map.put(TLRPC.TL_messages_reorderPinnedDialogs.class, (bridge, account, req) -> of(account).reorder(bridge, (TLRPC.TL_messages_reorderPinnedDialogs) req));
        map.put(TLRPC.TL_messages_updateDialogFilter.class, (bridge, account, req) -> of(account).updateFilter(bridge, (TLRPC.TL_messages_updateDialogFilter) req));
    }

    /** The favourites the client was just given (getPinnedDialogs / getDialogFilters). */
    void noteDelivered(List<String> favorites) {
        delivered = new ArrayList<>(favorites);
    }

    // MARK: - Writes

    private TLObject toggle(RelayBridge bridge, TLRPC.TL_messages_toggleDialogPin req) throws Exception {
        if (!(req.peer instanceof TLRPC.TL_inputDialogPeer)) {
            return new TLRPC.TL_boolTrue();
        }
        String channelId = path.directory.channelIdOf(((TLRPC.TL_inputDialogPeer) req.peer).peer);
        if (channelId == null) {
            return RelayTl.error(400, "PEER_ID_INVALID");
        }
        List<String> added = new ArrayList<>(), removed = new ArrayList<>();
        (req.pinned ? added : removed).add(channelId);
        apply(added, removed);
        scheduleFolders(bridge);
        return new TLRPC.TL_boolTrue();
    }

    private TLObject reorder(RelayBridge bridge, TLRPC.TL_messages_reorderPinnedDialogs req) throws Exception {
        if (req.folder_id != 0) {
            return new TLRPC.TL_boolTrue();
        }
        Set<String> wanted = new LinkedHashSet<>();
        for (TLRPC.InputDialogPeer peer : req.order) {
            if (peer instanceof TLRPC.TL_inputDialogPeer) {
                String channelId = path.directory.channelIdOf(((TLRPC.TL_inputDialogPeer) peer).peer);
                if (channelId != null) {
                    wanted.add(channelId);
                }
            }
        }
        List<String> current = path.directory.get(0).favorites;
        List<String> added = new ArrayList<>(), removed = new ArrayList<>();
        for (String id : wanted) {
            if (!current.contains(id)) {
                added.add(id);
            }
        }
        if (req.force) {
            for (String id : current) {
                if (!wanted.contains(id)) {
                    removed.add(id);
                }
            }
        }
        if (!added.isEmpty() || !removed.isEmpty()) {
            apply(added, removed);
            scheduleFolders(bridge);
        }
        return new TLRPC.TL_boolTrue();
    }

    /** A pin inside a team folder: Telegram edits the folder's pinned peers and saves the folder. */
    private TLObject updateFilter(RelayBridge bridge, TLRPC.TL_messages_updateDialogFilter req) throws Exception {
        String teamId = account.db().mmId(RelayDatabase.KIND_TEAM, req.id);
        if (teamId == null || !(req.filter instanceof TLRPC.TL_dialogFilter)) {
            return new TLRPC.TL_boolTrue();
        }
        RelayDirectory.Snapshot snapshot = path.directory.get(0);
        Set<String> wanted = new LinkedHashSet<>();
        for (TLRPC.InputPeer peer : req.filter.pinned_peers) {
            String channelId = path.directory.channelIdOf(peer);
            if (channelId != null) {
                wanted.add(channelId);
            }
        }
        List<String> added = new ArrayList<>(), removed = new ArrayList<>();
        for (String id : wanted) {
            if (!snapshot.favorites.contains(id)) {
                added.add(id);
            }
        }
        for (String id : snapshot.favorites) {
            Channel channel = snapshot.channels.get(id);
            boolean inTeam = channel != null && (channel.isDirect() || channel.isGroup() || teamId.equals(channel.teamId));
            if (inTeam && !wanted.contains(id)) {
                removed.add(id);
            }
        }
        if (!added.isEmpty() || !removed.isEmpty()) {
            apply(added, removed);
            RelayLive live = RelayLive.peek(account);
            if (live != null) {
                deliverPinned(live);
            }
            scheduleFolders(bridge);
        }
        return new TLRPC.TL_boolTrue();
    }

    private void apply(List<String> added, List<String> removed) throws Exception {
        String me = account.session.userId;
        if (!added.isEmpty()) {
            List<Preference> list = new ArrayList<>();
            for (String id : added) {
                list.add(new Preference(me, CATEGORY, id, "true"));
            }
            MattermostDirectory.savePreferences(account.client, me, list);
        }
        if (!removed.isEmpty()) {
            List<Preference> list = new ArrayList<>();
            for (String id : removed) {
                list.add(new Preference(me, CATEGORY, id, "true"));
            }
            MattermostDirectory.deletePreferences(account.client, me, list);
        }
        path.directory.invalidate();
        List<String> now = new ArrayList<>(delivered != null ? delivered : path.directory.get(0).favorites);
        now.removeAll(removed);
        for (String id : added) {
            if (!now.contains(id)) {
                now.add(0, id);
            }
        }
        delivered = now;
        RelayConfig.log(TAG, "favorites: +" + added + " -" + removed);
    }

    // MARK: - Live

    /** WebSocket: a favourite changed elsewhere (or our own write echoed). Bridge executor. */
    static void onEvent(RelayLive live, RelayAccount account, WebSocketEvent event) {
        boolean relevant = event.type == WebSocketEventType.SIDEBAR_CATEGORY_UPDATED || event.type == WebSocketEventType.SIDEBAR_CATEGORY_ORDER_UPDATED;
        if (!relevant) {
            List<Preference> preferences = event.getPreferences();
            if (preferences != null) {
                for (Preference preference : preferences) {
                    if (CATEGORY.equals(preference.category)) {
                        relevant = true;
                        break;
                    }
                }
            }
        }
        if (relevant) {
            of(account).scheduleRefresh(live);
        }
    }

    private void scheduleRefresh(RelayLive live) {
        if (refreshScheduled) {
            return;
        }
        refreshScheduled = true;
        live.bridge.postDelayed(() -> {
            refreshScheduled = false;
            try {
                refresh(live);
            } catch (Throwable t) {
                RelayConfig.logError(TAG, "favorites refresh", t);
            }
        }, 400);
    }

    private void refresh(RelayLive live) throws Exception {
        path.directory.invalidate();
        List<String> favorites = path.directory.get(0).favorites;
        List<String> before = delivered;
        if (before != null && new LinkedHashSet<>(before).equals(new LinkedHashSet<>(favorites))) {
            return;
        }
        RelayConfig.log(TAG, "live: favorites changed elsewhere: " + (before != null ? before.size() : -1) + " -> " + favorites.size());
        delivered = new ArrayList<>(favorites);
        deliverPinned(live);
        scheduleFolders(live.bridge);
    }

    private void deliverPinned(RelayLive live) {
        TL_update.TL_updatePinnedDialogs u = new TL_update.TL_updatePinnedDialogs();
        u.folder_id = 0;
        ArrayList<TLRPC.Update> list = new ArrayList<>();
        list.add(u);
        live.updates.deliver(list, new RelayUpdates.Refs());
    }

    // MARK: - Team folders

    /** The user's channels or favourites changed: the client reloads the team folders (debounced). */
    void scheduleFolders(RelayBridge bridge) {
        if (foldersScheduled) {
            return;
        }
        foldersScheduled = true;
        bridge.postDelayed(() -> {
            foldersScheduled = false;
            RelayLive live = RelayLive.peek(account);
            if (live == null) {
                return;
            }
            ArrayList<TLRPC.Update> list = new ArrayList<>();
            list.add(new TL_update.TL_updateDialogFilters());
            live.updates.deliver(list, new RelayUpdates.Refs());
            RelayConfig.log(TAG, "team folders: reload");
        }, 600);
    }

    static void foldersChanged(RelayAccount account) {
        of(account).scheduleFolders(RelayBridge.get(account.account));
    }
}
