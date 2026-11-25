package org.telegram.relay.bridge;

import com.google.gson.JsonObject;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.relay.RelayConfig;
import org.telegram.relay.account.RelayAccount;
import org.telegram.relay.mattermost.FileUpload;
import org.telegram.relay.mattermost.MattermostException;
import org.telegram.relay.mattermost.model.CustomStatus;
import org.telegram.relay.mattermost.model.User;
import org.telegram.relay.mattermost.model.UserStatus;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.tgnet.tl.TL_update;

import java.io.File;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Own profile and status (A7c).
 *
 * <ul>
 *   <li>Edit Info: {@code account.updateProfile} → {@code PUT /users/me/patch} (first/last name; the "bio" is the
 *       Mattermost position), {@code account.updateUsername}/{@code checkUsername}, and {@link #saveProfile} for
 *       Relay's own fields (username, nickname).</li>
 *   <li>Avatar: {@code photos.uploadProfilePhoto} → {@code POST /users/me/image}; removing it
 *       ({@code photos.updateProfilePhoto(empty)}, {@code photos.deletePhotos}) → {@code DELETE /users/me/image}.</li>
 *   <li>Status (online / away / dnd with an end time / offline) and custom status (emoji, text, expiry) for the
 *       Settings screens; the custom status of every user is a Telegram emoji status ({@link #applyEmojiStatus}).</li>
 * </ul>
 */
public final class RelayProfile {
    private static final String TAG = RelayConfig.TAG_BRIDGE;

    private RelayProfile() {
    }

    public interface Callback<T> {
        /** On the UI thread: the result, or an error text. */
        void done(T result, String error);
    }

    static void register(Map<Class<?>, RelayHandlers.Handler> map) {
        map.put(TL_account.updateProfile.class, (bridge, account, req) -> updateProfile(account, (TL_account.updateProfile) req));
        map.put(TL_account.updateUsername.class, (bridge, account, req) -> {
            TL_account.updateUsername r = (TL_account.updateUsername) req;
            JsonObject patch = new JsonObject();
            patch.addProperty("username", r.username);
            try {
                User me = account.client.patchMe(patch);
                account.rememberUser(me);
                return account.tlUser(me.id);
            } catch (MattermostException e) {
                return usernameError(e);
            }
        });
        map.put(TL_account.checkUsername.class, (bridge, account, req) -> {
            String name = ((TL_account.checkUsername) req).username;
            try {
                User other = account.client.userByUsername(name);
                return other == null || account.session.userId.equals(other.id) ? new TLRPC.TL_boolTrue() : new TLRPC.TL_boolFalse();
            } catch (MattermostException e) {
                return e.isNotFound() ? new TLRPC.TL_boolTrue() : RelayTl.error(400, "USERNAME_INVALID");
            }
        });
        map.put(TLRPC.TL_photos_uploadProfilePhoto.class, (bridge, account, req) -> uploadPhoto(bridge, account, (TLRPC.TL_photos_uploadProfilePhoto) req));
        map.put(TLRPC.TL_photos_updateProfilePhoto.class, (bridge, account, req) -> {
            TLRPC.TL_photos_updateProfilePhoto r = (TLRPC.TL_photos_updateProfilePhoto) req;
            if (r.id instanceof TLRPC.TL_inputPhotoEmpty) {
                return deletePhoto(account);
            }
            // Choosing an older photo: Mattermost keeps one picture only, so it stays the current one.
            TLRPC.TL_photos_photo res = new TLRPC.TL_photos_photo();
            User me = account.client.me();
            account.rememberUser(me);
            TLRPC.TL_photo photo = RelayReadPath.of(account).files.profilePhoto(me);
            res.photo = photo != null ? photo : new TLRPC.TL_photoEmpty();
            res.users.add(account.tlUser(me.id));
            return res;
        });
        map.put(TLRPC.TL_photos_deletePhotos.class, (bridge, account, req) -> {
            TLRPC.TL_photos_deletePhotos r = (TLRPC.TL_photos_deletePhotos) req;
            deletePhoto(account);
            long[] ids = new long[r.id.size()];
            for (int i = 0; i < ids.length; i++) {
                ids[i] = r.id.get(i).id;
            }
            return new RelayTl.RawLongVector(ids);
        });
    }

    private static TLObject usernameError(MattermostException e) {
        String id = e.getErrorId() != null ? e.getErrorId() : "";
        if (id.contains("username_exists") || id.contains("username.exists") || e.getStatusCode() == 400 && id.contains("exists")) {
            return RelayTl.error(400, "USERNAME_OCCUPIED");
        }
        if (id.contains("username")) {
            return RelayTl.error(400, "USERNAME_INVALID");
        }
        return RelayTl.error(400, "USERNAME_INVALID");
    }

    private static TLObject updateProfile(RelayAccount account, TL_account.updateProfile r) throws Exception {
        JsonObject patch = new JsonObject();
        if ((r.flags & 1) != 0) {
            patch.addProperty("first_name", r.first_name != null ? r.first_name : "");
        }
        if ((r.flags & 2) != 0) {
            patch.addProperty("last_name", r.last_name != null ? r.last_name : "");
        }
        if ((r.flags & 4) != 0) {
            patch.addProperty("position", r.about != null ? r.about.trim() : "");
        }
        User me = account.client.patchMe(patch);
        account.rememberUser(me);
        RelayConfig.log(TAG, "profile patched: " + patch.keySet());
        return account.tlUser(me.id);
    }

    private static TLObject uploadPhoto(RelayBridge bridge, RelayAccount account, TLRPC.TL_photos_uploadProfilePhoto r) throws Exception {
        if (r.file == null) {
            return RelayTl.error(400, "PHOTO_INVALID");
        }
        File whole = RelayUploads.assemble(bridge.account, r.file);
        if (whole == null) {
            return RelayTl.error(400, "FILE_PARTS_INVALID");
        }
        account.client.setProfileImage(account.session.userId, FileUpload.ofFile("avatar.jpg", "image/jpeg", whole));
        RelayUploads.discard(bridge.account, r.file.id);
        User me = account.client.me();
        account.rememberUser(me);
        TLRPC.TL_photos_photo res = new TLRPC.TL_photos_photo();
        TLRPC.TL_photo photo = RelayReadPath.of(account).files.profilePhoto(me);
        res.photo = photo != null ? photo : new TLRPC.TL_photoEmpty();
        res.users.add(account.tlUser(me.id));
        RelayConfig.log(TAG, "profile picture uploaded (" + whole.length() + " bytes)");
        return res;
    }

    private static TLObject deletePhoto(RelayAccount account) throws Exception {
        try {
            account.client.deleteProfileImage(account.session.userId);
        } catch (MattermostException e) {
            if (!e.isNotFound()) {
                throw e;
            }
        }
        User me = account.client.me();
        account.rememberUser(me);
        TLRPC.TL_photos_photo res = new TLRPC.TL_photos_photo();
        res.photo = new TLRPC.TL_photoEmpty();
        res.users.add(account.tlUser(me.id));
        RelayConfig.log(TAG, "profile picture removed");
        return res;
    }

    // MARK: - Relay profile fields (Edit Info)

    /** The current user from the server (UI thread callback). */
    public static void loadMe(int accountNum, Callback<User> callback) {
        run(accountNum, account -> {
            User me = account.client.me();
            account.rememberUser(me);
            return me;
        }, callback);
    }

    /** Saves names, position, nickname and username in one {@code PUT /users/me/patch}. */
    public static void saveProfile(int accountNum, String first, String last, String position, String nickname, String username, Callback<User> callback) {
        run(accountNum, account -> {
            JsonObject patch = new JsonObject();
            patch.addProperty("first_name", first);
            patch.addProperty("last_name", last);
            patch.addProperty("position", position);
            patch.addProperty("nickname", nickname);
            if (username != null && !username.isEmpty()) {
                patch.addProperty("username", username);
            }
            User me;
            try {
                me = account.client.patchMe(patch);
            } catch (MattermostException e) {
                throw new IllegalStateException(e.getMessage() != null ? e.getMessage() : "error", e);
            }
            account.rememberUser(me);
            pushSelf(accountNum, account, me);
            return me;
        }, callback);
    }

    /** Hands the changed self user to the client (names, username, emoji status). */
    static void pushSelf(int accountNum, RelayAccount account, User me) {
        RelayLive live = RelayBridge.get(accountNum).live();
        TLRPC.TL_user tl = account.tlUser(me.id);
        if (live == null || tl == null) {
            return;
        }
        TL_update.TL_updateUserName name = new TL_update.TL_updateUserName();
        name.user_id = tl.id;
        name.first_name = tl.first_name != null ? tl.first_name : "";
        name.last_name = tl.last_name != null ? tl.last_name : "";
        if (tl.username != null) {
            TLRPC.TL_username u = new TLRPC.TL_username();
            u.username = tl.username;
            u.active = true;
            u.editable = true;
            name.usernames.add(u);
        }
        ArrayList<TLRPC.Update> list = new ArrayList<>();
        list.add(name);
        addEmojiStatusUpdate(account, tl, list);
        live.updates.deliver(list, new RelayUpdates.Refs().user(me.id));
    }

    /** Telegram's bio for a user: position · nickname (as {@code users.getFullUser}). */
    public static String about(User user) {
        return RelayReadPath.about(user);
    }

    // MARK: - Status

    public static final String[] STATUSES = {UserStatus.ONLINE, UserStatus.AWAY, UserStatus.DND, UserStatus.OFFLINE};

    /** {@code GET /users/me/status}. */
    public static void loadStatus(int accountNum, Callback<UserStatus> callback) {
        run(accountNum, account -> {
            UserStatus status = account.client.userStatus(account.session.userId);
            account.putStatus(status);
            return status;
        }, callback);
    }

    /** {@code PUT /users/me/status}; {@code dndEndSeconds} is the Unix time a DND status ends, or 0. */
    public static void setStatus(int accountNum, String status, long dndEndSeconds, Callback<UserStatus> callback) {
        run(accountNum, account -> {
            UserStatus result = account.client.updateStatus(account.session.userId, status, dndEndSeconds);
            account.putStatus(result);
            RelayConfig.log(TAG, "status set: " + status + (dndEndSeconds > 0 ? " until " + dndEndSeconds : ""));
            return result;
        }, callback);
    }

    /** The cached own status ("online" when unknown). */
    public static String cachedStatus(int accountNum) {
        RelayAccount account = RelayAccount.get(accountNum);
        if (account == null) {
            return UserStatus.ONLINE;
        }
        UserStatus status = account.status(account.session.userId);
        return status != null && status.status != null ? status.status : UserStatus.ONLINE;
    }

    /** The own custom status from the cached user, or null. */
    public static CustomStatus cachedCustomStatus(int accountNum) {
        RelayAccount account = RelayAccount.get(accountNum);
        if (account == null) {
            return null;
        }
        User me = account.cachedUser(account.session.userId);
        CustomStatus status = me != null ? me.getCustomStatus() : null;
        return status != null && !isExpired(status) ? status : null;
    }

    /**
     * {@code PUT /users/me/status/custom}. {@code emoji} is a Mattermost emoji name; {@code duration} one of
     * Mattermost's ({@code ""}/{@code thirty_minutes}/{@code one_hour}/{@code four_hours}/{@code today}/
     * {@code this_week}/{@code date_and_time}); {@code expiresAtMs} the end for every duration but "don't clear".
     */
    public static void setCustomStatus(int accountNum, String emoji, String text, String duration, long expiresAtMs, Callback<User> callback) {
        run(accountNum, account -> {
            CustomStatus status = new CustomStatus(emoji, text);
            status.duration = duration != null ? duration : "";
            status.expiresAt = expiresAtMs > 0 ? iso(expiresAtMs) : "";
            account.client.updateCustomStatus(status);
            User me = account.client.me();
            account.rememberUser(me);
            pushSelf(accountNum, account, me);
            RelayConfig.log(TAG, "custom status set: " + emoji + " " + text + " " + status.duration);
            return me;
        }, callback);
    }

    public static void clearCustomStatus(int accountNum, Callback<User> callback) {
        run(accountNum, account -> {
            account.client.removeCustomStatus();
            User me = account.client.me();
            account.rememberUser(me);
            pushSelf(accountNum, account, me);
            return me;
        }, callback);
    }

    // MARK: - Emoji status

    /** Sets {@code emoji_status} of a TL user from the Mattermost custom status (called for every TL user). */
    public static void applyEmojiStatus(RelayAccount account, User user, TLRPC.TL_user u) {
        if (user == null || u == null) {
            return;
        }
        try {
            CustomStatus status = user.getCustomStatus();
            if (status == null || status.emoji == null || status.emoji.isEmpty() || isExpired(status)) {
                return;
            }
            TLRPC.TL_emojiStatus emoji = new TLRPC.TL_emojiStatus();
            emoji.document_id = RelayEmoji.of(account).documentId(status.emoji);
            long until = expiresAt(status);
            if (until > 0) {
                emoji.until = (int) (until / 1000);
                emoji.flags |= TLObject.FLAG_0;
            }
            u.emoji_status = emoji;
            u.flags |= TLObject.FLAG_30;
        } catch (Throwable t) {
            RelayConfig.logError(TAG, "emoji status of " + user.id, t);
        }
    }

    /** Adds {@code updateUserEmojiStatus} for a user (live custom status changes). */
    static void addEmojiStatusUpdate(RelayAccount account, TLRPC.TL_user tl, java.util.List<TLRPC.Update> out) {
        if (tl == null) {
            return;
        }
        TL_update.TL_updateUserEmojiStatus u = new TL_update.TL_updateUserEmojiStatus();
        u.user_id = tl.id;
        u.emoji_status = tl.emoji_status != null ? tl.emoji_status : new TLRPC.TL_emojiStatusEmpty();
        out.add(u);
    }

    static boolean isExpired(CustomStatus status) {
        long until = expiresAt(status);
        return until > 0 && until <= System.currentTimeMillis();
    }

    /** The custom status end in ms, 0 for "don't clear". */
    public static long expiresAt(CustomStatus status) {
        if (status == null || status.duration == null || status.duration.isEmpty() || "".equals(status.expiresAt) || status.expiresAt == null) {
            return 0;
        }
        return parseIso(status.expiresAt);
    }

    static long parseIso(String value) {
        String[] patterns = {"yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'"};
        String v = value;
        // Go may send nanoseconds: keep milliseconds.
        int dot = v.indexOf('.');
        if (dot > 0) {
            int end = dot + 1;
            while (end < v.length() && Character.isDigit(v.charAt(end))) {
                end++;
            }
            String frac = v.substring(dot + 1, end);
            frac = (frac + "000").substring(0, 3);
            v = v.substring(0, dot + 1) + frac + v.substring(end);
        }
        for (String p : patterns) {
            try {
                SimpleDateFormat f = new SimpleDateFormat(p, Locale.US);
                f.setTimeZone(TimeZone.getTimeZone("UTC"));
                return f.parse(v).getTime();
            } catch (ParseException | IllegalArgumentException ignore) {
            }
        }
        return 0;
    }

    static String iso(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new java.util.Date(ms));
    }

    // MARK: - Plumbing

    interface Task<T> {
        T run(RelayAccount account) throws Exception;
    }

    /** Runs a task on the account's bridge executor and answers on the UI thread. */
    static <T> void run(int accountNum, Task<T> task, Callback<T> callback) {
        RelayAccount account = RelayAccount.get(accountNum);
        if (account == null) {
            if (callback != null) {
                AndroidUtilities.runOnUIThread(() -> callback.done(null, "not logged in"));
            }
            return;
        }
        RelayBridge.get(accountNum).post(() -> {
            T result = null;
            String error = null;
            try {
                result = task.run(account);
            } catch (Throwable t) {
                Throwable cause = t.getCause() instanceof MattermostException ? t.getCause() : t;
                RelayConfig.logError(TAG, "profile task failed", cause);
                error = cause instanceof MattermostException ? cause.getMessage() : String.valueOf(cause.getMessage());
                if (error == null) {
                    error = cause.toString();
                }
            }
            if (callback != null) {
                T r = result;
                String e = error;
                AndroidUtilities.runOnUIThread(() -> callback.done(r, e));
            }
        });
    }
}
