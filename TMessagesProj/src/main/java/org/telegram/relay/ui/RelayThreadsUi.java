package org.telegram.relay.ui;

import android.os.Bundle;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.relay.RelayConfig;
import org.telegram.relay.bridge.RelayThreads;
import org.telegram.relay.bridge.RelayThreadsHooks;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BulletinFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * A7a: the UI side of Mattermost threads: opening a thread (Telegram's thread view over
 * {@code getDiscussionMessage}) and Follow / Unfollow Thread in the message menu. Hooks call in from
 * {@code ChatActivity}. The threads list is the Threads tab ({@link RelayMainTabs}; it replaced the chat list's
 * threads button).
 */
public final class RelayThreadsUi {
    private RelayThreadsUi() {
    }

    private static final String TAG = "RelayThreads";

    /** Message menu options (outside Telegram's OPTION_* range). */
    public static final int OPTION_FOLLOW_THREAD = 0x52a7a01;
    public static final int OPTION_UNFOLLOW_THREAD = 0x52a7a02;

    // MARK: - Opening a thread

    /**
     * Opens the thread of {@code rootMessageId} in a megagroup or (DM threads) a DM, showing {@code highlightId} (a
     * reply) when non-zero, else the first unread reply or the start of the thread.
     *
     * <p>A DM's thread is Telegram's thread view of a private chat: {@code ChatActivity} with {@code user_id} and
     * {@code setThreadMessages} without an original chat, as Telegram 13 runs it for bot forum topics (no topic here);
     * it loads through {@code messages.getReplies} with the user peer and reads with {@code readDiscussion}.
     */
    public static void openThread(BaseFragment from, int account, long dialogId, int rootMessageId, int highlightId) {
        if (from == null || !RelayThreadsHooks.threadsIn(account, dialogId)) {
            return;
        }
        MessagesController mc = MessagesController.getInstance(account);
        TLRPC.Chat chat = dialogId < 0 ? mc.getChat(-dialogId) : null;
        TLRPC.User user = dialogId > 0 ? mc.getUser(dialogId) : null;
        if (chat == null && user == null) {
            RelayConfig.log(TAG, "openThread: unknown chat " + dialogId);
            return;
        }
        TLRPC.TL_messages_getDiscussionMessage req = new TLRPC.TL_messages_getDiscussionMessage();
        req.peer = chat != null ? MessagesController.getInputPeer(chat) : MessagesController.getInputPeer(user);
        req.msg_id = rootMessageId;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (!(response instanceof TLRPC.TL_messages_discussionMessage)) {
                if (from.getParentActivity() != null) {
                    BulletinFactory.of(from).createErrorBulletin(LocaleController.getString(R.string.RelayLinkNotFound)).show();
                }
                return;
            }
            TLRPC.TL_messages_discussionMessage res = (TLRPC.TL_messages_discussionMessage) response;
            mc.putUsers(res.users, false);
            mc.putChats(res.chats, false);
            ArrayList<MessageObject> objects = new ArrayList<>();
            for (TLRPC.Message message : res.messages) {
                if (message instanceof TLRPC.TL_messageEmpty) {
                    continue;
                }
                message.isThreadMessage = true;
                objects.add(new MessageObject(account, message, true, true));
            }
            if (objects.isEmpty()) {
                return;
            }
            Bundle args = new Bundle();
            if (chat != null) {
                args.putLong("chat_id", -dialogId);
            } else {
                args.putLong("user_id", dialogId); // DM threads
            }
            args.putInt("message_id", highlightId != 0 ? highlightId : Math.max(1, res.read_inbox_max_id));
            args.putInt("unread_count", res.unread_count);
            args.putBoolean("historyPreloaded", false);
            ChatActivity chatActivity = new ChatActivity(args);
            chatActivity.setThreadMessages(objects, chat, rootMessageId, res.read_inbox_max_id, res.read_outbox_max_id, null);
            if (highlightId != 0) {
                chatActivity.highlightMessageId = highlightId;
            }
            from.presentFragment(chatActivity);
            RelayConfig.log(TAG, "opened thread " + rootMessageId + " in " + dialogId + (highlightId != 0 ? " at " + highlightId : ""));
        }));
    }

    /**
     * Reply in a main history with collapsed threads (megagroups and DMs; message menu, polish 1: swipe-to-reply and
     * the selection bar's Reply too): opens the post's thread instead of quoting into the chat. True when handled.
     */
    public static boolean replyInThread(ChatActivity chat, MessageObject message) {
        if (!RelayConfig.ENABLED || chat == null || message == null || chat.isThreadChat()
                || !RelayThreadsHooks.hidesReplies(chat.getCurrentAccount(), message.getDialogId()) || message.messageOwner == null || message.messageOwner.action != null
                || message.getId() <= 0 || message.scheduled || chat.getChatMode() != 0) {
            return false;
        }
        // Any part of a root post (the bridge resolves the post), or a reply's root.
        int root = message.getReplyTopMsgId() != 0 ? message.getReplyTopMsgId() : message.getId();
        RelayConfig.log(TAG, "reply in thread " + root + " of " + message.getDialogId());
        openThread(chat, chat.getCurrentAccount(), message.getDialogId(), root, 0);
        return true;
    }

    /** Polish 1: the "N Replies" footer opens the thread at its first unread reply ({@link #openThread}), as iOS. */
    public static boolean openFromFooter(ChatActivity chat, MessageObject message) {
        if (!RelayConfig.ENABLED || chat == null || message == null || chat.isThreadChat()
                || !RelayThreadsHooks.hidesReplies(chat.getCurrentAccount(), message.getDialogId()) || message.getId() <= 0) {
            return false;
        }
        RelayConfig.log(TAG, "footer: thread " + message.getId() + " of " + message.getDialogId());
        openThread(chat, chat.getCurrentAccount(), message.getDialogId(), message.getId(), 0);
        return true;
    }

    // MARK: - Message menu

    /**
     * Adds Follow / Unfollow Thread for messages of a thread (roots with replies, replies) with collapsed threads on,
     * and (DM threads) "View N Replies" on a DM's roots: Telegram offers it in megagroups only.
     */
    public static void addMessageOptions(ChatActivity chat, MessageObject message, List<Integer> options, List<CharSequence> items, List<Integer> icons) {
        if (!RelayConfig.ENABLED || chat == null || message == null || message.scheduled || message.messageOwner == null || message.messageOwner.action != null) {
            return;
        }
        int account = chat.getCurrentAccount();
        long dialogId = message.getDialogId();
        if (!RelayThreadsHooks.hidesReplies(account, dialogId) || message.getId() <= 0) {
            return;
        }
        if (dialogId > 0 && !chat.isThreadChat() && message.hasReplies() && !options.contains(ChatActivity.OPTION_VIEW_REPLIES_OR_THREAD)) {
            int at = Math.min(options.size(), Math.max(0, options.indexOf(ChatActivity.OPTION_REPLY) + 1));
            options.add(at, ChatActivity.OPTION_VIEW_REPLIES_OR_THREAD);
            items.add(at, LocaleController.formatPluralString("ViewReplies", message.getRepliesCount()));
            icons.add(at, R.drawable.msg_viewreplies);
        }
        String root = RelayThreads.rootPostIdOf(account, dialogId, message.getId());
        if (root == null) {
            return;
        }
        Boolean following = RelayThreads.isFollowing(account, root);
        boolean unfollow = following != null && following;
        RelayConfig.log(TAG, "menu: message " + message.getId() + " thread " + root + " following=" + following);
        int at = Math.min(options.size(), Math.max(0, options.indexOf(27) + 1)); // after "View Replies" when present
        options.add(at, unfollow ? OPTION_UNFOLLOW_THREAD : OPTION_FOLLOW_THREAD);
        items.add(at, LocaleController.getString(unfollow ? R.string.RelayUnfollowThread : R.string.RelayFollowThread));
        icons.add(at, unfollow ? R.drawable.msg_mute : R.drawable.msg_unmute);
    }

    /**
     * Handles the options added above; true when consumed. Also Reply in a megagroup's main history with
     * collapsed threads: the reply would leave the main history at once, so Reply opens the thread instead
     * (Mattermost's reply arrow opens the thread too).
     */
    public static boolean handleMessageOption(ChatActivity chat, int option, MessageObject message) {
        if (option == ChatActivity.OPTION_REPLY && replyInThread(chat, message)) {
            return true;
        }
        if (option == ChatActivity.OPTION_VIEW_REPLIES_OR_THREAD && message != null && message.getDialogId() > 0
                && RelayThreadsHooks.hidesReplies(chat.getCurrentAccount(), message.getDialogId())) {
            // DM threads: upstream opens a megagroup's discussion (currentChat); a DM's thread opens here.
            openThread(chat, chat.getCurrentAccount(), message.getDialogId(), message.getId(), 0);
            return true;
        }
        if (option != OPTION_FOLLOW_THREAD && option != OPTION_UNFOLLOW_THREAD) {
            return false;
        }
        if (message == null) {
            return true;
        }
        boolean follow = option == OPTION_FOLLOW_THREAD;
        int account = chat.getCurrentAccount();
        RelayThreads.setFollowing(account, message.getDialogId(), message.getId(), follow, (result, error) -> {
            if (chat.getParentActivity() == null) {
                return;
            }
            if (error != null) {
                BulletinFactory.of(chat).createErrorBulletin(LocaleController.getString(R.string.RelayLinkFailed)).show();
            } else {
                BulletinFactory.of(chat).createSimpleBulletin(follow ? R.raw.contact_check : R.raw.ic_ban,
                        LocaleController.getString(follow ? R.string.RelayThreadFollowed : R.string.RelayThreadUnfollowed)).show();
            }
        });
        return true;
    }

    // MARK: - Threads tab

    /** The chat list's selected folder (dialog filter id; 0 = "All"): the Threads tab opens on its team. */
    public interface FolderProvider {
        int currentFilterId();
    }
}
