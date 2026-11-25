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
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.relay.bridge.RelayFinding;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

/**
 * Phase A7b e2e hooks (debug builds only): send Telegram's own search / member requests through the
 * client's {@code ConnectionsManager} (so the bridge answers exactly as it does for the screens) and log
 * the result with Mattermost ids, and open Relay's A7b screens.
 *
 * <pre>
 * adb shell am broadcast -a org.relaychat.DEBUG_A7B_SEARCH --es channel &lt;id or empty&gt; --es q &lt;text&gt; [--es filter text|pinned|photovideo|photos|video|document|url|voice|music] [--es from &lt;username&gt;] [--es global 1]
 * adb shell am broadcast -a org.relaychat.DEBUG_A7B_MEMBERS --es channel &lt;id&gt; [--es filter recent|admins|search] [--es q …]
 * adb shell am broadcast -a org.relaychat.DEBUG_A7B_COUNTERS --es channel &lt;id&gt;
 * adb shell am broadcast -a org.relaychat.DEBUG_A7B_PEERS --es q &lt;text&gt;
 * adb shell am broadcast -a org.relaychat.DEBUG_A7B_OPEN --es screen saved|mentions|browse|newchannel
 * </pre>
 * Results are logged with tag {@code RelayA7b}: {@code A7B <what> count=… items=[postId|text, …]}.
 */
public class RelayFindingDebugHooks extends ContentProvider {
    private static final String TAG = "RelayA7b";

    public static final String ACTION_SEARCH = "org.relaychat.DEBUG_A7B_SEARCH";
    public static final String ACTION_MEMBERS = "org.relaychat.DEBUG_A7B_MEMBERS";
    public static final String ACTION_COUNTERS = "org.relaychat.DEBUG_A7B_COUNTERS";
    public static final String ACTION_PEERS = "org.relaychat.DEBUG_A7B_PEERS";
    public static final String ACTION_OPEN = "org.relaychat.DEBUG_A7B_OPEN";

    @Override
    public boolean onCreate() {
        Context context = getContext();
        if (context == null) {
            return false;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_SEARCH);
        filter.addAction(ACTION_MEMBERS);
        filter.addAction(ACTION_COUNTERS);
        filter.addAction(ACTION_PEERS);
        filter.addAction(ACTION_OPEN);
        BroadcastReceiver receiver = new Receiver();
        if (Build.VERSION.SDK_INT >= 33) {
            context.getApplicationContext().registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            context.getApplicationContext().registerReceiver(receiver, filter);
        }
        return true;
    }

    private static TLRPC.MessagesFilter filter(String name) {
        if (name == null) return new TLRPC.TL_inputMessagesFilterEmpty();
        switch (name) {
            case "pinned": return new TLRPC.TL_inputMessagesFilterPinned();
            case "photovideo": return new TLRPC.TL_inputMessagesFilterPhotoVideo();
            case "photos": return new TLRPC.TL_inputMessagesFilterPhotos();
            case "video": return new TLRPC.TL_inputMessagesFilterVideo();
            case "document": return new TLRPC.TL_inputMessagesFilterDocument();
            case "url": return new TLRPC.TL_inputMessagesFilterUrl();
            case "voice": return new TLRPC.TL_inputMessagesFilterRoundVoice();
            case "music": return new TLRPC.TL_inputMessagesFilterMusic();
            default: return new TLRPC.TL_inputMessagesFilterEmpty();
        }
    }

    private static String clean(String text) {
        if (text == null) return "";
        String s = text.replace('\n', ' ').replace('|', '/').replace(',', ';');
        return s.length() > 60 ? s.substring(0, 60) : s;
    }

