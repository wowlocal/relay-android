package org.telegram.relay.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.relay.RelayConfig;
import org.telegram.relay.bridge.RelayThreads;
import org.telegram.relay.bridge.RelayThreadsHooks;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeBind;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.SizeNotifierFrameLayout;
import org.telegram.ui.Components.ViewPagerFixed;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.MainTabsActivity;
import org.telegram.utils.glass.BaseFragmentSimpleGlass;
import org.telegram.utils.glass.source.GlassSourceRenderNode;

import java.util.ArrayList;
import java.util.List;

/**
 * A7a: the Threads list (iOS {@code MattermostThreadsController}, Mattermost's "Followed threads"). It is the Threads
 * tab of the main tab bar ({@link RelayMainTabs}; {@code hasMainTabs}), which opens on the team of the chat list's
 * selected team folder ("All" keeps the team); pushed with {@code team_id} it opens on that team. One tab per team
 * ({@code GET /users/me/teams/{team}/threads}) in a glass pill under the header; a row shows the root's author, chat,
 * text, reply count, new replies and mentions; tapping opens the thread, a long press offers Mark as Read and
 * Unfollow. Updates live through {@link RelayThreads.Listener}. With collapsed reply threads off it says so (as iOS).
 */
public class RelayThreadsActivity extends BaseFragment implements RelayThreads.Listener, MainTabsActivity.TabFragmentDelegate {
    private static final String TAG = "RelayThreads";

    private final List<RelayThreads.TeamInfo> teams = new ArrayList<>();
    private final List<RelayThreads.ThreadRow> rows = new ArrayList<>();
    private String teamId;
    private boolean hasMainTabs;
    private RelayThreadsUi.FolderProvider folderProvider;
    private int additionNavigationBarHeight;

    private FrameLayout contentView;
    private FrameLayout tabsContainer;
    private ViewPagerFixed.TabsView tabsView;
    private View listTopOverlay;
    private RecyclerListView listView;
    private LinearLayoutManager layoutManager;
    private Adapter adapter;
    private TextView emptyView;
    private boolean loading;
    private boolean loaded;
    private boolean reloadQueued;

    public RelayThreadsActivity(Bundle args) {
        super(args);
    }

    /** The Threads tab: the chat list's selected folder, whose team the list opens on each time it shows. */
    public void setFolderProvider(RelayThreadsUi.FolderProvider provider) {
        folderProvider = provider;
    }

