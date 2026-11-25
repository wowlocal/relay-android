package org.telegram.relay;

import android.util.Log;
import android.view.View;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.LaunchActivity;

/**
 * A7a debug helpers behind {@code org.relaychat.DEBUG_A7A} (debug builds only, see
 * {@code RelayDebugHooks}):
 *
 * <ul>
 *   <li>{@code ui_state}: the top fragment to {@code files/relay-ui.txt}: {@code fragment <class>},
 *       {@code chat <dialog> <thread root> <title>}, {@code visible <id> <reply_to> <fwd> <replies> <date> <text>} per message
 *       cell on screen, {@code highlight <id>}.</li>
 *   <li>{@code jump_date --ei date <unix>}: Telegram's {@code ChatActivity.jumpToDate} (the calendar's action).</li>
 *   <li>{@code clipboard}: logs the primary clip (Copy Link check).</li>
 * </ul>
 */
public final class RelayDebugA7a {
    private static final String TAG = "RelayDebug";

    private RelayDebugA7a() {
    }

    public static void run(int account, String op, int date) {
        if ("jump_date".equals(op)) {
            BaseFragment last = LaunchActivity.getLastFragment();
            if (last instanceof ChatActivity) {
                ((ChatActivity) last).jumpToDate(date);
                Log.i(TAG, "DEBUG_A7A jump_date ok " + date);
            } else {
                Log.w(TAG, "DEBUG_A7A jump_date: no open chat");
            }
            return;
        }
        if ("clipboard".equals(op)) {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) ApplicationLoader.applicationContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            CharSequence text = null;
            if (clipboard != null && clipboard.getPrimaryClip() != null && clipboard.getPrimaryClip().getItemCount() > 0) {
                text = clipboard.getPrimaryClip().getItemAt(0).getText();
            }
            Log.i(TAG, "DEBUG_A7A clipboard ok " + text);
            return;
        }
        // ui_state
        StringBuilder out = new StringBuilder();
        BaseFragment last = LaunchActivity.getLastFragment();
        out.append("fragment\t").append(last != null ? last.getClass().getSimpleName() : "none").append('\n');
        if (last instanceof ChatActivity) {
            ChatActivity chat = (ChatActivity) last;
            CharSequence title = chat.getAvatarContainer() != null && chat.getAvatarContainer().getTitleTextView() != null
                    ? chat.getAvatarContainer().getTitleTextView().getText() : null;
            if (title == null || title.length() == 0) {
                title = chat.getActionBar() != null ? chat.getActionBar().getTitle() : "";
            }
            out.append("chat\t").append(chat.getDialogId()).append('\t').append(chat.getThreadId()).append('\t').append(clean(title)).append('\n');
            out.append("highlight\t").append(chat.highlightMessageId).append('\n');
            if (chat.getChatListView() != null) {
                for (int i = 0; i < chat.getChatListView().getChildCount(); i++) {
                    View child = chat.getChatListView().getChildAt(i);
                    if (child instanceof ChatMessageCell) {
                        MessageObject m = ((ChatMessageCell) child).getMessageObject();
                        if (m != null && m.messageOwner != null) {
                            int replyTo = m.messageOwner.reply_to != null ? m.messageOwner.reply_to.reply_to_msg_id : 0;
                            String fwd = m.messageOwner.fwd_from != null && m.messageOwner.fwd_from.from_id != null ? String.valueOf(m.messageOwner.fwd_from.from_id.user_id) : "0";
                            int replies = m.messageOwner.replies != null ? m.messageOwner.replies.replies : 0;
                            out.append("visible\t").append(m.getId()).append('\t').append(replyTo).append('\t').append(fwd).append('\t')
                                    .append(replies).append('\t').append(m.messageOwner.date).append('\t').append(clean(m.messageOwner.message)).append('\n');
                        }
                    }
                }
            }
            out.append("loaded\t").append(chat.messages.size()).append('\n');
        }
        try {
            java.io.File file = new java.io.File(ApplicationLoader.getFilesDirFixed(), "relay-ui.txt");
            try (java.io.FileOutputStream stream = new java.io.FileOutputStream(file)) {
                stream.write(out.toString().getBytes("UTF-8"));
            }
        } catch (Exception e) {
            Log.w(TAG, "relay-ui.txt: " + e);
        }
        Log.i(TAG, "DEBUG_A7A ui_state ok");
    }

    private static String clean(CharSequence s) {
        if (s == null) {
            return "";
        }
        String text = s.toString().replace('\t', ' ').replace('\n', ' ');
        return text.length() > 120 ? text.substring(0, 120) : text;
    }
}
