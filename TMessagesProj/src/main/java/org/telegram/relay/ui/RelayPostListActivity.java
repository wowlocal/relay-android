package org.telegram.relay.ui;

import static org.telegram.messenger.LocaleController.getString;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.relay.bridge.RelayFinding;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.FlickerLoadingView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.MainTabsActivity;
import org.telegram.utils.glass.BaseFragmentSimpleGlass;
import org.telegram.utils.glass.source.GlassSourceRenderNode;

import java.util.ArrayList;

/**
 * Mattermost's "Saved Posts" (flagged posts) and "Recent Mentions" as a Telegram list: one row per post with
 * the chat, the author and the text (Telegram's message search rows); tapping opens the chat at the post.
 * Saved Posts opens from Settings; Recent Mentions is the Mentions tab of the main tab bar ({@link RelayMainTabs},
 * iOS {@code MattermostPostListController(isTab:)}), live: a new post that mentions the user reloads it.
 */
public class RelayPostListActivity extends UniversalFragment implements MainTabsActivity.TabFragmentDelegate, NotificationCenter.NotificationCenterDelegate {

    public static final int MODE_SAVED = 0;
    public static final int MODE_MENTIONS = 1;

    private final int mode;
    /** A page of the main tabs (the Mentions tab): no back button, room for the floating tab bar, glass source. */
    private final boolean isTab;
    private final ArrayList<MessageObject> messages = new ArrayList<>();
    private boolean loading = true;
    private boolean reloadQueued;
    private String error;

    public RelayPostListActivity(int mode) {
        this(mode, false);
    }

    private RelayPostListActivity(int mode, boolean isTab) {
        super();
        this.mode = mode;
        this.isTab = isTab;
    }

    /** The Mentions tab (RelayMainTabs). */
    public static RelayPostListActivity mentionsTab() {
        return new RelayPostListActivity(MODE_MENTIONS, true);
    }

    public static RelayPostListActivity saved() {
        return new RelayPostListActivity(MODE_SAVED);
    }

    public static RelayPostListActivity mentions() {
        return new RelayPostListActivity(MODE_MENTIONS);
    }

    @Override
    public boolean onFragmentCreate() {
        if (mode == MODE_MENTIONS) {
            NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.didReceiveNewMessages);
        }
        load();
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.didReceiveNewMessages);
        AndroidUtilities.cancelRunOnUIThread(liveReload);
        super.onFragmentDestroy();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!loading && fragmentView != null) {
            load();
        }
    }

    /** Live Recent Mentions: a new post that mentions the user (the bridge's {@code mentioned}) reloads the list. */
    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id != NotificationCenter.didReceiveNewMessages || account != currentAccount || args.length < 2 || !(args[1] instanceof ArrayList)) {
            return;
        }
        if (args.length > 2 && args[2] instanceof Boolean && (Boolean) args[2]) {
            return; // scheduled
        }
        for (Object object : (ArrayList<?>) args[1]) {
            if (object instanceof MessageObject && ((MessageObject) object).messageOwner != null
                    && ((MessageObject) object).messageOwner.mentioned && !((MessageObject) object).isOut()) {
                AndroidUtilities.cancelRunOnUIThread(liveReload);
                AndroidUtilities.runOnUIThread(liveReload, 1000);
                return;
            }
        }
    }

    private final Runnable liveReload = () -> {
        if (loading) {
            reloadQueued = true;
        } else {
            load(true);
        }
    };

    private void load() {
        load(false);
    }

    private void load(boolean fresh) {
        loading = true;
        RelayFinding.Callback<TLRPC.messages_Messages> done = (res, err) -> {
            loading = false;
            if (reloadQueued) {
                reloadQueued = false;
                AndroidUtilities.runOnUIThread(() -> load(true));
            }
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
                // A list at its top stays there: a new mention (live) shows instead of being inserted above the screen.
                boolean atTop = listView.layoutManager == null || listView.layoutManager.findFirstVisibleItemPosition() <= 0;
                listView.adapter.update(true);
                if (atTop) {
                    listView.scrollToPosition(0);
                }
            }
        };
        if (mode == MODE_SAVED) {
            RelayFinding.loadSaved(currentAccount, done);
        } else if (fresh) {
            RelayFinding.reloadMentions(currentAccount, done);
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
    protected boolean useSimpleActionBar() {
        return isTab;
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return isTab;
    }

    @Override
    public boolean drawEdgeNavigationBar() {
        return false; // the tab bar's fade is under the tabs
    }

    @Nullable
    @Override
    protected RecyclerListView getListViewForSimpleGlass() {
        return isTab ? listView : null;
    }

    @Override
    public View createView(Context context) {
        View view = super.createView(context);
        if (isTab) {
            // A main tab: no back button; the list is sectioned on the page background, like the Settings tab.
            setHasOwnBackground(true);
            simpleActionBar.setBackButtonDrawable(null);
            listView.setSections();
            listView.setSectionsDrawBackground(true);
            listView.adapter.setApplyBackground(false);
            listView.setClipToPadding(false);
            ViewCompat.setOnApplyWindowInsetsListener(fragmentView, this::onInsetsInternal);
        }
        return view;
    }

    @Override
    public void onInsets(int left, int top, int right, int bottom) {
        if (isTab && listView != null) {
            BaseFragmentSimpleGlass.setListViewParams(listView, top, bottom, ActionBar.getCurrentActionBarHeight(), dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS));
        }
    }

    /** Debug/e2e (RelayDebugTabs): the listed posts (Mattermost ids) and the first visible row. */
    public void debugState(StringBuilder out) {
        out.append("mentions_rows\t");
        for (int i = 0; i < messages.size(); i++) {
            MessageObject object = messages.get(i);
            out.append(i > 0 ? "," : "").append(RelayFinding.debugPostId(currentAccount, object.getDialogId(), object.getId()));
        }
        out.append("\nloading\t").append(loading).append('\n');
        int first = -1;
        if (listView != null && listView.layoutManager != null) {
            first = listView.layoutManager.findFirstCompletelyVisibleItemPosition();
        }
        out.append("first\t").append(first).append('\n');
    }

    @Override
    public void onParentScrollToTop() {
        if (listView != null) {
            listView.smoothScrollToPosition(0);
        }
    }

    @Override
    public boolean canParentTabsSlide(MotionEvent ev, boolean forward) {
        return true;
    }

    @Override
    public GlassSourceRenderNode getGlassSource() {
        return isTab ? getBaseSimpleGlass().getGlassSource() : null;
    }

    @Override
    public GlassSourceRenderNode getFrostedGlassSource() {
        return isTab ? getBaseSimpleGlass().getFrostedGlassSource() : null;
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
