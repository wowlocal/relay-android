package org.telegram.relay.bridge;

import com.google.gson.JsonObject;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.relay.RelayConfig;
import org.telegram.relay.account.RelayAccount;
import org.telegram.relay.account.RelayDatabase;
import org.telegram.relay.mattermost.MattermostDirectory;
import org.telegram.relay.mattermost.MattermostException;
import org.telegram.relay.mattermost.model.Channel;
import org.telegram.relay.mattermost.model.ChannelStats;
import org.telegram.relay.mattermost.model.Team;
import org.telegram.tgnet.SerializedData;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Phase A7b: the bridge's API for Relay's own screens and the small hooks in Telegram's (saved posts,
 * recent mentions, browse/join channels, new channel, group message, archive state, header). Work runs on
 * the account's bridge executor; callbacks run on the UI thread with copies of the TL objects (parsed back
 * from the wire, never the bridge's own).
 */
public final class RelayFinding {
    private static final String TAG = RelayConfig.TAG_BRIDGE;

    private RelayFinding() {
    }

    public interface Callback<T> {
        /** UI thread. {@code error} is null on success. */
        void run(T result, String error);
    }

    private interface Task<T> {
        T run(RelayBridge bridge, RelayAccount account) throws Exception;
    }

    private static <T> void async(int accountNum, String what, Task<T> task, Callback<T> callback) {
        RelayAccount account = RelayAccount.get(accountNum);
        if (account == null) {
            if (callback != null) {
                AndroidUtilities.runOnUIThread(() -> callback.run(null, "not logged in"));
            }
            return;
        }
        RelayBridge bridge = RelayBridge.get(accountNum);
        bridge.post(() -> {
            T result = null;
            String error = null;
            try {
                result = task.run(bridge, account);
            } catch (MattermostException e) {
                RelayConfig.logError(TAG, what + " failed", e);
                error = e.getAppError() != null && e.getAppError().message != null && !e.getAppError().message.isEmpty() ? e.getAppError().message : e.getMessage();
            } catch (Throwable t) {
                RelayConfig.logError(TAG, what + " failed", t);
                error = t.getMessage() != null ? t.getMessage() : t.toString();
            }
            if (callback != null) {
                final T finalResult = result;
                final String finalError = error;
                AndroidUtilities.runOnUIThread(() -> callback.run(finalResult, finalError));
            }
        });
    }

    static TLRPC.Chat copy(TLRPC.Chat chat) {
        if (chat == null) {
            return null;
        }
        SerializedData in = new SerializedData(RelayTl.serialize(chat));
        try {
            return TLRPC.Chat.TLdeserialize(in, in.readInt32(true), true);
        } finally {
            in.cleanup();
        }
    }

    static TLRPC.messages_Messages copy(TLRPC.messages_Messages messages) {
        SerializedData in = new SerializedData(RelayTl.serialize(messages));
        try {
            return TLRPC.messages_Messages.TLdeserialize(in, in.readInt32(true), true);
        } finally {
            in.cleanup();
        }
    }

    // MARK: - Rights and state of a chat (UI hooks; no I/O)

    private static RelayMembers.Rights rights(long chatId) {
        return RelayMembers.rightsByChat.get(Math.abs(chatId));
    }

    /** True when the chat is an archived Mattermost channel (read-only). */
    /** Polish 2a (QA-20): Mattermost channel type per chat: O public, P private, G group message. */
    private static final Map<Long, String> kinds = new java.util.concurrent.ConcurrentHashMap<>();

    static void noteKind(long chatId, Channel channel) {
        if (channel.type != null && !channel.type.isEmpty()) {
            kinds.put(Math.abs(chatId), channel.type);
        }
    }

    /** "O", "P", "G" or null when unknown. */
    public static String kindOf(long chatId) {
        return kinds.get(Math.abs(chatId));
    }