    static final class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            final int account = UserConfig.selectedAccount;
            final String action = intent.getAction();
            final String channel = intent.getStringExtra("channel");
            new Thread(() -> {
                long chatId = channel != null && !channel.isEmpty() ? RelayFinding.debugChatId(account, channel) : 0;
                long dialogId = chatId != 0 ? -chatId : (channel != null && !channel.isEmpty() ? org.telegram.relay.bridge.RelayHooks.dialogIdOfChannel(account, channel) : 0);
                AndroidUtilities.runOnUIThread(() -> run(account, action, intent, dialogId));
            }, "RelayA7bDebug").start();
        }

        private void run(int account, String action, Intent intent, long dialogId) {
            MessagesController mc = MessagesController.getInstance(account);
            ConnectionsManager cm = ConnectionsManager.getInstance(account);
            if (ACTION_SEARCH.equals(action)) {
                String q = intent.getStringExtra("q");
                String from = intent.getStringExtra("from");
                boolean global = "1".equals(intent.getStringExtra("global"));
                TLObject req;
                if (global) {
                    TLRPC.TL_messages_searchGlobal r = new TLRPC.TL_messages_searchGlobal();
                    r.q = q != null ? q : "";
                    r.filter = filter(intent.getStringExtra("filter"));
                    r.limit = 100;
                    r.offset_peer = new TLRPC.TL_inputPeerEmpty();
                    req = r;
                } else {
                    TLRPC.TL_messages_search r = new TLRPC.TL_messages_search();
                    r.peer = mc.getInputPeer(dialogId);
                    r.q = q != null ? q : "";
                    r.filter = filter(intent.getStringExtra("filter"));
                    r.limit = 100;
                    if (from != null && !from.isEmpty()) {
                        TLObject found = mc.getUserOrChat(from);
                        if (found instanceof TLRPC.User) {
                            r.from_id = MessagesController.getInputPeer((TLRPC.User) found);
                            r.flags |= 1;
                        }
                    }
                    req = r;
                }
                cm.sendRequest(req, (response, error) -> {
                    if (!(response instanceof TLRPC.messages_Messages)) {
                        Log.i(TAG, "A7B search error " + (error != null ? error.text : "null"));
                        return;
                    }
                    TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                    StringBuilder items = new StringBuilder();
                    for (TLRPC.Message m : res.messages) {
                        long did = MessageObject.getDialogId(m);
                        if (items.length() > 0) items.append(',');
                        items.append(RelayFinding.debugPostId(account, did, m.id)).append('|').append(clean(m.message));
                    }
                    Log.i(TAG, "A7B search " + (global ? "global" : "chat") + " count=" + res.count + " n=" + res.messages.size() + " items=[" + items + "]");
                });
            } else if (ACTION_COUNTERS.equals(action)) {
                TLRPC.TL_messages_getSearchCounters r = new TLRPC.TL_messages_getSearchCounters();
                r.peer = mc.getInputPeer(dialogId);
                String[] names = {"photovideo", "document", "url", "voice", "music", "pinned"};
                for (String n : names) {
                    r.filters.add(filter(n));
                }
                cm.sendRequest(r, (response, error) -> {
                    StringBuilder text = new StringBuilder();
                    if (response instanceof org.telegram.tgnet.Vector) {
                        java.util.List<?> objects = ((org.telegram.tgnet.Vector) response).objects;
                        for (int i = 0; i < objects.size() && i < names.length; i++) {
                            text.append(names[i]).append('=').append(((TLRPC.TL_messages_searchCounter) objects.get(i)).count).append(' ');
                        }
                    }
                    Log.i(TAG, "A7B counters " + text.toString().trim());
                });
            } else if (ACTION_MEMBERS.equals(action)) {
                TLRPC.TL_channels_getParticipants r = new TLRPC.TL_channels_getParticipants();
                r.channel = mc.getInputChannel(-dialogId);
                String f = intent.getStringExtra("filter");
                if ("admins".equals(f)) {
                    r.filter = new TLRPC.TL_channelParticipantsAdmins();
                } else if ("search".equals(f)) {
                    r.filter = new TLRPC.TL_channelParticipantsSearch();
                    r.filter.q = intent.getStringExtra("q");
                } else {
                    r.filter = new TLRPC.TL_channelParticipantsRecent();
                }
                r.limit = 200;
                cm.sendRequest(r, (response, error) -> {
                    if (!(response instanceof TLRPC.TL_channels_channelParticipants)) {
                        Log.i(TAG, "A7B members error " + (error != null ? error.text : "null"));
                        return;
                    }
                    TLRPC.TL_channels_channelParticipants res = (TLRPC.TL_channels_channelParticipants) response;
                    StringBuilder items = new StringBuilder();
                    for (TLRPC.ChannelParticipant p : res.participants) {
                        long uid = p.peer != null ? p.peer.user_id : p.user_id;
                        if (items.length() > 0) items.append(',');
                        items.append(RelayFinding.debugUserId(account, uid)).append('|')
                                .append(p instanceof TLRPC.TL_channelParticipantAdmin ? "admin" : p instanceof TLRPC.TL_channelParticipantCreator ? "creator" : "member");
                    }
                    Log.i(TAG, "A7B members count=" + res.count + " items=[" + items + "]");
                });
            } else if (ACTION_PEERS.equals(action)) {
                TLRPC.TL_contacts_search r = new TLRPC.TL_contacts_search();
                r.q = intent.getStringExtra("q");
                r.limit = 50;
                cm.sendRequest(r, (response, error) -> {
                    if (!(response instanceof TLRPC.TL_contacts_found)) {
                        Log.i(TAG, "A7B peers error");
                        return;
                    }
                    TLRPC.TL_contacts_found res = (TLRPC.TL_contacts_found) response;
                    StringBuilder items = new StringBuilder();
                    for (TLRPC.User u : res.users) {
                        if (items.length() > 0) items.append(',');
                        items.append("user:").append(RelayFinding.debugUserId(account, u.id));
                    }
                    for (TLRPC.Chat c : res.chats) {
                        if (items.length() > 0) items.append(',');
                        items.append(c.left ? "join:" : "chat:").append(clean(c.title));
                    }
                    Log.i(TAG, "A7B peers items=[" + items + "]");
                });
            } else if (ACTION_OPEN.equals(action)) {
                String screen = intent.getStringExtra("screen");
                Log.i(TAG, "A7B open " + screen + " " + org.telegram.relay.ui.RelayPostListActivity.debugOpen(screen));
            }
        }
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
