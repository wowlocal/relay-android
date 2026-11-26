package org.telegram.relay.bridge;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.relay.RelayConfig;
import org.telegram.relay.account.RelayAccount;
import org.telegram.relay.account.RelayDatabase;
import org.telegram.relay.mattermost.MattermostException;
import org.telegram.relay.mattermost.PostsQuery;
import org.telegram.relay.mattermost.model.Channel;
import org.telegram.relay.mattermost.model.ChannelStats;
import org.telegram.relay.mattermost.model.Post;
import org.telegram.relay.mattermost.model.PostList;
import org.telegram.relay.mattermost.model.Team;
import org.telegram.relay.mattermost.model.User;
import org.telegram.relay.ui.RelayLinks;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A7a: permalinks (iOS {@code MattermostSync.permalink} / {@code linkDestination}).
 *
 * <ul>
 *   <li>Copy Link: {@code channels.exportMessageLink} → {@code <server>/<team>/pl/<post>} (the channel's team,
 *       else the user's first team: Mattermost resolves the post's channel itself).</li>
 *   <li>Link destinations for {@link RelayLinks}: a post (mapped and opened in its chat at the post; a reply
 *       hidden by collapsed threads opens its thread at the reply), a channel by team and name, a DM by
 *       username (created when missing), a group message by name, a thread by its root. A public channel
 *       the user is not in becomes a join prompt with a preview; joining resolves the link again.</li>
 * </ul>
 */
public final class RelayPermalinks {
    private static final String TAG = RelayConfig.TAG_BRIDGE;

    private RelayPermalinks() {
    }

    static void register(Map<Class<?>, RelayHandlers.Handler> map) {
        map.put(TLRPC.TL_channels_exportMessageLink.class, (bridge, account, req) -> exportMessageLink(account, (TLRPC.TL_channels_exportMessageLink) req));
        org.telegram.relay.ui.RelayLinkOpener.install();
    }

    // MARK: - Copy Link

    private static TLObject exportMessageLink(RelayAccount account, TLRPC.TL_channels_exportMessageLink req) throws Exception {
        RelayReadPath path = RelayReadPath.of(account);
        String channelId = account.db().mmId(RelayDatabase.KIND_CHANNEL, req.channel.channel_id);
        RelayHistory.Unit unit = channelId != null ? path.history.unitAt(channelId, req.id) : null;
        if (unit == null || unit.layout.service) {
            return RelayTl.error(400, "MESSAGE_ID_INVALID");
        }
        TLRPC.TL_exportedMessageLink res = new TLRPC.TL_exportedMessageLink();
        res.link = permalink(account, path.directory.channel(channelId), unit.layout.post.id);
        res.html = "";
        RelayConfig.log(TAG, "exportMessageLink " + req.id + " -> " + res.link);
        return res;
    }

    /** {@code <server>/<team>/pl/<post id>}. */
    static String permalink(RelayAccount account, Channel channel, String postId) throws MattermostException {
        RelayDirectory.Snapshot snapshot = RelayReadPath.of(account).directory.any();
        String teamName = null;
        for (Team team : snapshot.teams) {
            if (channel != null && team.id.equals(channel.teamId)) {
                teamName = team.name;
            }
        }
        if (teamName == null && !snapshot.teams.isEmpty()) {
            List<Team> teams = new ArrayList<>(snapshot.teams);
            Collections.sort(teams, (a, b) -> a.displayName.compareToIgnoreCase(b.displayName));
            teamName = teams.get(0).name;
        }
        return serverBase(account) + "/" + (teamName != null ? teamName : "_redirect") + "/pl/" + postId;
    }

    private static String serverBase(RelayAccount account) {
        String server = account.session.serverUrl != null ? account.session.serverUrl.trim() : "";
        while (server.endsWith("/")) {
            server = server.substring(0, server.length() - 1);
        }
        return server;
    }

    // MARK: - Link destinations

    /** Where a link leads. */
    public static final class Destination {
        public enum Kind { CHAT, THREAD, JOIN, ERROR }

        public Kind kind = Kind.ERROR;
        public int account;
        /** Telegram dialog (user id for DMs, -channel for megagroups). */
        public long dialogId;
        /** Message to open the chat at (0: the chat's usual position); in a thread, the reply to show. */
        public int messageId;
        /** Thread root message id ({@link Kind#THREAD}). */
        public int rootMessageId;
        /** JOIN: the channel to join and the preview. */
        public String channelId;
        public String channelName;
        public String title;
        public String purpose;
        public long memberCount;
        public final ArrayList<String> recent = new ArrayList<>();
        /** ERROR: a short reason (not_found, no_access, offline). */
        public String error;
        /** Put into MessagesController before opening. */
        public final ArrayList<TLRPC.User> users = new ArrayList<>();
        public final ArrayList<TLRPC.Chat> chats = new ArrayList<>();

