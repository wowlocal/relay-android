package org.telegram.relay.bridge;

import org.telegram.relay.RelayConfig;
import org.telegram.relay.account.RelayAccount;
import org.telegram.relay.account.RelayDatabase;
import org.telegram.relay.account.RelayUsers;
import org.telegram.relay.mattermost.MattermostException;
import org.telegram.relay.mattermost.model.Channel;
import org.telegram.relay.mattermost.model.ChannelMember;
import org.telegram.relay.mattermost.model.ClientConfig;
import org.telegram.relay.mattermost.model.Preference;
import org.telegram.relay.mattermost.model.Team;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The account's Mattermost sidebar, as one immutable snapshot: teams, the user's channels (DMs, GMs,
 * public and private channels), their read state, favourites and whether collapsed reply threads are
 * on. Also the peer mapping (§ PLAN "Domain mapping"):
 *
 * <ul>
 *   <li>DM → private chat with the other user ({@code peerUser}); the self-DM is Saved Messages.</li>
 *   <li>GM, public and private channel → megagroup ({@code peerChannel}, {@code TL_channel} with
 *       {@code megagroup=true}).</li>
 * </ul>
 *
 * Refreshed on demand with a short TTL; mark-read and other writes call {@link #invalidate()}.
 */
final class RelayDirectory {
    private static final String TAG = RelayConfig.TAG_BRIDGE;

    static final class Snapshot {
        final long fetchedAt;
        final List<Team> teams;
        /** The user's channels that are not archived, by Mattermost id. */
        final Map<String, Channel> channels;
        final Map<String, ChannelMember> members;
        /** Favourite channel ids in preference order. */
        final List<String> favorites;
        final boolean collapsedThreads;
        /** Mattermost user id → DM channel id (the self-DM is keyed by the own id). */
        final Map<String, String> dmByUser;

        Snapshot(long fetchedAt, List<Team> teams, Map<String, Channel> channels, Map<String, ChannelMember> members, List<String> favorites, boolean collapsedThreads, Map<String, String> dmByUser) {
            this.fetchedAt = fetchedAt;
            this.teams = teams;
            this.channels = channels;
            this.members = members;
            this.favorites = favorites;
            this.collapsedThreads = collapsedThreads;
            this.dmByUser = dmByUser;
        }
    }

    private final RelayAccount account;
    private volatile Snapshot snapshot;
    private volatile String collapsedThreadsSetting;

    RelayDirectory(RelayAccount account) {
        this.account = account;
    }

    void invalidate() {
        Snapshot current = snapshot;
        if (current != null) {
            snapshot = new Snapshot(0, current.teams, current.channels, current.members, current.favorites, current.collapsedThreads, current.dmByUser);
        }
    }

    /** The cached snapshot when it is younger than {@code maxAgeMs}, else a fresh one. */
    Snapshot get(long maxAgeMs) throws MattermostException {
        Snapshot current = snapshot;
        if (current != null && System.currentTimeMillis() - current.fetchedAt <= maxAgeMs) {
            return current;
        }
        synchronized (this) {
            current = snapshot;
            if (current != null && System.currentTimeMillis() - current.fetchedAt <= maxAgeMs) {
                return current;
            }
            snapshot = current = load();
            return current;
        }
    }

    /** The last snapshot, whatever its age, or a fresh one. */
    Snapshot any() throws MattermostException {
        Snapshot current = snapshot;
        return current != null ? current : get(0);
    }

    private Snapshot load() throws MattermostException {
        long started = System.currentTimeMillis();
        if (collapsedThreadsSetting == null) {
            ClientConfig config = account.client.clientConfig();
            collapsedThreadsSetting = config.get("CollapsedThreads");
            if (collapsedThreadsSetting == null) {
                collapsedThreadsSetting = "disabled";
            }
        }
        List<Team> teams = account.client.teams();
        List<Channel> all = account.client.allChannels(false, 0);
        List<ChannelMember> memberList = account.client.allChannelMembers();
        List<Preference> preferences = account.client.preferences();

        Map<String, Channel> channels = new HashMap<>();
        Map<String, String> dmByUser = new HashMap<>();
        for (Channel channel : all) {
            if (channel.isArchived()) {
                continue;
            }
            channels.put(channel.id, channel);
            if (channel.isDirect()) {
                String other = channel.getDirectMessageTeammateId(account.session.userId);
                if (other != null) {
                    dmByUser.put(other, channel.id);
                }
            }
        }
        Map<String, ChannelMember> members = new HashMap<>();
        for (ChannelMember member : memberList) {
            members.put(member.channelId, member);
        }
        Set<String> favorites = new LinkedHashSet<>();
        String crtPreference = null;
        for (Preference preference : preferences) {
            if ("favorite_channel".equals(preference.category) && "true".equals(preference.value) && channels.containsKey(preference.name)) {
                favorites.add(preference.name);
            } else if ("display_settings".equals(preference.category) && "collapsed_reply_threads".equals(preference.name)) {
                crtPreference = preference.value;
            }
        }
        boolean crt;
        switch (collapsedThreadsSetting) {
            case "always_on":
                crt = true;
                break;
            case "default_on":
                crt = !"off".equals(crtPreference);
                break;
            case "default_off":
                crt = "on".equals(crtPreference);
                break;
            default:
                crt = false;
        }
        RelayConfig.log(TAG, "directory: " + teams.size() + " teams, " + channels.size() + " channels, " + favorites.size() + " favourites, crt=" + crt + " in " + (System.currentTimeMillis() - started) + " ms");
        return new Snapshot(System.currentTimeMillis(), teams, channels, members, new ArrayList<>(favorites), crt, dmByUser);
    }

    // MARK: - Peers

    /** The Telegram dialog id of a channel: the other user for DMs, {@code -channelTlId} otherwise; 0 when unknown. */
    long dialogId(Channel channel) {
        if (channel.isDirect()) {
            String other = channel.getDirectMessageTeammateId(account.session.userId);
            return other != null ? account.userTlId(other) : 0;
        }
        return -account.db().tlId(RelayDatabase.KIND_CHANNEL, channel.id);
    }

    /** The Mattermost channel of a dialog id, or null (e.g. a user without a DM). */
    String channelIdOf(long dialogId) throws MattermostException {
        if (dialogId > 0) {
            String userId = account.db().mmId(RelayDatabase.KIND_USER, dialogId);
            if (userId == null) {
                return null;
            }
            String channelId = any().dmByUser.get(userId);
            if (channelId == null) {
                channelId = get(0).dmByUser.get(userId);
            }
            return channelId;
        }
        if (dialogId < 0) {
            return account.db().mmId(RelayDatabase.KIND_CHANNEL, -dialogId);
        }
        return null;
    }

    String channelIdOf(TLRPC.InputPeer peer) throws MattermostException {
        return channelIdOf(dialogIdOf(peer));
    }

    long dialogIdOf(TLRPC.InputPeer peer) {
        if (peer instanceof TLRPC.TL_inputPeerSelf) {
            return account.selfTlId();
        }
        if (peer instanceof TLRPC.TL_inputPeerUser || peer instanceof TLRPC.TL_inputPeerUserFromMessage) {
            return peer.user_id;
        }
        if (peer instanceof TLRPC.TL_inputPeerChannel || peer instanceof TLRPC.TL_inputPeerChannelFromMessage) {
            return -peer.channel_id;
        }
        if (peer instanceof TLRPC.TL_inputPeerChat) {
            return -peer.chat_id;
        }
        return 0;
    }

    /** The channel itself, from the snapshot or (not a member / just created) the server. */
    Channel channel(String channelId) throws MattermostException {
        Channel channel = any().channels.get(channelId);
        if (channel == null) {
            channel = account.client.channel(channelId);
        }
        return channel;
    }

    TLRPC.Peer peer(Channel channel) {
        long did = dialogId(channel);
        if (did > 0) {
            TLRPC.TL_peerUser p = new TLRPC.TL_peerUser();
            p.user_id = did;
            return p;
        }
        TLRPC.TL_peerChannel p = new TLRPC.TL_peerChannel();
        p.channel_id = -did;
        return p;
    }

    TLRPC.InputPeer inputPeer(Channel channel) {
        long did = dialogId(channel);
        if (did > 0) {
            TLRPC.TL_inputPeerUser p = new TLRPC.TL_inputPeerUser();
            p.user_id = did;
            String other = channel.getDirectMessageTeammateId(account.session.userId);
            p.access_hash = RelayUsers.accessHash(other != null ? other : "");
            return p;
        }
        TLRPC.TL_inputPeerChannel p = new TLRPC.TL_inputPeerChannel();
        p.channel_id = -did;
        p.access_hash = RelayUsers.accessHash(channel.id);
        return p;
    }

    /** The other member of a DM (self for the self-DM), or null for other channels. */
    String dmUser(Channel channel) {
        return channel.isDirect() ? channel.getDirectMessageTeammateId(account.session.userId) : null;
    }

    /** The megagroup for a GM or channel. */
    TLRPC.TL_channel tlChannel(Channel channel) {
        TLRPC.TL_channel c = new TLRPC.TL_channel();
        c.id = -dialogId(channel);
        c.access_hash = RelayUsers.accessHash(channel.id);
        c.flags |= TLObject.FLAG_13;
        String title = channel.displayName != null ? channel.displayName.trim() : "";
        c.title = title.isEmpty() ? channel.name : title;
        c.photo = new TLRPC.TL_chatPhotoEmpty();
        c.date = (int) (channel.createAt / 1000);
        c.megagroup = true;
        c.broadcast = false;
        c.left = false;
        c.min = false;
        c.creator = account.session.userId.equals(channel.creatorId);
        // Nothing banned by default (null would read as "restricted" for TL_channel); roles come in A7.
        TLRPC.TL_chatBannedRights rights = new TLRPC.TL_chatBannedRights();
        rights.until_date = Integer.MAX_VALUE;
        c.default_banned_rights = rights;
        c.flags |= TLObject.FLAG_18;
        return c;
    }

    boolean isMuted(Snapshot snapshot, String channelId) {
        ChannelMember member = snapshot.members.get(channelId);
        return member != null && member.isMuted();
    }

    /** Unread posts: {@code total − msg_count}, the {@code _root} counters with collapsed threads (as iOS does). */
    static int unreadCount(Snapshot snapshot, Channel channel, ChannelMember member) {
        if (member == null) {
            return 0;
        }
        long unread = snapshot.collapsedThreads ? channel.totalMsgCountRoot - member.msgCountRoot : channel.totalMsgCount - member.msgCount;
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, unread));
    }

    /** The mention badge: channels only (every DM/GM post counts as a mention in Mattermost, so iOS shows none there). */
    static int mentionCount(Snapshot snapshot, Channel channel, ChannelMember member) {
        if (member == null || channel.isDirect() || channel.isGroup()) {
            return 0;
        }
        long count = snapshot.collapsedThreads ? member.mentionCountRoot : member.mentionCount;
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, count));
    }

    /** Channels of the snapshot sorted by id (stable iteration). */
    static List<Channel> sortedChannels(Snapshot snapshot) {
        List<Channel> list = new ArrayList<>(snapshot.channels.values());
        Collections.sort(list, (a, b) -> a.id.compareTo(b.id));
        return list;
    }
}
