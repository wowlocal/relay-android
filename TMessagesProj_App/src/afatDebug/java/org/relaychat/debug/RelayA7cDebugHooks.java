package org.relaychat.debug;

import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

/**
 * A7c debug hooks (afatDebug only), next to {@link RelayDebugHooks}:
 *
 * <pre>
 * adb shell am broadcast -a org.relaychat.DEBUG_SET_AVATAR --es path /sdcard/Android/data/…/files/a.jpg
 *     # Telegram's uploader (FileLoader.uploadFile) + photos.uploadProfilePhoto, as Settings' photo picker does
 * adb shell am broadcast -a org.relaychat.DEBUG_DELETE_AVATAR   # photos.updateProfilePhoto(inputPhotoEmpty)
 * adb shell am broadcast -a org.relaychat.DEBUG_USER_STATUS --es username bob
 *     # the client's user: DEBUG_USER_STATUS bob emoji_status=&lt;document id&gt; emoji=&lt;Mattermost name&gt;
 * </pre>
 * Results are logged with tag {@code RelayDebug}: {@code DEBUG_SET_AVATAR ok} / {@code failed …}.
 */
public class RelayA7cDebugHooks extends ContentProvider {
    private static final String TAG = "RelayDebug";
    public static final String ACTION_SET_AVATAR = "org.relaychat.DEBUG_SET_AVATAR";
    public static final String ACTION_DELETE_AVATAR = "org.relaychat.DEBUG_DELETE_AVATAR";
    public static final String ACTION_USER_STATUS = "org.relaychat.DEBUG_USER_STATUS";

    @Override
    public boolean onCreate() {
        Context context = getContext();
        if (context == null) {
            return false;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_SET_AVATAR);
        filter.addAction(ACTION_DELETE_AVATAR);
        filter.addAction(ACTION_USER_STATUS);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                int account = UserConfig.selectedAccount;
                if (ACTION_SET_AVATAR.equals(intent.getAction())) {
                    setAvatar(account, intent.getStringExtra("path"));
                } else if (ACTION_USER_STATUS.equals(intent.getAction())) {
                    String username = intent.getStringExtra("username");
                    TLRPC.User user = MessagesController.getInstance(account).getUser(username);
                    long doc = user != null ? org.telegram.messenger.DialogObject.getEmojiStatusDocumentId(user.emoji_status) : 0;
                    org.telegram.relay.account.RelayAccount relay = org.telegram.relay.account.RelayAccount.get(account);
                    String name = doc != 0 && relay != null ? org.telegram.relay.bridge.RelayEmoji.of(relay).nameOf(doc) : null;
                    Log.i(TAG, "DEBUG_USER_STATUS " + username + " found=" + (user != null) + " emoji_status=" + doc + " emoji=" + name);
                } else if (ACTION_DELETE_AVATAR.equals(intent.getAction())) {
                    TLRPC.TL_photos_updateProfilePhoto req = new TLRPC.TL_photos_updateProfilePhoto();
                    req.id = new TLRPC.TL_inputPhotoEmpty();
                    ConnectionsManager.getInstance(account).sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
                        if (res instanceof TLRPC.TL_photos_photo) {
                            MessagesController.getInstance(account).putUsers(((TLRPC.TL_photos_photo) res).users, false);
                            Log.i(TAG, "DEBUG_DELETE_AVATAR ok");
                        } else {
                            Log.w(TAG, "DEBUG_DELETE_AVATAR failed: " + (err != null ? err.text : "?"));
                        }
                    }));
                }
            }
        };
        if (Build.VERSION.SDK_INT >= 33) {
            context.getApplicationContext().registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            context.getApplicationContext().registerReceiver(receiver, filter);
        }
        return true;
    }

    private static void setAvatar(int account, String path) {
        if (path == null) {
            Log.w(TAG, "DEBUG_SET_AVATAR failed: needs --es path");
            return;
        }
        NotificationCenter center = NotificationCenter.getInstance(account);
        NotificationCenter.NotificationCenterDelegate[] observer = new NotificationCenter.NotificationCenterDelegate[1];
        observer[0] = (id, acc, args) -> {
            if (id != NotificationCenter.fileUploaded || !path.equals(args[0])) {
                return;
            }
            center.removeObserver(observer[0], NotificationCenter.fileUploaded);
            TLRPC.TL_photos_uploadProfilePhoto req = new TLRPC.TL_photos_uploadProfilePhoto();
            req.file = (TLRPC.InputFile) args[1];
            req.flags |= 1;
            ConnectionsManager.getInstance(account).sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
                if (res instanceof TLRPC.TL_photos_photo) {
                    MessagesController.getInstance(account).putUsers(((TLRPC.TL_photos_photo) res).users, false);
                    Log.i(TAG, "DEBUG_SET_AVATAR ok");
                } else {
                    Log.w(TAG, "DEBUG_SET_AVATAR failed: " + (err != null ? err.text : "?"));
                }
            }));
        };
        center.addObserver(observer[0], NotificationCenter.fileUploaded);
        FileLoader.getInstance(account).uploadFile(path, false, true, ConnectionsManager.FileTypePhoto);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
