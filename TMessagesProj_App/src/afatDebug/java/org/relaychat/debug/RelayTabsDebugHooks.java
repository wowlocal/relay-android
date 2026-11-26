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

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.UserConfig;

/**
 * Tab bar debug hooks (afatDebug only):
 *
 * <pre>
 * adb shell am broadcast -a org.relaychat.DEBUG_TABS --es op state|theme [--es dark 1|0]
 * </pre>
 * See {@code org.telegram.relay.RelayDebugTabs}.
 */
public class RelayTabsDebugHooks extends ContentProvider {
    public static final String ACTION_TABS = "org.relaychat.DEBUG_TABS";

    @Override
    public boolean onCreate() {
        Context context = getContext();
        if (context == null) {
            return false;
        }
        IntentFilter filter = new IntentFilter(ACTION_TABS);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                String op = intent.getStringExtra("op");
                AndroidUtilities.runOnUIThread(() -> org.telegram.relay.RelayDebugTabs.run(UserConfig.selectedAccount, op, intent));
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
