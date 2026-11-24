package org.telegram.relay.account;

import org.telegram.relay.mattermost.model.User;
import org.telegram.relay.mattermost.model.UserStatus;
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

    /** The data centre id every Relay file and avatar claims (FileLoader rejects 0). */
    public static final int DC_ID = 2;

    /**
     * Builds the {@code TL_user} for a Mattermost user. Name: first/last name when set, else the
     * nickname, else the username.
     *
     * @param avatarPhotoId the TL photo id of the user's current picture ({@code KIND_AVATAR}), or 0 for
     *                      none (the client draws its letter avatar)
     * @param status        Mattermost presence, or null when unknown ("recently")
     */
    public static TLRPC.TL_user toTlUser(User user, long tlId, boolean self, long avatarPhotoId, UserStatus status) {
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
        if (!username.isEmpty() && username.matches("[A-Za-z0-9_.\\-]+")) {
            u.username = username;
            u.flags |= TLObject.FLAG_3;
        }
        u.phone = "";
        u.flags |= TLObject.FLAG_4;

        if (avatarPhotoId != 0) {
            TLRPC.TL_userProfilePhoto photo = new TLRPC.TL_userProfilePhoto();
            photo.photo_id = avatarPhotoId;
            photo.dc_id = DC_ID;
            u.photo = photo;
        } else {
            u.photo = new TLRPC.TL_userProfilePhotoEmpty();
        }
        u.flags |= TLObject.FLAG_5;

        u.status = status(self, status);
        u.flags |= TLObject.FLAG_6;

        u.bot = false;
        u.deleted = false;
        u.premium = false;
        u.verified = false;
        u.contact = false;
        return u;
    }

    /** A2 signature: no avatar, unknown presence. */
    public static TLRPC.TL_user toTlUser(User user, long tlId, boolean self) {
        return toTlUser(user, tlId, self, 0, null);
    }

    /**
     * Mattermost presence → Telegram: online and dnd → online (5 min, refreshed by every response that
     * carries the user, live in A4), away → "recently", offline → "last seen" at {@code last_activity_at}.
     * Self is always online while the app runs.
     */
    public static TLRPC.UserStatus status(boolean self, UserStatus status) {
        int now = ConnectionsManager.getInstance(0).getCurrentTime();
        if (self || (status != null && (UserStatus.ONLINE.equals(status.status) || UserStatus.DND.equals(status.status)))) {
            TLRPC.TL_userStatusOnline online = new TLRPC.TL_userStatusOnline();
            online.expires = now + 300;
            return online;
        }
        if (status != null && UserStatus.OFFLINE.equals(status.status) && status.lastActivityAt > 0) {
            TLRPC.TL_userStatusOffline offline = new TLRPC.TL_userStatusOffline();
            offline.expires = (int) Math.min(now, status.lastActivityAt / 1000);
            return offline;
        }
        return new TLRPC.TL_userStatusRecently();
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
}
