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
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.SQLite.SQLiteCursor;
import org.telegram.relay.bridge.RelayBridge;
import org.telegram.relay.bridge.RelayHooks;
import org.telegram.tgnet.TLRPC;

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
 * adb shell am broadcast -a org.relaychat.DEBUG_DUMP_DIALOGS
 *     # writes files/relay-dialogs.txt: the client's chat list as shown (TSV, see dumpDialogs)
 * adb shell am broadcast -a org.relaychat.DEBUG_OPEN_CHAT --es channel &lt;mattermost channel id&gt; [--ei message &lt;id&gt;]
 * adb shell am broadcast -a org.relaychat.DEBUG_OPEN_URL --es url &lt;url&gt;   # Browser.openUrl, like an in-app link tap
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
    public static final String ACTION_DUMP_DIALOGS = "org.relaychat.DEBUG_DUMP_DIALOGS";
    public static final String ACTION_OPEN_CHAT = "org.relaychat.DEBUG_OPEN_CHAT";
    /** {@code --es op reconnect|reset|state}: drop the WebSocket (resumed / not resumed), or log the live state. */
    public static final String ACTION_WS = "org.relaychat.DEBUG_WS";
    /** {@code --es channel <id> --es kind photo|video|document|voice --es paths a,b,c [--es caption …]}. */
    public static final String ACTION_SEND_MEDIA = "org.relaychat.DEBUG_SEND_MEDIA";
    /** {@code --es text <substring of a message in the open chat> --es emoji 👍} (empty emoji clears). */
    public static final String ACTION_REACT = "org.relaychat.DEBUG_REACT";
    /** {@code --es url <url>}: opens a link the way an in-app tap does ({@code Browser.openUrl}). */
    public static final String ACTION_OPEN_URL = "org.relaychat.DEBUG_OPEN_URL";
    /** A7a: {@code --es op ui_state|jump_date|clipboard [--ei date <unix>]} (see {@code RelayDebugA7a}). */
    public static final String ACTION_A7A = "org.relaychat.DEBUG_A7A";

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
        filter.addAction(ACTION_DUMP_DIALOGS);
        filter.addAction(ACTION_OPEN_CHAT);
        filter.addAction(ACTION_WS);
        filter.addAction(ACTION_SEND_MEDIA);
        filter.addAction(ACTION_REACT);
        filter.addAction(ACTION_OPEN_URL);
        filter.addAction(ACTION_A7A);
        BroadcastReceiver receiver = new Receiver();
        if (Build.VERSION.SDK_INT >= 33) {
            context.getApplicationContext().registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            context.getApplicationContext().registerReceiver(receiver, filter);
        }
        Log.i(TAG, "debug hooks registered");
        // Latency probe for the e2e tests: when new / edited messages reach the UI thread. Posted, so it
        // runs after Application.onCreate (providers are created before it).
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                final int account = a;
                org.telegram.messenger.NotificationCenter center = org.telegram.messenger.NotificationCenter.getInstance(a);
                org.telegram.messenger.NotificationCenter.NotificationCenterDelegate observer = (id, acc, args) -> {
                    StringBuilder ids = new StringBuilder();
                    if (id == org.telegram.messenger.NotificationCenter.didReceiveNewMessages && args.length > 1 && args[1] instanceof java.util.ArrayList) {
                        for (Object o : (java.util.ArrayList<?>) args[1]) {
                            if (o instanceof org.telegram.messenger.MessageObject) {
                                org.telegram.messenger.MessageObject m = (org.telegram.messenger.MessageObject) o;
                                ids.append(m.getId()).append(':').append(clean(m.messageOwner != null ? m.messageOwner.message : "")).append(' ');
                            }
                        }
                        Log.i(TAG, "UI newMessages dialog=" + args[0] + " " + ids);
                    } else if (id == org.telegram.messenger.NotificationCenter.messageReceivedByServer && args.length > 1) {
                        Log.i(TAG, "UI sent old=" + args[0] + " new=" + args[1]);
                    }
                };
                center.addObserver(observer, org.telegram.messenger.NotificationCenter.didReceiveNewMessages);
                center.addObserver(observer, org.telegram.messenger.NotificationCenter.messageReceivedByServer);
            }
        });
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
            if (ACTION_A7A.equals(action)) {
                String op = intent.getStringExtra("op");
                String dateExtra = intent.getStringExtra("date");
                int date = dateExtra != null ? Integer.parseInt(dateExtra.trim()) : intent.getIntExtra("date", 0);
                AndroidUtilities.runOnUIThread(() -> org.telegram.relay.RelayDebugA7a.run(UserConfig.selectedAccount, op, date));
                return;
            }
            if (ACTION_DUMP_DIALOGS.equals(action)) {
                AndroidUtilities.runOnUIThread(() -> org.telegram.relay.RelayDebug.dumpDialogs(UserConfig.selectedAccount));
                return;
            }
            if (ACTION_OPEN_CHAT.equals(action)) {
                String channel = intent.getStringExtra("channel");
                int message = intent.getIntExtra("message", 0);
                new Thread(() -> {
                    long did = RelayHooks.dialogIdOfChannel(UserConfig.selectedAccount, channel);
                    AndroidUtilities.runOnUIThread(() -> org.telegram.relay.RelayDebug.openChat(did, message));
                }, "RelayDebugOpen").start();
                return;
            }
            if (ACTION_SEND_MEDIA.equals(action)) {
                String channel = intent.getStringExtra("channel");
                String kind = intent.getStringExtra("kind");
                String paths = intent.getStringExtra("paths");
                String caption = intent.getStringExtra("caption");
                new Thread(() -> {
                    long did = RelayHooks.dialogIdOfChannel(UserConfig.selectedAccount, channel);
                    java.util.List<String> list = java.util.Arrays.asList(paths != null ? paths.split(",") : new String[0]);
                    AndroidUtilities.runOnUIThread(() -> org.telegram.relay.RelayDebug.sendMedia(UserConfig.selectedAccount, did, kind != null ? kind : "photo", list, caption));
                }, "RelayDebugSend").start();
                return;
            }
            if (ACTION_REACT.equals(action)) {
                String text = intent.getStringExtra("text");
                String emoji = intent.getStringExtra("emoji");
                AndroidUtilities.runOnUIThread(() -> org.telegram.relay.RelayDebug.react(UserConfig.selectedAccount, text, emoji));
                return;
            }
            if (ACTION_WS.equals(action)) {
                String op = intent.getStringExtra("op");
                int account = UserConfig.selectedAccount;
                if ("reconnect".equals(op) || "reset".equals(op)) {
                    RelayHooks.debugReconnect(account, "reconnect".equals(op));
                }
                Log.i(TAG, "DEBUG_WS ok " + op + " " + RelayHooks.debugLiveState(account));
                return;
            }
            if (ACTION_OPEN_URL.equals(action)) {
                String url = intent.getStringExtra("url");
                AndroidUtilities.runOnUIThread(() -> org.telegram.relay.RelayDebug.openUrl(url));
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
                    cursor = storage.getDatabase().queryFinalized("SELECT unread_count, inbox_max, outbox_max, last_mid FROM dialogs WHERE did = " + finalDid);
                    if (cursor.next()) {
                        text.append("dialogrow\t").append(cursor.intValue(0)).append('\t').append(cursor.intValue(1)).append('\t').append(cursor.intValue(2)).append('\t').append(cursor.intValue(3)).append('\n');
                    }
                    cursor.dispose();
                    cursor = storage.getDatabase().queryFinalized("SELECT mid, data FROM messages_v2 WHERE uid = " + finalDid + " ORDER BY mid");
                    while (cursor.next()) {
                        text.append("client\t").append(cursor.intValue(0)).append('\n');
                        org.telegram.tgnet.NativeByteBuffer data = cursor.byteBufferValue(1);
                        if (data != null) {
                            try {
                                TLRPC.Message m = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                                if (m != null) {
                                    text.append("msg\t").append(cursor.intValue(0)).append('\t').append(clean(m.message))
                                            .append('\t').append(m.edit_date).append('\t').append(reactions(m))
                                            .append('\t').append(m.replies != null ? m.replies.replies : 0)
                                            .append('\t').append(m.reply_to != null ? (m.reply_to.reply_to_msg_id != 0 ? m.reply_to.reply_to_msg_id : m.reply_to.reply_to_top_id) : 0) // with CRT a reply carries only its thread root (top id)
                                            .append('\t').append(m.media != null ? m.media.getClass().getSimpleName() : "")
                                            .append('\t').append(m.out ? 1 : 0).append('\n');
                                }
                            } catch (Throwable ignore) {
                            }
                            data.reuse();
                        }
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

    private static String clean(String text) {
        return text == null ? "" : text.replace('\t', ' ').replace('\n', ' ');
    }

    private static String reactions(TLRPC.Message m) {
        if (m.reactions == null || m.reactions.results == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (TLRPC.ReactionCount count : m.reactions.results) {
            if (count.reaction instanceof TLRPC.TL_reactionEmoji) {
                out.append(((TLRPC.TL_reactionEmoji) count.reaction).emoticon).append('=').append(count.count)
                        .append(count.chosen_order != 0 ? "*" : "").append(' ');
            } else if (count.reaction instanceof TLRPC.TL_reactionCustomEmoji) {
                // A7c: Mattermost custom emoji (Relay emoji documents)
                out.append("doc:").append(((TLRPC.TL_reactionCustomEmoji) count.reaction).document_id).append('=').append(count.count)
                        .append(count.chosen_order != 0 ? "*" : "").append(' ');
            }
        }
        return out.toString().trim();
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
