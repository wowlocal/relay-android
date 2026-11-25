package org.telegram.relay.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.os.Bundle;
import android.view.View;

import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.R;
import org.telegram.relay.bridge.RelayFinding;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.FlickerLoadingView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;

/**
 * Mattermost's "Saved Posts" (flagged posts) and "Recent Mentions" as a Telegram list: one row per post with
 * the chat, the author and the text (Telegram's message search rows); tapping opens the chat at the post.
 * Opened from Settings.
 */
public class RelayPostListActivity extends UniversalFragment {

    public static final int MODE_SAVED = 0;
    public static final int MODE_MENTIONS = 1;

    private final int mode;
    private final ArrayList<MessageObject> messages = new ArrayList<>();
    private boolean loading = true;
    private String error;

    public RelayPostListActivity(int mode) {
        super();
        this.mode = mode;
    }

    public static RelayPostListActivity saved() {
        return new RelayPostListActivity(MODE_SAVED);
    }

    public static RelayPostListActivity mentions() {
        return new RelayPostListActivity(MODE_MENTIONS);
    }

    @Override
    public boolean onFragmentCreate() {
        load();
        return super.onFragmentCreate();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!loading && fragmentView != null) {
            load();
        }
    }

    private void load() {
        loading = true;
        RelayFinding.Callback<TLRPC.messages_Messages> done = (res, err) -> {
            loading = false;
            error = err;
            messages.clear();
            if (res != null) {
                MessagesController.getInstance(currentAccount).putUsers(res.users, false);
                MessagesController.getInstance(currentAccount).putChats(res.chats, false);
                MessagesStorage.getInstance(currentAccount).putUsersAndChats(res.users, res.chats, true, true);
                StringBuilder ids = new StringBuilder();
                for (TLRPC.Message message : res.messages) {
                    MessageObject object = new MessageObject(currentAccount, message, false, true);
                    messages.add(object);
                    if (ids.length() > 0) {
                        ids.append(',');
                    }
                    ids.append(RelayFinding.debugPostId(currentAccount, object.getDialogId(), object.getId()));
                }
                org.telegram.relay.RelayConfig.log("RelayA7b", "A7B list " + (mode == MODE_SAVED ? "saved" : "mentions") + " n=" + messages.size() + " items=[" + ids + "]");
            }
            if (listView != null) {
                listView.adapter.update(true);
            }
        };
        if (mode == MODE_SAVED) {
            RelayFinding.loadSaved(currentAccount, done);
        } else {
            RelayFinding.loadMentions(currentAccount, done);
        }
    }

    /** Debug/e2e: opens one of the A7b screens over the current one. */
    public static boolean debugOpen(String screen) {
        org.telegram.ui.ActionBar.BaseFragment last = org.telegram.ui.LaunchActivity.getLastFragment();
        if (last == null || screen == null) {
            return false;
        }
        org.telegram.ui.ActionBar.BaseFragment fragment;
        switch (screen) {
            case "saved": fragment = saved(); break;
            case "mentions": fragment = mentions(); break;
            case "browse": fragment = new RelayBrowseChannelsActivity(); break;
            case "newchannel": fragment = new RelayNewChannelActivity(); break;
            default: return false;
        }
        return last.presentFragment(fragment);
    }

    @Override
    protected CharSequence getTitle() {
        return getString(mode == MODE_SAVED ? R.string.RelaySavedPosts : R.string.RelayRecentMentions);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (loading && messages.isEmpty()) {
            items.add(UItem.asFlicker(FlickerLoadingView.DIALOG_CELL_TYPE));
            items.add(UItem.asFlicker(FlickerLoadingView.DIALOG_CELL_TYPE));
            items.add(UItem.asFlicker(FlickerLoadingView.DIALOG_CELL_TYPE));
            return;
        }
        if (messages.isEmpty()) {
            items.add(UItem.asShadow(error != null ? error : getString(mode == MODE_SAVED ? R.string.RelaySavedPostsEmpty : R.string.RelayRecentMentionsEmpty)));
            return;
        }
        for (int i = 0; i < messages.size(); i++) {
            items.add(UItem.asSearchMessage(i + 1, messages.get(i)));
        }
        items.add(UItem.asShadow(null));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (!(item.object instanceof MessageObject)) {
            return;
        }
        MessageObject message = (MessageObject) item.object;
        long dialogId = message.getDialogId();
        Bundle args = new Bundle();
        if (DialogObject.isUserDialog(dialogId)) {
            args.putLong("user_id", dialogId);
        } else {
            args.putLong("chat_id", -dialogId);
        }
        args.putInt("message_id", message.getId());
        presentFragment(new ChatActivity(args));
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
