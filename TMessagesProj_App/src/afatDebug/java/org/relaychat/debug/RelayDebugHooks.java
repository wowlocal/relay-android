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
import org.telegram.messenger.UserConfig;
import org.telegram.relay.auth.RelayAuth;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessagesStorage;
import org.telegram.SQLite.SQLiteCursor;
import org.telegram.relay.bridge.RelayBridge;
import org.telegram.relay.bridge.RelayHooks;

/**
 * Debug-only test hooks for the e2e scripts (this source set is compiled into debug builds only).
 * A ContentProvider registers a runtime receiver at process start, so plain implicit broadcasts work:
 *
 * <pre>
 * adb shell am broadcast -a org.relaychat.DEBUG_LOGIN --es server http://localhost:8065 --es user alice --es password …
 * adb shell am broadcast -a org.relaychat.DEBUG_DUMP     # logs the bridge's unhandled-method summary
 * adb shell am broadcast -a org.relaychat.DEBUG_DUMP_DIALOG --es channel &lt;mattermost channel id&gt;
 *     # writes files/relay-dialog.txt: the bridge's id map of that chat ("map" lines: local id, post id,
 *     # part, deleted), its islands, and the message ids the client has cached ("client" lines)
 * </pre>
 *
 * Results are logged with tag {@code RelayDebug}: {@code DEBUG_LOGIN ok account=…} or
 * {@code DEBUG_LOGIN failed …}. The login runs on the selected account and needs the app's UI running.
 */
public class RelayDebugHooks extends ContentProvider {
    private static final String TAG = "RelayDebug";
    public static final String ACTION_LOGIN = "org.relaychat.DEBUG_LOGIN";
    public static final String ACTION_DUMP = "org.relaychat.DEBUG_DUMP";
    public static final String ACTION_DUMP_DIALOG = "org.relaychat.DEBUG_DUMP_DIALOG";

    @Override
    public boolean onCreate() {
        Context context = getContext();
        if (context == null) {
            return false;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_LOGIN);
        filter.addAction(ACTION_DUMP);
        filter.addAction(ACTION_DUMP_DIALOG);
        BroadcastReceiver receiver = new Receiver();
        if (Build.VERSION.SDK_INT >= 33) {
            context.getApplicationContext().registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            context.getApplicationContext().registerReceiver(receiver, filter);
        }
        Log.i(TAG, "debug hooks registered");
        return true;
    }

    static final class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_DUMP.equals(action)) {
                int account = UserConfig.selectedAccount;
                Log.i(TAG, "DEBUG_DUMP account=" + account);
                RelayBridge.get(account).logSummary();
                return;
            }
            if (ACTION_DUMP_DIALOG.equals(action)) {
                dumpDialog(UserConfig.selectedAccount, intent.getStringExtra("channel"), intent.getLongExtra("dialog", 0));
                return;
            }
            if (!ACTION_LOGIN.equals(action)) {
                return;
            }
            String server = intent.getStringExtra("server");
            String user = intent.getStringExtra("user");
            String password = intent.getStringExtra("password");
            String mfa = intent.getStringExtra("mfa");
            if (server == null || user == null || password == null) {
                Log.w(TAG, "DEBUG_LOGIN failed: needs --es server, --es user, --es password");
                return;
            }
            AndroidUtilities.runOnUIThread(() -> login(server, user, password, mfa));
        }
    }

    private static void dumpDialog(int account, String channelId, long dialogId) {
        new Thread(() -> {
            long did = dialogId;
            if (did == 0 && channelId != null) {
                did = RelayHooks.dialogIdOfChannel(account, channelId);
            }
            StringBuilder text = new StringBuilder(RelayHooks.dumpDialog(account, did));
            final long finalDid = did;
            java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
            MessagesStorage storage = MessagesStorage.getInstance(account);
            storage.getStorageQueue().postRunnable(() -> {
                SQLiteCursor cursor = null;
                try {
                    cursor = storage.getDatabase().queryFinalized("SELECT mid FROM messages_v2 WHERE uid = " + finalDid + " ORDER BY mid");
                    while (cursor.next()) {
                        text.append("client\t").append(cursor.intValue(0)).append('\n');
                    }
                } catch (Exception e) {
                    text.append("client query failed ").append(e).append('\n');
                } finally {
                    if (cursor != null) {
                        cursor.dispose();
                    }
                    done.countDown();
                }
            });
            try {
                done.await(10, java.util.concurrent.TimeUnit.SECONDS);
                java.io.File file = new java.io.File(ApplicationLoader.getFilesDirFixed(), "relay-dialog.txt");
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
                    out.write(text.toString().getBytes("UTF-8"));
                }
                Log.i(TAG, "DEBUG_DUMP_DIALOG ok dialog=" + finalDid + " bytes=" + text.length());
            } catch (Exception e) {
                Log.w(TAG, "DEBUG_DUMP_DIALOG failed: " + e);
            }
        }, "RelayDebugDump").start();
    }

    private static void login(String server, String user, String password, String mfa) {
        RelayAuth.loginWithoutUi(server, user, password, mfa, (account, error) -> {
            if (error != null) {
                Log.w(TAG, "DEBUG_LOGIN failed: " + error);
            } else {
                Log.i(TAG, "DEBUG_LOGIN ok account=" + account);
            }
        });
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
