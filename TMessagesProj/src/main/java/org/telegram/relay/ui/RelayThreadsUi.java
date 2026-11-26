package org.telegram.relay.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.TextPaint;

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
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BulletinFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * A7a: the UI side of Mattermost threads: opening a thread (Telegram's thread view over
 * {@code getDiscussionMessage}), Follow / Unfollow Thread in the message menu, and the chat list's threads
 * button with its unread badge. Hooks call in from {@code ChatActivity} and {@code DialogsActivity}.
 */
public final class RelayThreadsUi {
    private RelayThreadsUi() {
    }

    private static final String TAG = "RelayThreads";

    /** Message menu options (outside Telegram's OPTION_* range). */
    public static final int OPTION_FOLLOW_THREAD = 0x52a7a01;
    public static final int OPTION_UNFOLLOW_THREAD = 0x52a7a02;

    /** Action bar item id of the chat list's threads button. */
    public static final int MENU_THREADS = 0x52a7a10;

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

    // MARK: - Chat list button

    /** The chat list's selected folder (dialog filter id; 0 = "All"). */
    public interface FolderProvider {
        int currentFilterId();
    }

    /**
     * Adds the threads button to the chat list's action bar (left of the overflow menu): opens the Threads
     * list for the team of the selected folder ({@code currentFilterId}, "All" → the first team); the badge
     * shows the unread followed threads of all teams and follows {@link RelayThreads.Listener}.
     */
    public static org.telegram.ui.ActionBar.ActionBarMenuItem attachChatListButton(BaseFragment fragment, org.telegram.ui.ActionBar.ActionBarMenu menu,
                                                                                  FolderProvider currentFilterId) {
        Context context = fragment.getContext() != null ? fragment.getContext() : menu.getContext();
        ButtonDrawable drawable = new ButtonDrawable(context);
        org.telegram.ui.ActionBar.ActionBarMenuItem item = menu.addItem(MENU_THREADS, drawable);
        item.setContentDescription(LocaleController.getString(R.string.RelayThreads));
        final int account = fragment.getCurrentAccount();
        item.setOnClickListener(v -> {
            Bundle args = new Bundle();
            String teamId = RelayThreads.teamIdOfFolder(account, currentFilterId.currentFilterId());
            if (teamId != null) {
                args.putString("team_id", teamId);
            }
            fragment.presentFragment(new RelayThreadsActivity(args));
        });
        RelayThreads.Listener listener = changed -> {
            RelayThreads.Totals totals = RelayThreads.totals(account);
            drawable.setCount(totals.unreadThreads, totals.unreadMentions);
            String badge = drawable.badge();
            item.setContentDescription(LocaleController.getString(R.string.RelayThreads) + (badge != null ? " " + badge : ""));
        };
        item.addOnAttachStateChangeListener(new android.view.View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(android.view.View v) {
                RelayThreads.addListener(listener);
                listener.onThreadsChanged(account);
                RelayThreads.prefetch(account);
            }

            @Override
            public void onViewDetachedFromWindow(android.view.View v) {
                RelayThreads.removeListener(listener);
            }
        });
        return item;
    }

    /** The chat list's threads button icon: Telegram's replies glyph with the unread followed-threads badge. */
    public static final class ButtonDrawable extends Drawable {
        private final Drawable icon;
        private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private String badge;

        public ButtonDrawable(Context context) {
            icon = context.getResources().getDrawable(R.drawable.msg_discussion).mutate();
            textPaint.setTextSize(AndroidUtilities.dp(10));
            textPaint.setTypeface(AndroidUtilities.bold());
            textPaint.setColor(0xffffffff);
            updateColors();
        }

        public void updateColors() {
            icon.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_actionBarDefaultIcon), PorterDuff.Mode.MULTIPLY));
            badgePaint.setColor(Theme.getColor(Theme.key_chats_unreadCounter));
            invalidateSelf();
        }

        /** {@code count} unread threads ({@code mentions} > 0 → an "@" in front). */
        public void setCount(int count, int mentions) {
            String value = count <= 0 ? null : (mentions > 0 ? "@" : "") + (count > 99 ? "99+" : String.valueOf(count));
            if (value == null ? badge != null : !value.equals(badge)) {
                badge = value;
                invalidateSelf();
            }
        }

        public String badge() {
            return badge;
        }

        @Override
        public void draw(Canvas canvas) {
            android.graphics.Rect b = getBounds();
            int w = icon.getIntrinsicWidth();
            int h = icon.getIntrinsicHeight();
            int cx = b.centerX();
            int cy = b.centerY();
            icon.setBounds(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
            icon.draw(canvas);
            if (badge != null) {
                float textWidth = textPaint.measureText(badge);
                float height = AndroidUtilities.dp(16);
                float width = Math.max(height, textWidth + AndroidUtilities.dp(8));
                float left = cx + w / 2f - AndroidUtilities.dp(6);
                float top = cy - h / 2f - AndroidUtilities.dp(4);
                rect.set(left, top, left + width, top + height);
                canvas.drawRoundRect(rect, height / 2, height / 2, badgePaint);
                canvas.drawText(badge, rect.centerX() - textWidth / 2, rect.centerY() + AndroidUtilities.dp(3.5f), textPaint);
            }
        }

        @Override
        public void setAlpha(int alpha) {
            icon.setAlpha(alpha);
            badgePaint.setAlpha(alpha);
            textPaint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            // Polish 2b: the action bar tints its icons (white in dark mode); the badge keeps its own colours.
            if (colorFilter != null) {
                icon.setColorFilter(colorFilter);
                invalidateSelf();
            }
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }

        @Override
        public int getIntrinsicWidth() {
            return AndroidUtilities.dp(48);
        }

        @Override
        public int getIntrinsicHeight() {
            return AndroidUtilities.dp(48);
        }
    }
}
