package org.telegram.relay;

import android.util.Log;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessagesController;

/**
 * Helpers behind the debug broadcasts of {@code org.relaychat.debug.RelayDebugHooks} (debug builds only;
 * they live here because they touch Telegram UI classes the app module cannot compile against).
 */
public final class RelayDebug {
    private static final String TAG = "RelayDebug";

    private RelayDebug() {
    }

    /**
     * The client's chat list as the UI has it, one TSV line per dialog:
     * {@code dialog <did> <channel> <title> <unread> <mentions> <muted> <pinned> <top_id> <date> <preview>},
     * then {@code filter <id> <title> <dialog count>} per folder.
     */
    public static void dumpDialogs(int account) {
        MessagesController mc = MessagesController.getInstance(account);
        java.util.ArrayList<org.telegram.tgnet.TLRPC.Dialog> dialogs = new java.util.ArrayList<>(mc.getAllDialogs());
        java.util.ArrayList<org.telegram.messenger.MessagesController.DialogFilter> filters = new java.util.ArrayList<>(mc.getDialogFilters());
        new Thread(() -> {
            StringBuilder text = new StringBuilder();
            for (org.telegram.tgnet.TLRPC.Dialog d : dialogs) {
                String title;
                if (d.id > 0) {
                    org.telegram.tgnet.TLRPC.User u = mc.getUser(d.id);
                    title = u == null ? "?" : (u.self ? "Saved Messages" : org.telegram.messenger.UserObject.getUserName(u));
                } else {
                    org.telegram.tgnet.TLRPC.Chat c = mc.getChat(-d.id);
                    title = c == null ? "?" : c.title;
                }
                java.util.ArrayList<org.telegram.messenger.MessageObject> msgs = mc.dialogMessage.get(d.id);
                String preview = msgs != null && !msgs.isEmpty() && msgs.get(0).messageText != null ? msgs.get(0).messageText.toString() : "";
                text.append("dialog\t").append(d.id).append('\t').append(org.telegram.relay.bridge.RelayHooks.channelIdOfDialog(account, d.id)).append('\t')
                        .append(clean(title)).append('\t').append(d.unread_count).append('\t').append(d.unread_mentions_count).append('\t')
                        .append(mc.isDialogMuted(d.id, 0) ? 1 : 0).append('\t').append(d.pinned ? 1 : 0).append('\t')
                        .append(d.top_message).append('\t').append(d.last_message_date).append('\t').append(clean(preview)).append('\n');
            }
            for (org.telegram.messenger.MessagesController.DialogFilter f : filters) {
                text.append("filter\t").append(f.id).append('\t').append(clean(f.name)).append('\t').append(f.dialogs.size());
                for (org.telegram.tgnet.TLRPC.Dialog d : f.dialogs) {
                    text.append('\t').append(d.id);
                }
                text.append('\n');
            }
            write("relay-dialogs.txt", text.toString());
            Log.i(TAG, "DEBUG_DUMP_DIALOGS ok dialogs=" + dialogs.size() + " filters=" + filters.size());
        }, "RelayDebugDialogs").start();
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ');
    }

    private static void write(String name, String text) {
        try {
            java.io.File file = new java.io.File(ApplicationLoader.getFilesDirFixed(), name);
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
                out.write(text.getBytes("UTF-8"));
            }
        } catch (Exception e) {
            Log.w(TAG, "write " + name + " failed: " + e);
        }
    }

    /** Opens a chat (optionally at a message) the way a notification tap does. */
    public static void openChat(long dialogId, int messageId) {
        if (dialogId == 0) {
            Log.w(TAG, "DEBUG_OPEN_CHAT: unknown channel");
            return;
        }
        android.os.Bundle args = new android.os.Bundle();
        if (dialogId > 0) {
            args.putLong("user_id", dialogId);
        } else {
            args.putLong("chat_id", -dialogId);
        }
        if (messageId != 0) {
            args.putInt("message_id", messageId);
        }
        org.telegram.ui.ActionBar.BaseFragment last = org.telegram.ui.LaunchActivity.getLastFragment();
        if (last == null) {
            Log.w(TAG, "DEBUG_OPEN_CHAT: no fragment");
            return;
        }
        last.presentFragment(new org.telegram.ui.ChatActivity(args));
        Log.i(TAG, "DEBUG_OPEN_CHAT ok dialog=" + dialogId + " message=" + messageId);
    }

}
