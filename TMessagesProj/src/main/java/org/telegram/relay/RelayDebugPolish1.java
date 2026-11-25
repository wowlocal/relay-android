package org.telegram.relay;

import android.os.Bundle;
import android.util.Log;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.ProfileActivity;

/**
 * Polish 1 debug helpers behind {@code org.relaychat.DEBUG_POLISH1 --es op …} (debug builds only, see
 * {@code RelayPolish1DebugHooks}). Results are logged with tag {@code RelayDebug} as {@code DEBUG_POLISH1 <op> ok …}.
 *
 * <ul>
 *   <li>{@code chat_dump}: the open chat's loaded messages to {@code files/relay-polish1.txt}, one per line:
 *       {@code msg <id> <text> <entity types> <media> <fwd user>} (media: {@code video <w>x<h> <duration>s thumbs=<n>},
 *       {@code photo}, {@code doc <mime>}, or {@code -}).</li>
 *   <li>{@code renumber --es channel <id>}: forces an id renumber in that chat and migrates it into the client.</li>
 *   <li>{@code keepalive --es on 1|0}: "Keep connected in background".</li>
 *   <li>{@code logout}: Telegram's logout of the selected account (as Settings → Log Out).</li>
 *   <li>{@code open_profile --es username <name>}: the user's profile screen.</li>
 * </ul>
 */
public final class RelayDebugPolish1 {
    private static final String TAG = "RelayDebug";

    private RelayDebugPolish1() {
    }

    public static void run(int account, String op, android.content.Intent intent) {
        switch (op == null ? "" : op) {
            case "chat_dump":
                chatDump();
                break;
            case "renumber": {
                String channel = intent.getStringExtra("channel");
                org.telegram.relay.bridge.RelayHooks.debugRenumber(account, channel, result -> Log.i(TAG, "DEBUG_POLISH1 renumber ok " + result));
                break;
            }
            case "keepalive":
                org.telegram.relay.bridge.RelayNotifications.setKeepConnected("1".equals(intent.getStringExtra("on")));
                Log.i(TAG, "DEBUG_POLISH1 keepalive ok " + org.telegram.relay.bridge.RelayNotifications.keepConnected());
                break;
            case "logout":
                MessagesController.getInstance(account).performLogout(1);
                Log.i(TAG, "DEBUG_POLISH1 logout ok");
                break;
            case "open_profile": {
                TLRPC.User user = MessagesController.getInstance(account).getUser(intent.getStringExtra("username"));
                BaseFragment last = LaunchActivity.getLastFragment();
                if (user == null || last == null) {
                    Log.w(TAG, "DEBUG_POLISH1 open_profile: no user or fragment");
                    break;
                }
                Bundle args = new Bundle();
                args.putLong("user_id", user.id);
                last.presentFragment(new ProfileActivity(args));
                Log.i(TAG, "DEBUG_POLISH1 open_profile ok " + user.id);
                break;
            }
            default:
                Log.w(TAG, "DEBUG_POLISH1 unknown op " + op);
        }
    }

    private static void chatDump() {
        StringBuilder out = new StringBuilder();
        BaseFragment last = LaunchActivity.getLastFragment();
        if (last instanceof ChatActivity) {
            ChatActivity chat = (ChatActivity) last;
            out.append("chat\t").append(chat.getDialogId()).append('\t').append(chat.getThreadId()).append('\n');
            for (MessageObject m : chat.messages) {
                if (m == null || m.messageOwner == null || m.getId() == 0) {
                    continue;
                }
                TLRPC.Message msg = m.messageOwner;
                StringBuilder entities = new StringBuilder();
                if (msg.entities != null) {
                    for (TLRPC.MessageEntity e : msg.entities) {
                        if (entities.length() > 0) {
                            entities.append(',');
                        }
                        entities.append(e.getClass().getSimpleName().replace("TL_messageEntity", "")).append('@').append(e.offset).append('+').append(e.length);
                    }
                }
                String fwd = msg.fwd_from != null && msg.fwd_from.from_id != null ? String.valueOf(msg.fwd_from.from_id.user_id) : "0";
                out.append("msg\t").append(m.getId()).append('\t').append(clean(msg.message)).append('\t')
                        .append(entities.length() > 0 ? entities : "-").append('\t').append(media(msg)).append('\t').append(fwd)
                        .append('\t').append(clean(m.messageText)).append('\n');
            }
        }
        try {
            java.io.File file = new java.io.File(ApplicationLoader.getFilesDirFixed(), "relay-polish1.txt");
            try (java.io.FileOutputStream stream = new java.io.FileOutputStream(file)) {
                stream.write(out.toString().getBytes("UTF-8"));
            }
        } catch (Exception e) {
            Log.w(TAG, "relay-polish1.txt: " + e);
        }
        Log.i(TAG, "DEBUG_POLISH1 chat_dump ok");
    }

    private static String media(TLRPC.Message msg) {
        if (msg.media == null || msg.media instanceof TLRPC.TL_messageMediaEmpty) {
            return "-";
        }
        if (msg.media.photo != null) {
            // "photo loaded=1" once the largest size (the bubble's image) is on disk
            TLRPC.PhotoSize big = org.telegram.messenger.FileLoader.getClosestPhotoSizeWithSize(msg.media.photo.sizes, org.telegram.messenger.AndroidUtilities.getPhotoSize());
            boolean loaded = big != null && org.telegram.messenger.FileLoader.getInstance(org.telegram.messenger.UserConfig.selectedAccount).getPathToAttach(big, false).exists()
                    || org.telegram.messenger.FileLoader.getInstance(org.telegram.messenger.UserConfig.selectedAccount).getPathToAttach(big, true).exists();
            return "photo " + (big != null ? big.type : "?") + " loaded=" + (loaded ? 1 : 0);
        }
        TLRPC.Document doc = msg.media.document;
        if (doc == null) {
            return msg.media.getClass().getSimpleName();
        }
        for (TLRPC.DocumentAttribute a : doc.attributes) {
            if (a instanceof TLRPC.TL_documentAttributeVideo) {
                return "video " + a.w + "x" + a.h + " " + (int) a.duration + "s thumbs=" + doc.thumbs.size();
            }
        }
        return "doc " + doc.mime_type + " thumbs=" + doc.thumbs.size();
    }

    private static String clean(CharSequence s) {
        if (s == null) {
            return "";
        }
        String text = s.toString().replace('\t', ' ').replace('\n', ' ');
        return text.length() > 160 ? text.substring(0, 160) : text;
    }

    static void ui(Runnable r) {
        AndroidUtilities.runOnUIThread(r);
    }
}
