package org.telegram.relay;

import android.os.Bundle;
import android.util.Log;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationsController;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.GroupCreateActivity;
import org.telegram.ui.LaunchActivity;

import java.util.List;

/**
 * Polish 2a debug helpers behind {@code org.relaychat.DEBUG_POLISH2A --es op …} (debug builds only, see
 * {@code RelayPolish2aDebugHooks}). Results are logged with tag {@code RelayDebug} as {@code DEBUG_POLISH2A <op> ok …};
 * dumps go to {@code files/relay-polish2a.txt}.
 *
 * <ul>
 *   <li>{@code pin --es channel <id> --es on 1|0}: pins/unpins the chat as the chat list's Pin does
 *       ({@code MessagesController.pinDialog}).</li>
 *   <li>{@code folders}: the client's folders: {@code filter <id> <name> <unread> <always-show dialog ids> <pinned ids>}.</li>
 *   <li>{@code notif}: NotificationsController state: pushed messages, summary count, chats.</li>
 *   <li>{@code limits}: max message length; for the open chat whether scheduling and Clear History are offered.</li>
 *   <li>{@code add_members --es channel <id>}: opens Add Members for the chat; {@code add_members_dump}: usernames listed.</li>
 *   <li>{@code invite --es channel <id> --es username <name>}: {@code channels.inviteToChannel} → the error text.</li>
 * </ul>
 */
public final class RelayDebugPolish2a {
    private static final String TAG = "RelayDebug";

    private RelayDebugPolish2a() {
    }

    public static void run(int account, String op, android.content.Intent intent) {
        try {
            switch (op == null ? "" : op) {
                case "pin": {
                    long chatId = org.telegram.relay.bridge.RelayFinding.debugChatId(account, intent.getStringExtra("channel"));
                    boolean on = "1".equals(intent.getStringExtra("on"));
                    boolean done = chatId != 0 && MessagesController.getInstance(account).pinDialog(-chatId, on, null, 0);
                    Log.i(TAG, "DEBUG_POLISH2A pin ok " + (-chatId) + " " + on + " " + done);
                    break;
                }
                case "folders":
                    folders(account);
                    break;
                case "notif":
                    notif(account);
                    break;
                case "limits": {
                    BaseFragment last = LaunchActivity.getLastFragment();
                    String chat = "-";
                    if (last instanceof ChatActivity) {
                        ChatActivity c = (ChatActivity) last;
                        Object clear = field(c, ChatActivity.class, "clearHistoryItem");
                        chat = "schedule=" + c.canScheduleMessage() + " clear_history=" + (clear != null);
                    }
                    Log.i(TAG, "DEBUG_POLISH2A limits ok max_length=" + MessagesController.getInstance(account).getMaxMessageLength() + " " + chat);
                    break;
                }
                case "add_members": {
                    long chatId = org.telegram.relay.bridge.RelayFinding.debugChatId(account, intent.getStringExtra("channel"));
                    Bundle args = new Bundle();
                    args.putBoolean("addToGroup", true);
                    args.putLong("chatId", chatId);
                    BaseFragment last = LaunchActivity.getLastFragment();
                    if (last != null) {
                        last.presentFragment(new GroupCreateActivity(args));
                    }
                    Log.i(TAG, "DEBUG_POLISH2A add_members ok " + chatId);
                    break;
                }
                case "add_members_dump": {
                    BaseFragment last = LaunchActivity.getLastFragment();
                    StringBuilder out = new StringBuilder();
                    int count = 0;
                    if (last instanceof GroupCreateActivity) {
                        Object adapter = field(last, GroupCreateActivity.class, "adapter");
                        Object contacts = adapter != null ? field(adapter, adapter.getClass(), "contacts") : null;
                        if (contacts instanceof List) {
                            for (Object o : (List<?>) contacts) {
                                if (o instanceof TLRPC.User) {
                                    out.append("user\t").append(((TLRPC.User) o).username).append('\n');
                                    count++;
                                }
                            }
                        }
                    }
                    write(out.toString());
                    Log.i(TAG, "DEBUG_POLISH2A add_members_dump ok " + count);
                    break;
                }
                case "invite": {
                    long chatId = org.telegram.relay.bridge.RelayFinding.debugChatId(account, intent.getStringExtra("channel"));
                    TLRPC.User user = MessagesController.getInstance(account).getUser(intent.getStringExtra("username"));
                    TLRPC.TL_channels_inviteToChannel req = new TLRPC.TL_channels_inviteToChannel();
                    req.channel = MessagesController.getInstance(account).getInputChannel(chatId);
                    if (user != null) {
                        req.users.add(MessagesController.getInstance(account).getInputUser(user));
                    }
                    ConnectionsManager.getInstance(account).sendRequest(req, (TLObject response, TLRPC.TL_error error) ->
                            Log.i(TAG, "DEBUG_POLISH2A invite ok " + (error != null ? error.text : "added")));
                    break;
                }
                case "compose": {
                    // A long text in the open chat's composer (adb input drops characters of long texts).
                    BaseFragment last = LaunchActivity.getLastFragment();
                    int length = Integer.parseInt(intent.getStringExtra("length"));
                    String tag = intent.getStringExtra("tag");
                    StringBuilder text = new StringBuilder(tag != null ? tag + " " : "");
                    int word = 0;
                    while (text.length() < length) {
                        text.append("word").append(word++ % 10).append(' ');
                    }
                    text.setLength(length);
                    if (last instanceof ChatActivity && ((ChatActivity) last).getChatActivityEnterView() != null) {
                        ((ChatActivity) last).getChatActivityEnterView().setFieldText(text.toString());
                        Log.i(TAG, "DEBUG_POLISH2A compose ok " + text.length());
                    } else {
                        Log.w(TAG, "DEBUG_POLISH2A compose: no chat");
                    }
                    break;
                }
                case "edit_append": {
                    // As the editor does: the shown text (shortcodes already emoji) plus a suffix → messages.editMessage.
                    BaseFragment last = LaunchActivity.getLastFragment();
                    String match = intent.getStringExtra("match");
                    String suffix = intent.getStringExtra("suffix");
                    if (!(last instanceof ChatActivity) || match == null) {
                        Log.w(TAG, "DEBUG_POLISH2A edit_append: no chat");
                        break;
                    }
                    ChatActivity c = (ChatActivity) last;
                    org.telegram.messenger.MessageObject target = null;
                    for (org.telegram.messenger.MessageObject m : c.messages) {
                        if (m != null && m.messageOwner != null && m.messageOwner.message != null && m.messageOwner.message.contains(match)) {
                            target = m;
                            break;
                        }
                    }
                    if (target == null) {
                        Log.w(TAG, "DEBUG_POLISH2A edit_append: no message");
                        break;
                    }
                    TLRPC.TL_messages_editMessage req = new TLRPC.TL_messages_editMessage();
                    req.peer = MessagesController.getInstance(account).getInputPeer(c.getDialogId());
                    req.id = target.getId();
                    req.message = target.messageOwner.message + (suffix != null ? suffix : "");
                    req.flags |= 2048;
                    String sent = req.message;
                    ConnectionsManager.getInstance(account).sendRequest(req, (TLObject response, TLRPC.TL_error error) -> {
                        if (response instanceof TLRPC.Updates) {
                            MessagesController.getInstance(account).processUpdates((TLRPC.Updates) response, false);
                        }
                        Log.i(TAG, "DEBUG_POLISH2A edit_append ok " + (error != null ? error.text : "edited") + " sent=" + sent);
                    });
                    break;
                }
                default:
                    Log.w(TAG, "DEBUG_POLISH2A unknown op " + op);
            }
        } catch (Throwable t) {
            Log.w(TAG, "DEBUG_POLISH2A " + op + " failed: " + t);
        }
    }