    /** The lower-case type label of a chat for lists ("public channel", "private channel", "group message"), or null. */
    public static String typeLabel(long chatId) {
        String kind = kindOf(chatId);
        if (kind == null) {
            return null;
        }
        switch (kind) {
            case "O":
                return org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.ChannelPublic).toLowerCase();
            case "P":
                return org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.ChannelPrivate).toLowerCase();
            case "G":
                return org.telegram.messenger.LocaleController.getString(org.telegram.messenger.R.string.RelayGroupMessageLabel).toLowerCase();
            default:
                return null;
        }
    }

    public static boolean isArchived(long chatId) {
        RelayMembers.Rights r = rights(chatId);
        return r != null && r.archived;
    }

    public static boolean canArchive(long chatId) {
        RelayMembers.Rights r = rights(chatId);
        return r != null && !r.archived && r.archive;
    }

    public static boolean canUnarchive(long chatId) {
        RelayMembers.Rights r = rights(chatId);
        return r != null && r.archived && r.unarchive;
    }

    public static boolean canChangeInfo(long chatId) {
        RelayMembers.Rights r = rights(chatId);
        return r != null && !r.archived && r.changeInfo;
    }

    /** Purpose and header of channels whose info was loaded, by TL chat id. */
    static final Map<Long, String[]> infoByChat = Collections.synchronizedMap(new HashMap<Long, String[]>());

    /** The channel's purpose (Telegram's description) as last loaded, or null. */
    public static String purpose(long chatId) {
        String[] info = infoByChat.get(Math.abs(chatId));
        return info != null ? info[0] : null;
    }

    /** The channel's header as last loaded, or null. */
    public static String header(long chatId) {
        String[] info = infoByChat.get(Math.abs(chatId));
        return info != null ? info[1] : null;
    }

    /** Called from {@code RelayReadPath.getFullChannel}: purpose/header cache, newest pinned message. */
    static void fillFull(RelayAccount account, Channel channel, TLRPC.TL_channelFull full) {
        infoByChat.put(full.id, new String[]{channel.purpose != null ? channel.purpose : "", channel.header != null ? channel.header : ""});
        try {
            RelaySearch.of(account).flaggedIds(false); // the message menu's Save/Remove reads the cache
        } catch (MattermostException e) {
            RelayConfig.logError(TAG, "saved ids", e);
        }
        if (!channel.isGroup()) {
            try {
                int admins = 0;
                for (org.telegram.relay.mattermost.model.ChannelMember member : MattermostDirectory.channelMembers(account.client, channel.id, 0, 200)) {
                    if (member.schemeAdmin) {
                        admins++;
                    }
                }
                full.admins_count = admins;
                full.flags |= TLObject.FLAG_1;
            } catch (MattermostException e) {
                RelayConfig.logError(TAG, "channel admins", e);
            }
        }
        int pinned = RelaySearch.of(account).newestPinnedId(channel);
        if (pinned != 0) {
            full.pinned_msg_id = pinned;
            full.flags |= TLObject.FLAG_5;
        }
    }

    // MARK: - Saved posts and recent mentions

    /** The user's saved (flagged) posts, newest first, as messages of their chats. */
    public static void loadSaved(int account, Callback<TLRPC.messages_Messages> callback) {
        async(account, "saved posts", (bridge, a) -> found(a, RelaySearch.of(a).saved()), callback);
    }

    /** Mattermost's "Recent Mentions": posts that mention the user, newest first. */
    public static void loadMentions(int account, Callback<TLRPC.messages_Messages> callback) {
        async(account, "recent mentions", (bridge, a) -> found(a, RelaySearch.of(a).mentions()), callback);
    }

    /** Recent Mentions from the server, not the minute-long cache (the Mentions tab, after a new mention arrived). */
    public static void reloadMentions(int account, Callback<TLRPC.messages_Messages> callback) {
        async(account, "recent mentions", (bridge, a) -> {
            RelaySearch search = RelaySearch.of(a);
            search.forget("mentions");
            return found(a, search.mentions());
        }, callback);
    }

    private static TLRPC.messages_Messages found(RelayAccount account, RelaySearch.Found found) throws MattermostException {
        TLRPC.TL_messages_messages res = new TLRPC.TL_messages_messages();
        res.messages.addAll(found.messages);
        RelaySearch.of(account).fillPeers(res, found);
        return copy(res);
    }

    private static String postIdOf(RelayAccount account, long dialogId, int messageId) throws Exception {
        RelayReadPath path = RelayReadPath.of(account);
        String channelId = path.directory.channelIdOf(dialogId);
        RelayHistory.Unit unit = channelId != null ? path.history.unitAt(channelId, messageId) : null;
        return unit != null ? unit.layout.post.id : null;
    }

    /** Loads the saved-post ids (so {@link #isSaved} answers from the cache). */
    public static void prefetchSaved(int account) {
        async(account, "saved ids", (bridge, a) -> RelaySearch.of(a).flaggedIds(false).size(), null);
    }

    /** Whether a message's post is saved, from the cache (the message menu is built synchronously). */
    public static boolean isSaved(int accountNum, long dialogId, int messageId) {
        RelayAccount account = RelayAccount.get(accountNum);
        if (account == null) {
            return false;
        }
        RelaySearch search = RelaySearch.of(account);
        if (!search.flaggedLoaded()) {
            prefetchSaved(accountNum);
            return false;
        }
        // No I/O here (UI thread): the channel from the id map or the last directory snapshot.
        String channelId = null;
        if (dialogId < 0) {
            channelId = account.db().mmId(RelayDatabase.KIND_CHANNEL, -dialogId);
        } else {
            String userId = account.db().mmId(RelayDatabase.KIND_USER, dialogId);
            RelayDirectory.Snapshot snapshot = RelayReadPath.of(account).directory.peek();
            if (userId != null && snapshot != null) {
                channelId = snapshot.dmByUser.get(userId);
            }
        }
        if (channelId == null) {
            return false;
        }
        org.telegram.relay.mattermost.ids.MessageIdEntry entry = account.messageIdStore().entryByLocalId(channelId, messageId);
        return entry != null && RelaySearch.of(account).isFlaggedCached(entry.serverPostId());
    }

    /** Debug/e2e: the Mattermost post of a message (no I/O), or null. */
    public static String debugPostId(int accountNum, long dialogId, int messageId) {
        RelayAccount account = RelayAccount.get(accountNum);
        if (account == null) {
            return null;
        }
        String channelId = null;
        if (dialogId < 0) {
            channelId = account.db().mmId(RelayDatabase.KIND_CHANNEL, -dialogId);
        } else {
            String userId = account.db().mmId(RelayDatabase.KIND_USER, dialogId);
            RelayDirectory.Snapshot snapshot = RelayReadPath.of(account).directory.peek();
            if (userId != null && snapshot != null) {
                channelId = snapshot.dmByUser.get(userId);
            }
        }
        if (channelId == null) {
            return null;
        }
        org.telegram.relay.mattermost.ids.MessageIdEntry entry = account.messageIdStore().entryByLocalId(channelId, messageId);
        return entry != null ? entry.serverPostId() : null;
    }

    /** Debug/e2e: the Mattermost user of a TL user id, or null. */
    public static String debugUserId(int accountNum, long tlUserId) {
        RelayAccount account = RelayAccount.get(accountNum);
        return account != null ? account.db().mmId(RelayDatabase.KIND_USER, tlUserId) : null;
    }

    /** Debug/e2e: the TL chat id of a Mattermost channel (0 when unmapped). */
    public static long debugChatId(int accountNum, String channelId) {
        RelayAccount account = RelayAccount.get(accountNum);
        return account != null ? account.db().findTlId(RelayDatabase.KIND_CHANNEL, channelId) : 0;
    }

    /** Saves or unsaves the post of a message (Mattermost's flag). */
    public static void setSaved(int account, long dialogId, int messageId, boolean saved, Callback<Boolean> callback) {
        async(account, "save post", (bridge, a) -> {
            String postId = postIdOf(a, dialogId, messageId);
            if (postId == null) {
                throw new IllegalStateException("no post for message " + messageId);
            }
            RelaySearch.of(a).setFlagged(postId, saved);
            return saved;
        }, callback);
    }

    // MARK: - Channels: browse, join, create, archive

    /** One row of the channel browser. */
    public static final class ChannelRow {
        public TLRPC.Chat chat;
        public String teamName;
        public String purpose;
        public int memberCount;
        public boolean joined;
        public boolean archived;
        public boolean isPrivate;
    }

    /** The user's teams: id → display name, sorted by name. */
    public static void loadTeams(int account, Callback<LinkedHashMap<String, String>> callback) {
        async(account, "teams", (bridge, a) -> RelayMembers.of(a).teams(), callback);
    }

    /**
     * Public channels of a team (or archived ones) matching {@code query}, joined or not, sorted by name;
     * the user's private channels of the team are listed too (they are only visible to members).
     */
    public static void browseChannels(int account, String teamId, boolean archived, String query, Callback<List<ChannelRow>> callback) {
        async(account, "browse channels", (bridge, a) -> {
            RelayReadPath path = RelayReadPath.of(a);
            RelayDirectory.Snapshot snapshot = path.directory.get(10_000);
            String q = query != null ? query.trim().toLowerCase(Locale.ROOT) : "";
            Map<String, Channel> found = new LinkedHashMap<>();
            if (!q.isEmpty()) {
                for (Channel channel : MattermostDirectory.searchTeamChannels(a.client, teamId, q, archived)) {
                    found.put(channel.id, channel);
                }
            } else {
                for (int page = 0; page < 5; page++) {
                    List<Channel> list = MattermostDirectory.teamChannels(a.client, teamId, archived, page, 200);
                    for (Channel channel : list) {
                        found.put(channel.id, channel);
                    }
                    if (list.size() < 200) {
                        break;
                    }
                }
            }
            if (archived) {
                // Archived channels the user is in (private ones are not listed by the team).
                for (Channel channel : MattermostDirectory.myChannelsWithArchived(a.client)) {
                    if (channel.isArchived() && teamId.equals(channel.teamId) && matches(channel, q)) {
                        found.put(channel.id, channel);
                    }
                }
            } else {
                for (Channel channel : snapshot.channels.values()) {
                    if (channel.isPrivate() && teamId.equals(channel.teamId) && matches(channel, q)) {
                        found.put(channel.id, channel);
                    }
                }
            }
            String teamName = teamId;
            for (Team team : snapshot.teams) {
                if (team.id.equals(teamId)) {
                    teamName = team.displayName;
                }
            }
            List<ChannelRow> rows = new ArrayList<>();
            for (Channel channel : found.values()) {
                if (channel.isArchived() != archived || channel.isDirect() || channel.isGroup()) {
                    continue;
                }
                ChannelRow row = new ChannelRow();
                row.chat = copy(path.directory.tlChannel(channel));
                row.teamName = teamName;
                row.purpose = channel.purpose != null ? channel.purpose.trim() : "";
                row.joined = snapshot.channels.containsKey(channel.id) || (archived && snapshot.members.containsKey(channel.id));
                row.archived = channel.isArchived();
                row.isPrivate = channel.isPrivate();
                if (rows.size() < 100) {
                    try {
                        ChannelStats stats = a.client.channelStats(channel.id);
                        row.memberCount = (int) stats.memberCount;
                    } catch (MattermostException e) {
                        row.memberCount = -1;
                    }
                }
                rows.add(row);
            }
            Collections.sort(rows, (x, y) -> x.chat.title.compareToIgnoreCase(y.chat.title));
            RelayConfig.log(TAG, "browse " + teamName + (archived ? " (archived)" : "") + " q=" + q.length() + " chars -> " + rows.size());
            return rows;
        }, callback);
    }

    private static boolean matches(Channel channel, String q) {
        if (q.isEmpty()) {
            return true;
        }
        return (channel.displayName != null && channel.displayName.toLowerCase(Locale.ROOT).contains(q)) || channel.name.toLowerCase(Locale.ROOT).contains(q);
    }

    /** A URL name suggestion for a display name (the New Channel screen). */
    public static String slug(String displayName) {
        return RelayMembers.slug(displayName);
    }

    public static boolean isValidChannelName(String name) {
        return RelayMembers.validName(name);
    }

    /** Creates a public or private channel; the result is the new chat (also announced to the client). */
    public static void createChannel(int account, String teamId, String displayName, String name, boolean isPrivate, String purpose, String header, Callback<TLRPC.Chat> callback) {
        async(account, "create channel", (bridge, a) -> {
            Channel channel = RelayMembers.of(a).createChannel(teamId, displayName, name, isPrivate, purpose, header);
            return announced(bridge, a, channel);
        }, callback);
    }

    private static TLRPC.Chat announced(RelayBridge bridge, RelayAccount a, Channel channel) throws MattermostException {
        RelayReadPath path = RelayReadPath.of(a);
        path.directory.invalidate();
        Channel fresh = path.directory.get(0).channels.get(channel.id);
        if (fresh == null) {
            fresh = channel;
        }
        if (!path.isKnown(fresh.id)) {
            RelayLive.of(bridge, a).announce(fresh);
        }
        return copy(path.directory.tlChannel(fresh));
    }

    /** Creates (or finds) the DM/GM with these users (TL user ids); the result is the dialog id. */
    public static void createGroupMessage(int account, long[] userIds, Callback<Long> callback) {
        async(account, "create group message", (bridge, a) -> {
            List<Long> ids = new ArrayList<>();
            for (long id : userIds) {
                ids.add(id);
            }
            Channel channel = RelayMembers.of(a).createGroup(ids);
            RelayReadPath path = RelayReadPath.of(a);
            if (!channel.isDirect()) {
                announced(bridge, a, channel);
            } else {
                path.directory.invalidate();
            }
            return path.directory.dialogId(channel);
        }, callback);
    }

    /** The chat of a dialog the client does not have yet (a GM just created): a copy for {@code putChat}. */
    public static void loadChat(int account, long chatId, Callback<TLRPC.Chat> callback) {
        async(account, "load chat", (bridge, a) -> {
            String channelId = a.db().mmId(RelayDatabase.KIND_CHANNEL, Math.abs(chatId));
            Channel channel = channelId != null ? RelayReadPath.of(a).directory.channel(channelId) : null;
            return channel != null ? copy(RelayReadPath.of(a).directory.tlChannel(channel)) : null;
        }, callback);
    }

    /** Unarchives a channel (team or system admins); the result is the updated chat. */
    public static void unarchive(int account, long chatId, Callback<TLRPC.Chat> callback) {
        async(account, "unarchive", (bridge, a) -> {
            String channelId = a.db().mmId(RelayDatabase.KIND_CHANNEL, Math.abs(chatId));
            if (channelId == null) {
                throw new IllegalStateException("unknown chat");
            }
            Channel channel = RelayMembers.of(a).unarchive(channelId);
            TLRPC.TL_updates updates = RelayMembers.of(a).channelUpdate(channel);
            RelayLive.of(bridge, a).updates.deliver(updates.updates, new RelayUpdates.Refs().chat(updates.chats.get(0)));
            return copy(updates.chats.get(0));
        }, callback);
    }

    /** Changes the channel header (Telegram has one description, which is the purpose). */
    public static void setHeader(int account, long chatId, String header, Callback<Boolean> callback) {
        async(account, "set header", (bridge, a) -> {
            String channelId = a.db().mmId(RelayDatabase.KIND_CHANNEL, Math.abs(chatId));
            Channel channel = channelId != null ? RelayReadPath.of(a).directory.channel(channelId) : null;
            if (channel == null) {
                throw new IllegalStateException("unknown chat");
            }
            JsonObject patch = new JsonObject();
            patch.addProperty("header", header != null ? header.trim() : "");
            Channel patched = RelayMembers.of(a).patch(channel, patch);
            if (patched == null) {
                throw new IllegalStateException("not allowed");
            }
            infoByChat.put(Math.abs(chatId), new String[]{patched.purpose, patched.header});
            return true;
        }, callback);
    }

    /**
     * Polish 2a: the users (TL ids) of the channel's team, for Add Members (Mattermost adds only team members to a
     * channel; iOS I5). Empty for a DM or GM.
     */
    public static void teamUsersOf(int account, long chatId, Callback<java.util.HashSet<Long>> callback) {
        async(account, "team users", (bridge, a) -> {
            java.util.HashSet<Long> ids = new java.util.HashSet<>();
            String channelId = a.db().mmId(RelayDatabase.KIND_CHANNEL, Math.abs(chatId));
            Channel channel = channelId != null ? RelayReadPath.of(a).directory.channel(channelId) : null;
            if (channel == null || channel.teamId == null || channel.teamId.isEmpty()) {
                return ids;
            }
            for (int page = 0; page < 25; page++) {
                List<org.telegram.relay.mattermost.model.User> users = MattermostDirectory.usersInTeam(a.client, channel.teamId, page, 200);
                for (org.telegram.relay.mattermost.model.User user : users) {
                    if (!user.isDeactivated()) {
                        a.rememberUser(user);
                        ids.add(a.userTlId(user.id));
                    }
                }
                if (users.size() < 200) {
                    break;
                }
            }
            RelayConfig.log(TAG, "team users of " + channel.name + ": " + ids.size());
            return ids;
        }, callback);
    }
}