        @Override
        public String toString() {
            return kind + " dialog=" + dialogId + " message=" + messageId + " root=" + rootMessageId + (channelName != null ? " channel=" + channelName : "") + (error != null ? " error=" + error : "");
        }
    }

    /** Resolves a link of the account on the bridge executor; answers on the UI thread. */
    public static void resolve(int account, RelayLinks.Link link, RelayThreads.Callback<Destination> callback) {
        run(account, callback, relayAccount -> {
            Destination d = destination(relayAccount, link);
            d.account = account;
            RelayConfig.log(TAG, "link " + link.route + " " + link.value + " -> " + d);
            return d;
        });
    }

    /** Joins a public channel ({@code POST /channels/{id}/members}), announces it to the client, then resolves the link again. */
    public static void joinAndResolve(int account, String channelId, RelayLinks.Link link, RelayThreads.Callback<Destination> callback) {
        run(account, callback, relayAccount -> {
            RelayReadPath path = RelayReadPath.of(relayAccount);
            relayAccount.client.addChannelMember(channelId, relayAccount.session.userId);
            path.directory.invalidate();
            Channel channel = path.directory.get(0).channels.get(channelId);
            RelayLive live = RelayBridge.get(account).live();
            if (channel != null && live != null && !path.isKnown(channelId)) {
                live.announce(channel);
            }
            RelayConfig.log(TAG, "joined " + channelId);
            Destination d = destination(relayAccount, link);
            d.account = account;
            return d;
        });
    }

    private interface Job<T> {
        T run(RelayAccount account) throws Exception;
    }

    private static <T> void run(int account, RelayThreads.Callback<T> callback, Job<T> job) {
        RelayAccount relayAccount = RelayAccount.get(account);
        if (relayAccount == null) {
            AndroidUtilities.runOnUIThread(() -> callback.done(null, "not logged in"));
            return;
        }
        RelayBridge.get(account).post(() -> {
            T result = null;
            String error = null;
            try {
                result = job.run(relayAccount);
            } catch (MattermostException e) {
                RelayConfig.logError(TAG, "link failed", e);
                error = e.getKind() == MattermostException.Kind.TRANSPORT ? "offline" : e.getStatusCode() == 403 ? "no_access" : e.isNotFound() ? "not_found" : "failed";
            } catch (Throwable t) {
                RelayConfig.logError(TAG, "link failed", t);
                error = "failed";
            }
            final T r = result;
            final String e = error;
            AndroidUtilities.runOnUIThread(() -> callback.done(r, e));
        });
    }

    private static Destination error(String reason) {
        Destination d = new Destination();
        d.kind = Destination.Kind.ERROR;
        d.error = reason;
        return d;
    }

    private static Destination destination(RelayAccount account, RelayLinks.Link link) throws Exception {
        RelayReadPath path = RelayReadPath.of(account);
        RelayDirectory.Snapshot snapshot = path.directory.get(30_000);
        switch (link.route) {
            case POST:
                return postDestination(account, path, link.value, false);
            case THREAD:
                return postDestination(account, path, link.value, true);
            case CHANNEL:
            case GROUP_MESSAGE: {
                Channel channel = channelByName(account, snapshot, link.teamName, link.value);
                if (channel == null) {
                    return error("not_found");
                }
                return channelDestination(account, path, channel, 0);
            }
            case DIRECT_MESSAGE: {
                User user;
                try {
                    user = account.client.userByUsername(link.value);
                } catch (MattermostException e) {
                    if (e.isNotFound()) {
                        return error("not_found");
                    }
                    throw e;
                }
                account.rememberUser(user);
                if (!snapshot.dmByUser.containsKey(user.id)) {
                    account.client.createDirectChannel(account.session.userId, user.id);
                    path.directory.invalidate();
                    snapshot = path.directory.get(0);
                }
                Destination d = new Destination();
                d.kind = Destination.Kind.CHAT;
                d.dialogId = account.userTlId(user.id);
                d.users.addAll(path.users(Collections.singletonList(user.id)));
                return d;
            }
            default:
                return error("not_found");
        }
    }

    private static Channel channelByName(RelayAccount account, RelayDirectory.Snapshot snapshot, String teamName, String name) throws MattermostException {
        for (Channel channel : snapshot.channels.values()) {
            if (name.equals(channel.name) && (teamName == null || channel.teamId == null || channel.teamId.isEmpty() || teamName.equals(teamNameOf(snapshot, channel.teamId)))) {
                return channel;
            }
        }
        for (Team team : snapshot.teams) {
            if (teamName != null && !teamName.equals(team.name)) {
                continue;
            }
            try {
                return account.client.channelByName(team.id, name, false);
            } catch (MattermostException e) {
                if (!e.isNotFound() && e.getStatusCode() != 403) {
                    throw e;
                }
            }
        }
        return null;
    }

    private static String teamNameOf(RelayDirectory.Snapshot snapshot, String teamId) {
        for (Team team : snapshot.teams) {
            if (team.id.equals(teamId)) {
                return team.name;
            }
        }
        return null;
    }

