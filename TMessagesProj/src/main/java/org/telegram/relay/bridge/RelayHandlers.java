package org.telegram.relay.bridge;

import org.telegram.relay.account.RelayAccount;
import org.telegram.relay.account.RelayDatabase;
import org.telegram.relay.mattermost.model.User;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Map;

/**
 * Real handlers: methods that need the account's Mattermost session or bridge state. A2 covers boot
 * (state, difference, dialogs, filters, self user, app config); A3+ grow this table.
 */
final class RelayHandlers {
    private RelayHandlers() {
    }

    interface Handler {
        /** Runs on the account's bridge executor; may block on Mattermost I/O. Returns a response or a TL_error. */
        TLObject handle(RelayBridge bridge, RelayAccount account, TLObject request) throws Exception;
    }

    /** Handlers that also answer when the slot has no Mattermost session (account may be null). */
    static final java.util.Set<Class<?>> WITHOUT_SESSION = new java.util.HashSet<>();

    static void register(Map<Class<?>, Handler> map) {
        // MARK: updates (§6.4)
        map.put(TLRPC.TL_updates_getState.class, (bridge, account, req) -> bridge.currentState(account));
        map.put(TLRPC.TL_updates_getDifference.class, (bridge, account, req) ->
                RelayLive.of(bridge, account).getDifference((TLRPC.TL_updates_getDifference) req));
        map.put(TLRPC.TL_updates_getChannelDifference.class, (bridge, account, req) ->
                RelayLive.of(bridge, account).getChannelDifference((TLRPC.TL_updates_getChannelDifference) req));
        // Typing in the composer → WebSocket user_typing (parent_id for threads).
        map.put(TLRPC.TL_messages_setTyping.class, (bridge, account, req) -> {
            TLRPC.TL_messages_setTyping r = (TLRPC.TL_messages_setTyping) req;
            if (r.action instanceof TLRPC.TL_sendMessageTypingAction) {
                RelayReadPath path = RelayReadPath.of(account);
                String channelId = path.directory.channelIdOf(r.peer);
                String parentId = null;
                if (channelId != null && r.top_msg_id != 0) {
                    RelayHistory.Unit unit = path.history.unitAt(channelId, r.top_msg_id);
                    if (unit != null) {
                        org.telegram.relay.mattermost.model.Post post = unit.layout.post;
                        parentId = post.isReply() ? post.rootId : post.id;
                    }
                }
                RelayLive.of(bridge, account).sendTyping(channelId, parentId);
            }
            return new TLRPC.TL_boolTrue();
        });

        // MARK: chat list (§4)
        map.put(TLRPC.TL_messages_getDialogs.class, (bridge, account, req) -> RelayReadPath.of(account).getDialogs((TLRPC.TL_messages_getDialogs) req));
        map.put(TLRPC.TL_messages_getPinnedDialogs.class, (bridge, account, req) -> RelayReadPath.of(account).getPinnedDialogs(bridge, (TLRPC.TL_messages_getPinnedDialogs) req));
        map.put(TLRPC.TL_messages_getPeerDialogs.class, (bridge, account, req) -> RelayReadPath.of(account).getPeerDialogs(bridge, (TLRPC.TL_messages_getPeerDialogs) req));
        map.put(TLRPC.TL_messages_getDialogFilters.class, (bridge, account, req) -> RelayReadPath.of(account).getDialogFilters());
        map.put(TLRPC.TL_channels_getChannels.class, (bridge, account, req) -> RelayReadPath.of(account).getChannels((TLRPC.TL_channels_getChannels) req));
        map.put(TLRPC.TL_messages_getChats.class, (bridge, account, req) -> new TLRPC.TL_messages_chats());
        map.put(TLRPC.TL_channels_getFullChannel.class, (bridge, account, req) -> RelayReadPath.of(account).getFullChannel((TLRPC.TL_channels_getFullChannel) req));

        // MARK: history (§5)
        map.put(TLRPC.TL_messages_getHistory.class, (bridge, account, req) -> RelayReadPath.of(account).getHistory((TLRPC.TL_messages_getHistory) req));
        map.put(TLRPC.TL_channels_getMessages.class, (bridge, account, req) -> {
            TLRPC.TL_channels_getMessages r = (TLRPC.TL_channels_getMessages) req;
            RelayReadPath path = RelayReadPath.of(account);
            return path.getMessages(account.db().mmId(RelayDatabase.KIND_CHANNEL, r.channel.channel_id), r.id);
        });
        map.put(TLRPC.TL_messages_getMessages.class, (bridge, account, req) -> {
            // Private-chat ids are per dialog in Relay (one allocator per chat): the caller tags the
            // request with its dialog (RelayHooks.tagDialog); untagged lookups answer messageEmpty.
            TLRPC.TL_messages_getMessages r = (TLRPC.TL_messages_getMessages) req;
            RelayReadPath path = RelayReadPath.of(account);
            long dialogId = RelayHooks.taggedDialog(req);
            return path.getMessages(dialogId != 0 ? path.directory.channelIdOf(dialogId) : null, r.id);
        });
        map.put(TLRPC.TL_messages_getUnreadMentions.class, (bridge, account, req) -> RelayReadPath.of(account).unreadMentions((TLRPC.TL_messages_getUnreadMentions) req));
        map.put(TLRPC.TL_messages_getOnlines.class, (bridge, account, req) -> new TLRPC.TL_chatOnlines());

        // MARK: read state (§5.6)
        map.put(TLRPC.TL_channels_readHistory.class, (bridge, account, req) -> {
            TLRPC.TL_channels_readHistory r = (TLRPC.TL_channels_readHistory) req;
            RelayReadPath.of(account).markRead(account.db().mmId(RelayDatabase.KIND_CHANNEL, r.channel.channel_id));
            return new TLRPC.TL_boolTrue();
        });
        map.put(TLRPC.TL_messages_readHistory.class, (bridge, account, req) -> {
            TLRPC.TL_messages_readHistory r = (TLRPC.TL_messages_readHistory) req;
            RelayReadPath path = RelayReadPath.of(account);
            path.markRead(path.directory.channelIdOf(r.peer));
            TLRPC.TL_messages_affectedMessages res = new TLRPC.TL_messages_affectedMessages();
            res.pts = account.db().pts(RelayDatabase.BOX_COMMON);
            res.pts_count = 0;
            return res;
        });

        // MARK: users
        map.put(TLRPC.TL_users_getFullUser.class, (bridge, account, req) -> RelayReadPath.of(account).getFullUser((TLRPC.TL_users_getFullUser) req));
        map.put(TLRPC.TL_users_getUsers.class, (bridge, account, req) -> {
            TLRPC.TL_users_getUsers r = (TLRPC.TL_users_getUsers) req;
            ArrayList<TLObject> users = new ArrayList<>();
            for (TLRPC.InputUser input : r.id) {
                TLRPC.TL_user user = resolveUser(account, input);
                if (user != null) {
                    users.add(user);
                }
            }
            return new RelayTl.RawVector(users);
        });
        map.put(TLRPC.TL_photos_getUserPhotos.class, (bridge, account, req) -> RelayReadPath.of(account).getUserPhotos((TLRPC.TL_photos_getUserPhotos) req));
        map.put(TLRPC.TL_contacts_resolveUsername.class, (bridge, account, req) -> RelayReadPath.of(account).resolveUsername((TLRPC.TL_contacts_resolveUsername) req));
        map.put(TLRPC.TL_contacts_getContacts.class, (bridge, account, req) -> {
            TLRPC.TL_contacts_contacts res = new TLRPC.TL_contacts_contacts();
            res.saved_count = 0;
            return res;
        });

        // Before editing, the composer asks whether the message may be edited (own posts; caption for files).
        map.put(TLRPC.TL_messages_getMessageEditData.class, (bridge, account, req) -> {
            TLRPC.TL_messages_getMessageEditData r = (TLRPC.TL_messages_getMessageEditData) req;
            RelayReadPath path = RelayReadPath.of(account);
            String channelId = path.directory.channelIdOf(r.peer);
            RelayHistory.Unit unit = channelId != null ? path.history.unitAt(channelId, r.id) : null;
            if (unit == null || !account.session.userId.equals(unit.layout.post.userId) || unit.layout.service) {
                return RelayTl.error(400, "MESSAGE_AUTHOR_REQUIRED");
            }
            TLRPC.TL_messages_messageEditData res = new TLRPC.TL_messages_messageEditData();
            res.caption = !unit.layout.files.isEmpty();
            return res;
        });

        // MARK: write path (§7, A5): queued per account, answered when Mattermost has it
        map.put(TLRPC.TL_messages_sendMessage.class, (bridge, account, req) -> RelayWrite.of(bridge, account).sendMessage((TLRPC.TL_messages_sendMessage) req));
        map.put(TLRPC.TL_messages_sendMedia.class, (bridge, account, req) -> RelayWrite.of(bridge, account).sendMedia((TLRPC.TL_messages_sendMedia) req));
        map.put(TLRPC.TL_messages_uploadMedia.class, (bridge, account, req) -> RelayWrite.of(bridge, account).uploadMedia((TLRPC.TL_messages_uploadMedia) req));
        map.put(TLRPC.TL_messages_sendMultiMedia.class, (bridge, account, req) -> RelayWrite.of(bridge, account).sendMultiMedia((TLRPC.TL_messages_sendMultiMedia) req));
        map.put(TLRPC.TL_messages_editMessage.class, (bridge, account, req) -> RelayWrite.of(bridge, account).editMessage((TLRPC.TL_messages_editMessage) req));
        map.put(TLRPC.TL_messages_deleteMessages.class, (bridge, account, req) -> RelayWrite.of(bridge, account).deleteMessages((TLRPC.TL_messages_deleteMessages) req));
        map.put(TLRPC.TL_channels_deleteMessages.class, (bridge, account, req) -> RelayWrite.of(bridge, account).deleteChannelMessages((TLRPC.TL_channels_deleteMessages) req));
        map.put(TLRPC.TL_messages_sendReaction.class, (bridge, account, req) -> RelayWrite.of(bridge, account).sendReaction((TLRPC.TL_messages_sendReaction) req));
        map.put(TLRPC.TL_messages_forwardMessages.class, (bridge, account, req) -> RelayWrite.of(bridge, account).forwardMessages((TLRPC.TL_messages_forwardMessages) req));
        map.put(TLRPC.TL_messages_markDialogUnread.class, (bridge, account, req) -> RelayWrite.of(bridge, account).markDialogUnread((TLRPC.TL_messages_markDialogUnread) req));
        // Upload parts are buffered on disk (transfer pool); the file goes to Mattermost with sendMedia/uploadMedia.
        map.put(TLRPC.TL_upload_saveFilePart.class, (bridge, account, req) -> {
            TLRPC.TL_upload_saveFilePart r = (TLRPC.TL_upload_saveFilePart) req;
            return RelayUploads.savePart(bridge.account, r.file_id, r.file_part, r.bytes) ? new TLRPC.TL_boolTrue() : RelayTl.error(400, "FILE_PART_INVALID");
        });
        map.put(TLRPC.TL_upload_saveBigFilePart.class, (bridge, account, req) -> {
            TLRPC.TL_upload_saveBigFilePart r = (TLRPC.TL_upload_saveBigFilePart) req;
            return RelayUploads.savePart(bridge.account, r.file_id, r.file_part, r.bytes) ? new TLRPC.TL_boolTrue() : RelayTl.error(400, "FILE_PART_INVALID");
        });

        // MARK: files (§8)
        map.put(TLRPC.TL_upload_getFile.class, (bridge, account, req) -> RelayReadPath.of(account).files.getFile((TLRPC.TL_upload_getFile) req));

        // MARK: A7c: reactions, emoji, composer, profile and status, notifications (one block; see each class)
        RelayEmoji.register(map);
        RelayReactions.register(map);
        RelayComposer.register(map);
        RelayProfile.register(map);
        RelayNotifications.register(map);

        // MARK: config
        map.put(TLRPC.TL_help_getAppConfig.class, (bridge, account, req) -> RelayAppConfig.answer((TLRPC.TL_help_getAppConfig) req));
        WITHOUT_SESSION.add(TLRPC.TL_help_getAppConfig.class);

        // MARK: auth. The Mattermost logout and the wipe run from MessagesController.performLogout
        // (RelayAuth.onTelegramLogout); this only answers the request.
        map.put(TLRPC.TL_auth_logOut.class, (bridge, account, req) -> new TLRPC.TL_auth_loggedOut());
        WITHOUT_SESSION.add(TLRPC.TL_auth_logOut.class);

        // A7a: threads (CRT), permalinks / Copy Link, jump to date.
        RelayThreads.register(map);
        RelayPermalinks.register(map);
    }

    /** The TL user for an InputUser: self from {@code /users/me}, others from the cache or {@code /users/{id}}. */
    static TLRPC.TL_user resolveUser(RelayAccount account, TLRPC.InputUser input) throws Exception {
        String mmId;
        if (input instanceof TLRPC.TL_inputUserSelf || (input != null && input.user_id == account.selfTlId())) {
            mmId = account.session.userId;
            if (account.cachedUser(mmId) == null) {
                account.rememberUser(account.client.me());
            }
        } else if (input != null) {
            mmId = account.db().mmId(RelayDatabase.KIND_USER, input.user_id);
            if (mmId == null) {
                return null;
            }
            if (account.cachedUser(mmId) == null) {
                User user = account.client.user(mmId);
                account.rememberUser(user);
            }
        } else {
            return null;
        }
        return account.tlUser(mmId);
    }
}
