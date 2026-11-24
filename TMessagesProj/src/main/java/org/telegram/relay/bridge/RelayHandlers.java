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
            if (r.pts == commonPts) {
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
        map.put(TLRPC.TL_messages_getDialogs.class, (bridge, account, req) -> new TLRPC.TL_messages_dialogs());
        map.put(TLRPC.TL_messages_getPinnedDialogs.class, (bridge, account, req) -> {
            TLRPC.TL_messages_peerDialogs res = new TLRPC.TL_messages_peerDialogs();
            res.state = bridge.currentState(account);
            return res;
        });
        map.put(TLRPC.TL_messages_getPeerDialogs.class, (bridge, account, req) -> {
            TLRPC.TL_messages_peerDialogs res = new TLRPC.TL_messages_peerDialogs();
            res.state = bridge.currentState(account);
            return res;
        });
        map.put(TLRPC.TL_messages_getDialogFilters.class, (bridge, account, req) -> {
            TLRPC.TL_messages_dialogFilters res = new TLRPC.TL_messages_dialogFilters();
            res.filters.add(new TLRPC.TL_dialogFilterDefault());
            return res;
        });

        // MARK: users
        map.put(TLRPC.TL_users_getFullUser.class, (bridge, account, req) -> {
            TLRPC.TL_users_getFullUser r = (TLRPC.TL_users_getFullUser) req;
            TLRPC.TL_user user = resolveUser(account, r.id);
            if (user == null) {
                return RelayTl.error(400, "USER_ID_INVALID");
            }
            TLRPC.TL_userFull full = new TLRPC.TL_userFull();
            full.id = user.id;
            full.settings = new TLRPC.TL_peerSettings();
            full.notify_settings = RelayStubs.notifySettings();
            full.common_chats_count = 0;
            TLRPC.TL_users_userFull res = new TLRPC.TL_users_userFull();
            res.full_user = full;
            res.users.add(user);
            return res;
        });
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
        map.put(TLRPC.TL_contacts_getContacts.class, (bridge, account, req) -> {
            TLRPC.TL_contacts_contacts res = new TLRPC.TL_contacts_contacts();
            res.saved_count = 0;
            return res;
        });

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