    /** A chat the user may or may not be in: open it (at {@code messageId}), or a join prompt for a public channel. */
    private static Destination channelDestination(RelayAccount account, RelayReadPath path, Channel channel, int messageId) throws Exception {
        RelayDirectory.Snapshot snapshot = path.directory.any();
        if (!snapshot.channels.containsKey(channel.id)) {
            if (channel.isArchived() || !channel.isOpen()) {
                return error("no_access");
            }
            return joinPrompt(account, path, channel);
        }
        Destination d = new Destination();
        d.kind = Destination.Kind.CHAT;
        d.dialogId = path.directory.dialogId(channel);
        d.messageId = messageId;
        String dmUser = path.directory.dmUser(channel);
        if (dmUser != null) {
            d.users.addAll(path.users(Collections.singletonList(dmUser)));
        } else {
            d.chats.add(path.directory.tlChannel(channel));
        }
        return d;
    }

    private static Destination joinPrompt(RelayAccount account, RelayReadPath path, Channel channel) throws Exception {
        Destination d = new Destination();
        d.kind = Destination.Kind.JOIN;
        d.channelId = channel.id;
        d.channelName = channel.name;
        String title = channel.displayName != null ? channel.displayName.trim() : "";
        d.title = title.isEmpty() ? channel.name : title;
        d.purpose = channel.purpose != null ? channel.purpose.trim() : "";
        try {
            ChannelStats stats = account.client.channelStats(channel.id);
            d.memberCount = stats.memberCount;
        } catch (MattermostException e) {
            // Non-members may not read a public channel's stats; the member count endpoint answers them.
            try {
                Long count = account.client.channelMemberCounts(Collections.singletonList(channel.id)).get(channel.id);
                d.memberCount = count != null ? count : 0;
            } catch (MattermostException e2) {
                RelayConfig.logError(TAG, "channel member count", e2);
            }
        }
        try {
            PostList list = account.client.posts(channel.id, PostsQuery.latest(3));
            List<Post> posts = list.getOrderedPosts();
            Set<String> authors = new LinkedHashSet<>();
            for (Post post : posts) {
                authors.add(post.userId);
            }
            account.ensureUsers(authors);
            for (int i = posts.size() - 1; i >= 0; i--) {
                Post post = posts.get(i);
                if (post.isSystemMessage()) {
                    continue;
                }
                TLRPC.TL_user user = account.tlUser(post.userId);
                String name = user != null ? org.telegram.messenger.UserObject.getUserName(user) : "";
                d.recent.add(name + ": " + RelayThreads.previewText(post));
            }
        } catch (MattermostException e) {
            RelayConfig.logError(TAG, "channel preview", e);
        }
        return d;
    }

    private static Destination postDestination(RelayAccount account, RelayReadPath path, String postId, boolean threadLink) throws Exception {
        Post post;
        try {
            post = account.client.post(postId);
        } catch (MattermostException e) {
            if (e.isNotFound()) {
                return error("not_found");
            }
            if (e.getStatusCode() == 403) {
                return error("no_access");
            }
            throw e;
        }
        if (post.isDeleted()) {
            return error("not_found");
        }
        path.history.rememberPost(post);
        Channel channel;
        try {
            channel = path.directory.channel(post.channelId);
        } catch (MattermostException e) {
            if (e.getStatusCode() == 403 || e.isNotFound()) {
                return error("no_access");
            }
            throw e;
        }
        RelayDirectory.Snapshot snapshot = path.directory.any();
        if (!snapshot.channels.containsKey(channel.id)) {
            return channelDestination(account, path, channel, 0);
        }
        // DM threads: a DM reply hidden by collapsed threads opens its thread too (not the self-DM).
        boolean collapsed = RelayThreads.collapses(account, channel, snapshot.collapsedThreads);
        boolean threads = RelayThreads.threadsIn(account, channel);
        if (post.isReply() && collapsed || threadLink && threads) {
            Post root = post.isReply() ? path.history.post(post.rootId) : post;
            if (root != null && (collapsed || threadLink && !post.isReply())) {
                int rootId = path.history.localIdOf(channel.id, root.id);
                if (rootId == 0) {
                    rootId = path.history.mapSingle(channel.id, root);
                }
                int replyId = 0;
                if (post != root) {
                    replyId = path.history.localIdOf(channel.id, post.id);
                    if (replyId == 0) {
                        replyId = path.history.mapSingle(channel.id, post);
                    }
                }
                if (collapsed) {
                    Destination d = channelDestination(account, path, channel, 0);
                    d.kind = Destination.Kind.THREAD;
                    d.rootMessageId = rootId;
                    d.messageId = replyId;
                    return d;
                }
                return channelDestination(account, path, channel, rootId);
            }
        }
        int localId = path.history.localIdOf(channel.id, post.id);
        if (localId == 0) {
            localId = path.history.mapSingle(channel.id, post);
        }
        return channelDestination(account, path, channel, localId);
    }
}
