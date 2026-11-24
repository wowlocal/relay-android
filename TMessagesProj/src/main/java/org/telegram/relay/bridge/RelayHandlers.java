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
        map.put(TLRPC.TL_updates_getDifference.class, (bridge, account, req) -> {
            TLRPC.TL_updates_getDifference r = (TLRPC.TL_updates_getDifference) req;
            int commonPts = account.db().pts(RelayDatabase.BOX_COMMON);
            if (r.pts == commonPts && !RelayReadPath.of(account).needsReset()) {
                TLRPC.TL_updates_differenceEmpty empty = new TLRPC.TL_updates_differenceEmpty();
                empty.date = RelayStubs.now(bridge.account);
                empty.seq = 0;
                return empty;
            }
            // A2 keeps no update log yet: any gap re-lists the dialogs (resetDialogs).
            TLRPC.TL_updates_differenceTooLong tooLong = new TLRPC.TL_updates_differenceTooLong();
            tooLong.pts = commonPts;
            return tooLong;
        });
        map.put(TLRPC.TL_updates_getChannelDifference.class, (bridge, account, req) -> {
            TLRPC.TL_updates_getChannelDifference r = (TLRPC.TL_updates_getChannelDifference) req;
            long channelId = r.channel != null ? r.channel.channel_id : 0;
            TLRPC.TL_updates_channelDifferenceEmpty empty = new TLRPC.TL_updates_channelDifferenceEmpty();
            empty.isFinal = true;
            empty.pts = Math.max(r.pts, account.db().pts(RelayDatabase.channelBox(channelId)));
            empty.timeout = 30;
            empty.flags |= TLObject.FLAG_1;
            return empty;
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

        // MARK: files (§8)
        map.put(TLRPC.TL_upload_getFile.class, (bridge, account, req) -> RelayReadPath.of(account).files.getFile((TLRPC.TL_upload_getFile) req));

        // MARK: config
        map.put(TLRPC.TL_help_getAppConfig.class, (bridge, account, req) -> RelayAppConfig.answer((TLRPC.TL_help_getAppConfig) req));
        WITHOUT_SESSION.add(TLRPC.TL_help_getAppConfig.class);

        // MARK: auth. The Mattermost logout and the wipe run from MessagesController.performLogout
        // (RelayAuth.onTelegramLogout); this only answers the request.
        map.put(TLRPC.TL_auth_logOut.class, (bridge, account, req) -> new TLRPC.TL_auth_loggedOut());
        WITHOUT_SESSION.add(TLRPC.TL_auth_logOut.class);
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