    private static void folders(int account) {
        StringBuilder out = new StringBuilder();
        MessagesController mc = MessagesController.getInstance(account);
        for (MessagesController.DialogFilter f : mc.dialogFilters) {
            StringBuilder always = new StringBuilder();
            for (Long did : f.alwaysShow) {
                always.append(always.length() > 0 ? "," : "").append(did);
            }
            StringBuilder pinned = new StringBuilder();
            for (int i = 0; i < f.pinnedDialogs.size(); i++) {
                pinned.append(pinned.length() > 0 ? "," : "").append(f.pinnedDialogs.keyAt(i));
            }
            out.append("filter\t").append(f.id).append('\t').append(f.name).append('\t').append(f.unreadCount).append('\t')
                    .append(always.length() > 0 ? always : "-").append('\t').append(pinned.length() > 0 ? pinned : "-").append('\n');
        }
        write(out.toString());
        Log.i(TAG, "DEBUG_POLISH2A folders ok " + mc.dialogFilters.size());
    }

    private static void notif(int account) {
        NotificationsController nc = NotificationsController.getInstance(account);
        org.telegram.messenger.Utilities.globalQueue.postRunnable(() -> {
            Object messages = field(nc, NotificationsController.class, "pushMessages");
            Object dialogs = field(nc, NotificationsController.class, "pushDialogs");
            int m = messages instanceof List ? ((List<?>) messages).size() : -1;
            int d = -1;
            try {
                d = (int) dialogs.getClass().getMethod("size").invoke(dialogs);
            } catch (Throwable ignore) {
            }
            Log.i(TAG, "DEBUG_POLISH2A notif ok messages=" + m + " total=" + nc.getTotalUnreadCount() + " chats=" + d);
        });
    }

    private static Object field(Object target, Class<?> type, String name) {
        try {
            java.lang.reflect.Field f = type.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void write(String text) {
        try {
            java.io.File file = new java.io.File(ApplicationLoader.getFilesDirFixed(), "relay-polish2a.txt");
            try (java.io.FileOutputStream stream = new java.io.FileOutputStream(file)) {
                stream.write(text.getBytes("UTF-8"));
            }
        } catch (Exception e) {
            Log.w(TAG, "relay-polish2a.txt: " + e);
        }
    }

    static AccountInstance instance(int account) {
        return AccountInstance.getInstance(account);
    }
}