    @Override
    public boolean onFragmentCreate() {
        teamId = arguments != null ? arguments.getString("team_id") : null;
        hasMainTabs = arguments != null && arguments.getBoolean("hasMainTabs", false);
        additionNavigationBarHeight = hasMainTabs ? dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS) : 0;
        RelayThreads.addListener(this);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        RelayThreads.removeListener(this);
        AndroidUtilities.cancelRunOnUIThread(reloadRunnable);
        super.onFragmentDestroy();
    }

    @Override
    protected boolean useSimpleActionBar() {
        return true;
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Override
    public boolean drawEdgeNavigationBar() {
        return !hasMainTabs; // the tab bar's fade is under the tabs
    }

    @Override
    protected boolean useFadeDrawableForActionBar() {
        return false; // listTopOverlay fades under the header and the team tabs
    }

    @Nullable
    @Override
    protected RecyclerListView getListViewForSimpleGlass() {
        return listView;
    }

    @Override
    public View createView(Context context) {
        setHasOwnBackground(true);
        simpleActionBar.setBackButtonDrawable(hasMainTabs ? null : new BackDrawable(false));
        simpleActionBar.setTitle(LocaleController.getString(R.string.RelayThreads));
        simpleActionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1 && !hasMainTabs) {
                    finishFragment();
                }
            }
        });

        contentView = new SizeNotifierFrameLayout(context);
        ThemeBind.backgroundColor(contentView, Theme.key_windowBackgroundGray, resourceProvider);

        listView = new RecyclerListView(context);
        // The rows in one section on the page background, which the list draws (so the glass over it sees it too),
        // like Telegram's simple screens (Settings, Mentions).
        listView.setSections();
        listView.setSectionsDrawBackground(true);
        listView.setLayoutManager(layoutManager = new LinearLayoutManager(context, RecyclerView.VERTICAL, false));
        listView.setAdapter(adapter = new Adapter(context));
        listView.setClipToPadding(false);
        listView.setOnItemClickListener((view, position) -> {
            if (position >= 0 && position < rows.size()) {
                open(rows.get(position));
            }
        });
        listView.setOnItemLongClickListener((view, position) -> {
            if (position >= 0 && position < rows.size()) {
                showOptions(rows.get(position));
                return true;
            }
            return false;
        });
        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        emptyView = new TextView(context);
        ThemeBind.textColor(emptyView, Theme.key_windowBackgroundWhiteGrayText, resourceProvider);
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setVisibility(View.GONE);
        contentView.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        listTopOverlay = new View(context);
        contentView.addView(listTopOverlay, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 0, Gravity.TOP));
        listTopOverlay.setBackground(getBaseSimpleGlass().createActionBarFadeDrawable(listTopOverlay));
        simpleActionBar.setBackground(null);

        // The team tabs: a glass pill 7dp under the header's buttons (Telegram's Network Usage tabs).
        tabsView = new ViewPagerFixed.TabsView(context, true, ViewPagerFixed.SELECTOR_TYPE_BUBBLE_STYLE, resourceProvider);
        tabsView.setDelegate(new ViewPagerFixed.TabsView.TabsViewDelegate() {
            @Override
            public void onPageSelected(int page, boolean forward) {
                if (page >= 0 && page < teams.size()) {
                    selectTeam(teams.get(page).id);
                }
            }

            @Override
            public void onSamePageSelected() {
                onParentScrollToTop();
            }
        });
        tabsContainer = new FrameLayout(context);
        tabsContainer.addView(tabsView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 36 + 6 + 6));
        tabsContainer.setPadding(dp(4), dp(4), dp(4), dp(4));
        tabsContainer.setBackground(getBaseSimpleGlass().factory
            .create(tabsContainer)
            .setColorProvider(BlurredBackgroundProviderImpl.simpleActionBar(resourceProvider))
            .setPadding(dp(9.66f))
            .setRadius(dp(18)));
        tabsContainer.setVisibility(View.GONE);
        contentView.addView(tabsContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, -4, 0, -4, 0));

        fragmentView = contentView;
        if (hasMainTabs) {
            // a page of the main tabs: the pager hands its insets down (pushed fragments get them from the layout)
            ViewCompat.setOnApplyWindowInsetsListener(fragmentView, this::onInsetsInternal);
        }
        updateLayoutParams();
        updateEmpty();
        loadTeams();
        return fragmentView;
    }

    @Override
    public void onInsets(int left, int top, int right, int bottom) {
        updateLayoutParams();
    }

    private boolean tabsVisible() {
        return teams.size() > 1;
    }

    private void updateLayoutParams() {
        if (listView == null) {
            return;
        }
        final int insetTop = mSystemInsets.top;
        final int insetBottom = mSystemInsets.bottom;
        final int tabsHeight = tabsVisible() ? dp(42) : 0;
        BaseFragmentSimpleGlass.setListViewParams(listView, insetTop, insetBottom, ActionBar.getCurrentActionBarHeight() + tabsHeight, additionNavigationBarHeight);
        tabsContainer.setTranslationY(BaseFragmentSimpleGlass.getPillGlassTop(insetTop) - dp(9.66f));
        ViewGroup.LayoutParams lp = listTopOverlay.getLayoutParams();
        lp.height = insetTop + ActionBar.getCurrentActionBarHeight() + simpleActionBar.getExtraHeight() + (tabsVisible() ? dp(44) : 0);
        listTopOverlay.setLayoutParams(lp);
        emptyView.setPadding(dp(32), insetTop + ActionBar.getCurrentActionBarHeight() + tabsHeight, dp(32), insetBottom + additionNavigationBarHeight);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (hasMainTabs) {
            // The tab: the account may have joined or left teams, and the list follows the chat list's team folder.
            loadTeams();
        } else if (teamId != null && !loading) {
            reload();
        }
        updateEmpty();
    }

    // MARK: - Data

    private void loadTeams() {
        RelayThreads.teams(currentAccount, (result, error) -> {
            if (result == null || fragmentView == null) {
                return;
            }
            boolean changed = result.size() != teams.size();
            for (int i = 0; !changed && i < result.size(); i++) {
                changed = !result.get(i).id.equals(teams.get(i).id) || !result.get(i).title.equals(teams.get(i).title);
            }
            teams.clear();
            teams.addAll(result);
            String want = teamId;
            if (hasMainTabs && folderProvider != null) {
                String folderTeam = RelayThreads.teamIdOfFolder(currentAccount, folderProvider.currentFilterId());
                if (folderTeam != null) {
                    want = folderTeam;
                }
            }
            if (indexOfTeam(want) < 0) {
                want = teamId != null && indexOfTeam(teamId) >= 0 ? teamId : (teams.isEmpty() ? null : teams.get(0).id);
            }
            if (changed) {
                buildTabs();
            }
            if (want != null && !want.equals(teamId)) {
                selectTeam(want);
            } else {
                syncTabs(false);
                reload();
            }
        });
    }

    private int indexOfTeam(String id) {
        if (id == null) {
            return -1;
        }
        for (int i = 0; i < teams.size(); i++) {
            if (teams.get(i).id.equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private void buildTabs() {
        tabsView.removeTabs();
        for (int i = 0; i < teams.size(); i++) {
            tabsView.addTab(i, teams.get(i).title);
        }
        tabsView.finishAddingTabs();
        tabsContainer.setVisibility(tabsVisible() ? View.VISIBLE : View.GONE);
        updateLayoutParams();
    }

    private void syncTabs(boolean animated) {
        int index = indexOfTeam(teamId);
        if (index >= 0 && tabsView.getCurrentTabId() != index) {
            if (animated) {
                tabsView.scrollToTab(index, index);
            } else {
                tabsView.selectTabWithId(index, 1f);
            }
        }
    }

    private void selectTeam(String id) {
        if (id == null || id.equals(teamId)) {
            return;
        }
        teamId = id;
        rows.clear();
        loaded = false;
        adapter.notifyDataSetChanged();
        syncTabs(false);
        updateEmpty();
        RelayConfig.log(TAG, "threads list team " + id);
        reload();
    }

    private void reload() {
        if (teamId == null || !RelayThreadsHooks.collapsedThreads(currentAccount)) {
            updateEmpty();
            return;
        }
        if (loading) {
            reloadQueued = true;
            return;
        }
        loading = true;
        final String team = teamId;
        RelayThreads.loadThreads(currentAccount, team, (result, error) -> {
            loading = false;
            if (fragmentView == null) {
                return;
            }
            if (result != null && team.equals(teamId)) {
                rows.clear();
                rows.addAll(result);
                loaded = true;
                adapter.notifyDataSetChanged();
                RelayConfig.log(TAG, "threads list " + team + ": " + rows.size() + " threads");
            }
            updateEmpty();
            if (reloadQueued || !team.equals(teamId)) {
                reloadQueued = false;
                reload();
            }
        });
    }

    private void updateEmpty() {
        if (emptyView == null) {
            return;
        }
        int text;
        if (!RelayThreadsHooks.collapsedThreads(currentAccount)) {
            text = R.string.RelayThreadsOff;
            if (!rows.isEmpty()) {
                rows.clear();
                adapter.notifyDataSetChanged();
            }
        } else if (!rows.isEmpty()) {
            text = 0;
        } else if (!loaded) {
            text = R.string.RelayThreadsLoading;
        } else {
            text = R.string.RelayThreadsEmpty;
        }
        if (text != 0) {
            emptyView.setText(LocaleController.getString(text));
        }
        emptyView.setVisibility(text != 0 ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onThreadsChanged(int account) {
        if (account == currentAccount && fragmentView != null && !isFinishing()) {
            AndroidUtilities.cancelRunOnUIThread(reloadRunnable);
            AndroidUtilities.runOnUIThread(reloadRunnable, 400);
        }
    }

    private final Runnable reloadRunnable = this::reload;

    private void open(RelayThreads.ThreadRow row) {
        RelayThreadsUi.openThread(this, currentAccount, row.dialogId, row.rootMessageId, 0);
    }

    private void showOptions(RelayThreads.ThreadRow row) {
        if (getParentActivity() == null) {
            return;
        }
        CharSequence[] items = {
                LocaleController.getString(R.string.RelayThreadOpen),
                LocaleController.getString(R.string.RelayThreadMarkRead),
                LocaleController.getString(R.string.RelayUnfollowThread),
        };
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setItems(items, (dialog, which) -> {
            if (which == 0) {
                open(row);
            } else if (which == 1) {
                RelayThreads.markRead(currentAccount, row.rootPostId, (r, e) -> reload());
            } else {
                RelayThreads.setFollowing(currentAccount, row.dialogId, row.rootMessageId, false, (r, e) -> reload());
            }
        });
        showDialog(builder.create());
    }

    /** Debug/e2e (RelayDebugTabs): the selected team, the teams, the rows, the empty text, the first visible row. */
    public void debugState(StringBuilder out) {
        int index = indexOfTeam(teamId);
        out.append("threads_team\t").append(teamId != null ? teamId : "-").append('\t').append(index >= 0 ? teams.get(index).title : "-").append('\n');
        out.append("threads_teams\t");
        for (int i = 0; i < teams.size(); i++) {
            out.append(i > 0 ? "," : "").append(teams.get(i).id).append(':').append(teams.get(i).title);
        }
        out.append("\nthreads_rows\t");
        for (int i = 0; i < rows.size(); i++) {
            RelayThreads.ThreadRow row = rows.get(i);
            out.append(i > 0 ? "," : "").append(row.rootPostId).append(':').append(row.unreadReplies).append(':').append(row.unreadMentions);
        }
        out.append("\nempty\t").append(emptyView != null && emptyView.getVisibility() == View.VISIBLE ? emptyView.getText() : "-").append('\n');
        out.append("first\t").append(layoutManager != null ? layoutManager.findFirstCompletelyVisibleItemPosition() : -1).append('\n');
    }

    // MARK: - Main tab

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
        return getBaseSimpleGlass().getGlassSource();
    }

    @Override
    public GlassSourceRenderNode getFrostedGlassSource() {
        return getBaseSimpleGlass().getFrostedGlassSource();
    }

    @Override
    public void onThemeColorsChanged() {
        super.onThemeColorsChanged();
        if (listView != null) {
            // rows take their colors when bound
            listView.getRecycledViewPool().clear();
            adapter.notifyDataSetChanged();
            tabsView.updateColors();
        }
    }

    // MARK: - Views

    private static class ThreadCell extends FrameLayout {
        final BackupImageView avatar;
        final AvatarDrawable avatarDrawable = new AvatarDrawable();
        final TextView name;
        final TextView time;
        final TextView chat;
        final TextView text;
        final TextView footer;
        final View dot;
        boolean divider;
        final android.graphics.drawable.GradientDrawable circle = new android.graphics.drawable.GradientDrawable();

        ThreadCell(Context context) {
            super(context);
            setPadding(0, dp(10), 0, dp(10));
            dot = new View(context);
            circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            dot.setBackground(circle);
            addView(dot, LayoutHelper.createFrame(8, 8, Gravity.LEFT | Gravity.TOP, 6, 22, 0, 0));
            avatar = new BackupImageView(context);
            avatar.setRoundRadius(dp(23));
            addView(avatar, LayoutHelper.createFrame(46, 46, Gravity.LEFT | Gravity.TOP, 18, 0, 0, 0));

            LinearLayout column = new LinearLayout(context);
            column.setOrientation(LinearLayout.VERTICAL);
            addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 76, 0, 16, 0));

            FrameLayout top = new FrameLayout(context);
            column.addView(top, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            name = text(context, 16, true);
            top.addView(name, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT, 0, 0, 72, 0));
            time = text(context, 13, false);
            top.addView(time, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT | Gravity.CENTER_VERTICAL));

            chat = text(context, 14, false);
            column.addView(chat, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));
            text = text(context, 15, false);
            text.setSingleLine(false);
            text.setMaxLines(2);
            column.addView(text, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
            footer = text(context, 14, false);
            column.addView(footer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
            setWillNotDraw(false);
        }

        private static TextView text(Context context, int size, boolean bold) {
            TextView view = new TextView(context);
            view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size);
            view.setSingleLine(true);
            view.setEllipsize(TextUtils.TruncateAt.END);
            if (bold) {
                view.setTypeface(AndroidUtilities.bold());
            }
            return view;
        }

        void bind(int account, RelayThreads.ThreadRow row, boolean divider) {
            this.divider = divider;
            invalidate();
            TLRPC.User user = MessagesController.getInstance(account).getUser(row.authorId);
            if (user != null) {
                avatarDrawable.setInfo(account, user);
                avatar.setForUserOrChat(user, avatarDrawable);
            } else {
                avatarDrawable.setInfo(row.authorId, row.authorName, "");
                avatar.setImageDrawable(avatarDrawable);
            }
            name.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            chat.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            text.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            footer.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            circle.setColor(Theme.getColor(Theme.key_chats_unreadCounter));
            name.setText(row.authorName);
            chat.setText(row.channelTitle);
            text.setText(row.text);
            boolean unread = row.unreadReplies > 0 || row.unreadMentions > 0;
            dot.setVisibility(unread ? View.VISIBLE : View.INVISIBLE);
            time.setText(row.lastReplyAt > 0 ? LocaleController.stringForMessageListDate(row.lastReplyAt / 1000) : "");
            time.setTextColor(Theme.getColor(unread ? Theme.key_windowBackgroundWhiteBlueText : Theme.key_windowBackgroundWhiteGrayText3));
            SpannableStringBuilder f = new SpannableStringBuilder(LocaleController.formatPluralString("RelayThreadReplies", (int) row.replyCount));
            int blue = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText);
            if (row.unreadReplies > 0) {
                f.append(" · ");
                int start = f.length();
                f.append(LocaleController.formatPluralString("RelayThreadNewReplies", (int) row.unreadReplies));
                f.setSpan(new ForegroundColorSpan(blue), start, f.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (row.unreadMentions > 0) {
                f.append(" · ");
                int start = f.length();
                f.append(LocaleController.formatPluralString("RelayThreadMentions", (int) row.unreadMentions));
                f.setSpan(new ForegroundColorSpan(blue), start, f.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            footer.setText(f);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (divider) {
                canvas.drawLine(dp(76), getHeight() - 1, getWidth(), getHeight() - 1, Theme.dividerPaint);
            }
        }
    }

    private class Adapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

        Adapter(Context context) {
            this.context = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            ThreadCell cell = new ThreadCell(context);
            cell.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(cell);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            ((ThreadCell) holder.itemView).bind(currentAccount, rows.get(position), position < rows.size() - 1);
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }
}
