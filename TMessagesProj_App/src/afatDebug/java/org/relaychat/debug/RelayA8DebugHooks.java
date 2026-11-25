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

import org.telegram.messenger.UserConfig;
import org.telegram.relay.calls.RelayCallsDebug;

/**
 * A8 debug hooks (afatDebug only):
 *
 * <pre>
 * adb shell am broadcast -a org.relaychat.DEBUG_CALL_PROBE --es channel &lt;channel id&gt; [--ei seconds 20]
 *     # joins the channel's call through the plugin and a LiveKit room, microphone on, then leaves
 * </pre>
 * Results are logged with tag {@code RelayDebug}.
 */
public class RelayA8DebugHooks extends ContentProvider {
    public static final String ACTION_CALL_PROBE = "org.relaychat.DEBUG_CALL_PROBE";
    /** {@code --es user <username> [--ez video true]}: Telegram's call button path (VoIPHelper.startCall). */
    public static final String ACTION_CALL = "org.relaychat.DEBUG_CALL";
    /** Logs VoIPService's state: {@code DEBUG_CALL_STATE state=<n> …}. */
    public static final String ACTION_CALL_STATE = "org.relaychat.DEBUG_CALL_STATE";

    @Override
    public boolean onCreate() {
        Context context = getContext();
        if (context == null) {
            return false;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_CALL_PROBE);
        filter.addAction(ACTION_CALL);
        filter.addAction(ACTION_CALL_STATE);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                int account = UserConfig.selectedAccount;
                if (ACTION_CALL_PROBE.equals(intent.getAction())) {
                    RelayCallsDebug.probe(account, intent.getStringExtra("channel"), intent.getIntExtra("seconds", 20));
                } else if (ACTION_CALL.equals(intent.getAction())) {
                    org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> RelayCallsDebug.call(account, intent.getStringExtra("user"), intent.getBooleanExtra("video", false)));
                } else if (ACTION_CALL_STATE.equals(intent.getAction())) {
                    org.telegram.messenger.AndroidUtilities.runOnUIThread(RelayCallsDebug::logState);
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
