package org.telegram.relay.account;

import org.telegram.relay.mattermost.model.User;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

/** Mattermost users → {@code TL_user}. */
public final class RelayUsers {
    private RelayUsers() {
    }

    /** Stable non-zero access hash for a Mattermost id (FNV-1a 64). */
    public static long accessHash(String mmId) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < mmId.length(); i++) {
            hash ^= mmId.charAt(i);
            hash *= 0x100000001b3L;
        }
        return hash == 0 ? 1 : hash;
    }

    /**
     * Builds the {@code TL_user} for a Mattermost user. Name: first/last name when set, else the
     * nickname, else the username. {@code status}: online for self (the app is in use), otherwise
     * "recently" until A4 maps Mattermost presence. Photo: none yet (A3 maps profile images).
     */
    public static TLRPC.TL_user toTlUser(User user, long tlId, boolean self) {
        TLRPC.TL_user u = new TLRPC.TL_user();
        u.id = tlId;
        u.self = self;
        u.access_hash = accessHash(user.id);
        u.flags |= TLObject.FLAG_0;

        String first = trim(user.firstName);
        String last = trim(user.lastName);
        if (first.isEmpty() && last.isEmpty()) {
            String nickname = trim(user.nickname);
            first = !nickname.isEmpty() ? nickname : trim(user.username);
        }
        u.first_name = first;
        u.flags |= TLObject.FLAG_1;
        if (!last.isEmpty()) {
            u.last_name = last;
            u.flags |= TLObject.FLAG_2;
        }
        String username = trim(user.username);
        if (!username.isEmpty()) {
            u.username = username;
            u.flags |= TLObject.FLAG_3;
        }
        u.phone = "";
        u.flags |= TLObject.FLAG_4;

        u.photo = new TLRPC.TL_userProfilePhotoEmpty();
        u.flags |= TLObject.FLAG_5;

        int now = ConnectionsManager.getInstance(0).getCurrentTime();
        if (self) {
            TLRPC.TL_userStatusOnline online = new TLRPC.TL_userStatusOnline();
            online.expires = now + 300;
            u.status = online;
        } else {
            u.status = new TLRPC.TL_userStatusRecently();
        }
        u.flags |= TLObject.FLAG_6;

        u.bot = false;
        u.deleted = false;
        u.premium = false;
        u.verified = false;
        u.contact = false;
        return u;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
