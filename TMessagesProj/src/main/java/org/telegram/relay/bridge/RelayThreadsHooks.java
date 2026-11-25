package org.telegram.relay.bridge;

import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.relay.RelayConfig;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

/**
 * A7a: collapsed reply threads (CRT) in Telegram's controllers. Small, side-effect free predicates the
 * client calls from a few marked hooks (search {@code RelayThreadsHooks} in {@code MessagesController},
 * {@code MessagesStorage}, {@code ChatActivity} and {@code ChatMessageCell}).
 *
 * <p>Model (iOS 3B, "root post tag"): every Mattermost reply stays a normal megagroup message in the
 * chat's one id space (the id map remains contiguous over all posts), but while CRT is on for the account
 * the chat's <em>main</em> history of a megagroup (channels and GMs) does not show replies; they live in the
 * thread view ({@code getDiscussionMessage} / {@code getReplies}), and roots with replies get Telegram's
 * "N Replies" footer. DMs keep replies inline (Telegram has no thread view for private chats), but with
 * CRT no reply raises an unread badge, DMs included (as Mattermost's {@code _root} counters).
 * With CRT off everything is inline, as before A7a.
 */
public final class RelayThreadsHooks {
    private RelayThreadsHooks() {
    }

    private static final Boolean[] collapsed = new Boolean[UserConfig.MAX_ACCOUNT_COUNT];
    private static final String KV_CRT = "a7a_collapsed_threads";

    /** Set by the bridge whenever it (re)reads the server's {@code CollapsedThreads} and the user's preference (kept for the next start). */
    static void setCollapsedThreads(org.telegram.relay.account.RelayAccount account, boolean on) {
        int slot = account.account;
        if (slot < 0 || slot >= collapsed.length) {
            return;
        }
        if (collapsed[slot] == null || collapsed[slot] != on) {
            boolean switched = collapsed[slot] != null || account.db().kv(KV_CRT) != null && !(on ? "1" : "0").equals(account.db().kv(KV_CRT));
            collapsed[slot] = on;
            if (switched) {
                RelayThreads.onCollapsedThreadsChanged(account);
            }
            account.db().putKv(KV_CRT, on ? "1" : "0");
            RelayConfig.log(RelayConfig.TAG_BRIDGE, "collapsed reply threads " + (on ? "on" : "off") + " for account " + slot);
        }
    }

    /** Forgets the flag (logout). */
    static void reset(int account) {
        if (account >= 0 && account < collapsed.length) {
            collapsed[account] = null;
        }
    }

    /** True when collapsed reply threads are on for the account (Relay mode only). */
    public static boolean collapsedThreads(int account) {
        if (!RelayConfig.ENABLED || account < 0 || account >= collapsed.length) {
            return false;
        }
        Boolean value = collapsed[account];
        if (value == null) {
            org.telegram.relay.account.RelayAccount relayAccount = org.telegram.relay.account.RelayAccount.get(account);
            if (relayAccount == null) {
                return false;
            }
            value = "1".equals(relayAccount.db().kv(KV_CRT));
            collapsed[account] = value;
        }
        return value;
    }

    /** A Mattermost thread reply (every Relay reply is one): a regular message with a reply header in the same chat. */
    public static boolean isThreadReply(TLRPC.Message message) {
        if (message == null || message.reply_to == null || message instanceof TLRPC.TL_messageService || message.action != null) {
            return false;
        }
        if (message.reply_to.reply_to_peer_id != null) {
            return false;
        }
        return message.reply_to.reply_to_top_id != 0 || message.reply_to.reply_to_msg_id != 0;
    }

    /** True when the main history of {@code dialogId} hides thread replies (CRT on, megagroup). */
    public static boolean hidesReplies(int account, long dialogId) {
        return collapsedThreads(account) && dialogId < 0;
    }

    /** {@code MessagesController.processLoadedMessages}: drops thread replies from a megagroup's main history (cache pages). */
    public static void filterMainHistory(int account, long dialogId, int mode, long threadMessageId, ArrayList<TLRPC.Message> messages) {
        if (messages == null || mode != 0 || threadMessageId != 0 || !hidesReplies(account, dialogId)) {
            return;
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (isThreadReply(messages.get(i))) {
                messages.remove(i);
            }
        }
    }

    /** {@code ChatActivity} (new messages): the messages the open chat shows; replies stay out of a megagroup's main history. */
    public static ArrayList<MessageObject> visibleNewMessages(int account, long dialogId, boolean threadChat, ArrayList<MessageObject> messages) {
        if (threadChat || messages == null || !hidesReplies(account, dialogId)) {
            return messages;
        }
        ArrayList<MessageObject> result = null;
        for (int i = 0; i < messages.size(); i++) {
            MessageObject m = messages.get(i);
            if (m != null && isThreadReply(m.messageOwner)) {
                if (result == null) {
                    result = new ArrayList<>(messages.subList(0, i));
                }
            } else if (result != null) {
                result.add(m);
            }
        }
        return result != null ? result : messages;
    }

    /** {@code MessagesStorage.putMessages}: with CRT an incoming reply does not count as unread (Mattermost's {@code _root} counters). */
    public static boolean notCountedAsUnread(int account, TLRPC.Message message) {
        return collapsedThreads(account) && isThreadReply(message);
    }

    /**
     * {@code MessagesController.markDialogAsRead}: reading a megagroup's main history reads its hidden replies
     * too (Mattermost marks the whole channel viewed), so the local read mark covers the chat's top message.
     */
    public static int readMaxId(int account, long dialogId, int maxPositiveId) {
        if (!hidesReplies(account, dialogId) || maxPositiveId <= 0) {
            return maxPositiveId;
        }
        TLRPC.Dialog dialog = MessagesController.getInstance(account).dialogs_dict.get(dialogId);
        if (dialog != null && dialog.top_message > maxPositiveId) {
            return dialog.top_message;
        }
        return maxPositiveId;
    }

    /** {@code ChatMessageCell}: Telegram's comment footer ("N Replies") on thread roots of a megagroup's main history. */
    public static boolean showRepliesFooter(int account, MessageObject message, boolean threadChat) {
        return message != null && !threadChat && message.messageOwner != null && message.getDialogId() < 0
                && collapsedThreads(account) && message.hasReplies() && !message.messageOwner.isThreadMessage
                && !isThreadReply(message.messageOwner) && !message.scheduled;
    }
}
