package org.telegram.relay.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.relay.RelayConfig;
import org.telegram.relay.bridge.RelayThreads;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.List;

/**
 * A7a: the Threads list (iOS {@code MattermostThreadsController}, Mattermost's "Followed threads"), opened
 * from the chat list's threads button. One tab per team ({@code GET /users/me/teams/{team}/threads}); a
 * row shows the root's author, chat, text, reply count, new replies and mentions; tapping opens the
 * thread, a long press offers Mark as Read and Unfollow. Updates live through {@link RelayThreads.Listener}.
 */
public class RelayThreadsActivity extends BaseFragment implements RelayThreads.Listener {
    private static final String TAG = "RelayThreads";

    private final List<RelayThreads.TeamInfo> teams = new ArrayList<>();
    private final List<RelayThreads.ThreadRow> rows = new ArrayList<>();
    private String teamId;
    private LinearLayout tabs;
    private RecyclerListView listView;
    private Adapter adapter;
    private TextView emptyView;
    private boolean loading;
    private boolean reloadQueued;

    public RelayThreadsActivity(Bundle args) {
        super(args);
    }

    @Override
    public boolean onFragmentCreate() {
        teamId = arguments != null ? arguments.getString("team_id") : null;
        RelayThreads.addListener(this);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        RelayThreads.removeListener(this);
        super.onFragmentDestroy();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.RelayThreads));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        tabs = new LinearLayout(context);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        column.addView(tabs, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44));

        FrameLayout content = new FrameLayout(context);
        column.addView(content, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, RecyclerView.VERTICAL, false));
        listView.setAdapter(adapter = new Adapter(context));
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
        content.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        emptyView = new TextView(context);
        emptyView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(AndroidUtilities.dp(32), 0, AndroidUtilities.dp(32), 0);
        emptyView.setText(LocaleController.getString(R.string.RelayThreadsEmpty));
        emptyView.setVisibility(View.GONE);
        content.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        fragmentView = root;
        loadTeams();
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (teamId != null && !loading) {
            reload();
        }
    }

    // MARK: - Data

    private void loadTeams() {
        RelayThreads.teams(currentAccount, (result, error) -> {
            if (result == null || fragmentView == null) {
                return;
            }
            teams.clear();
            teams.addAll(result);
            boolean known = false;
            for (RelayThreads.TeamInfo team : teams) {
                known |= team.id.equals(teamId);
            }
            if (!known) {
                teamId = teams.isEmpty() ? null : teams.get(0).id;
            }
            buildTabs();
            reload();
        });
    }

    private void buildTabs() {
        tabs.removeAllViews();
        tabs.setVisibility(teams.size() > 1 ? View.VISIBLE : View.GONE);
        for (RelayThreads.TeamInfo team : teams) {
            TabView tab = new TabView(getContext(), team);
            tab.setOnClickListener(v -> {
                if (!team.id.equals(teamId)) {
                    teamId = team.id;
                    rows.clear();
                    adapter.notifyDataSetChanged();
                    updateTabs();
                    reload();
                }
            });
            tabs.addView(tab, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f));
        }
        updateTabs();
    }

    private void updateTabs() {
        for (int i = 0; i < tabs.getChildCount(); i++) {
            View child = tabs.getChildAt(i);
            if (child instanceof TabView) {
                ((TabView) child).setSelectedTab(((TabView) child).team.id.equals(teamId));
            }
        }
    }

    private void reload() {
        if (teamId == null) {
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
                adapter.notifyDataSetChanged();
                emptyView.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
                RelayConfig.log(TAG, "threads list " + team + ": " + rows.size() + " threads");
            }
            if (reloadQueued || !team.equals(teamId)) {
                reloadQueued = false;
                reload();
            }
        });
    }

    @Override
    public void onThreadsChanged(int account) {
        if (account == currentAccount && fragmentView != null && isFinishing() == false) {
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

    // MARK: - Views

    private class TabView extends TextView {
        final RelayThreads.TeamInfo team;
        private boolean selected;
        private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        TabView(Context context, RelayThreads.TeamInfo team) {
            super(context);
            this.team = team;
            setText(team.title);
            setGravity(Gravity.CENTER);
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            setTypeface(AndroidUtilities.bold());
            setSingleLine(true);
            setEllipsize(TextUtils.TruncateAt.END);
            setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ALL));
        }

        void setSelectedTab(boolean value) {
            selected = value;
            setTextColor(Theme.getColor(value ? Theme.key_windowBackgroundWhiteBlueHeader : Theme.key_windowBackgroundWhiteGrayText));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (selected) {
                linePaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader));
                float w = Math.min(getWidth() - AndroidUtilities.dp(24), getPaint().measureText(getText().toString()) + AndroidUtilities.dp(8));
                float left = (getWidth() - w) / 2f;
                canvas.drawRoundRect(left, getHeight() - AndroidUtilities.dp(3), left + w, getHeight() + AndroidUtilities.dp(3), AndroidUtilities.dp(3), AndroidUtilities.dp(3), linePaint);
            }
        }
    }

    private static class ThreadCell extends FrameLayout {
        final BackupImageView avatar;
        final AvatarDrawable avatarDrawable = new AvatarDrawable();
        final TextView name;
        final TextView time;
        final TextView chat;
        final TextView text;
        final TextView footer;
        final View dot;

        ThreadCell(Context context) {
            super(context);
            setPadding(0, AndroidUtilities.dp(10), 0, AndroidUtilities.dp(10));
            dot = new View(context);
            android.graphics.drawable.GradientDrawable circle = new android.graphics.drawable.GradientDrawable();
            circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            circle.setColor(Theme.getColor(Theme.key_chats_unreadCounter));
            dot.setBackground(circle);
            addView(dot, LayoutHelper.createFrame(8, 8, Gravity.LEFT | Gravity.TOP, 6, 22, 0, 0));
            avatar = new BackupImageView(context);
            avatar.setRoundRadius(AndroidUtilities.dp(23));
            addView(avatar, LayoutHelper.createFrame(46, 46, Gravity.LEFT | Gravity.TOP, 18, 0, 0, 0));

            LinearLayout column = new LinearLayout(context);
            column.setOrientation(LinearLayout.VERTICAL);
            addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 76, 0, 16, 0));

            FrameLayout top = new FrameLayout(context);
            column.addView(top, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            name = text(context, 16, Theme.key_windowBackgroundWhiteBlackText, true);
            top.addView(name, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT, 0, 0, 72, 0));
            time = text(context, 13, Theme.key_windowBackgroundWhiteGrayText3, false);
            top.addView(time, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT | Gravity.CENTER_VERTICAL));

            chat = text(context, 14, Theme.key_windowBackgroundWhiteGrayText, false);
            column.addView(chat, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 1, 0, 0));
            text = text(context, 15, Theme.key_windowBackgroundWhiteBlackText, false);
            text.setSingleLine(false);
            text.setMaxLines(2);
            column.addView(text, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
            footer = text(context, 14, Theme.key_windowBackgroundWhiteGrayText, false);
            column.addView(footer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));
            setWillNotDraw(false);
        }

        private static TextView text(Context context, int size, int color, boolean bold) {
            TextView view = new TextView(context);
            view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size);
            view.setTextColor(Theme.getColor(color));
            view.setSingleLine(true);
            view.setEllipsize(TextUtils.TruncateAt.END);
            if (bold) {
                view.setTypeface(AndroidUtilities.bold());
            }
            return view;
        }

        void bind(int account, RelayThreads.ThreadRow row) {
            TLRPC.User user = MessagesController.getInstance(account).getUser(row.authorId);
            if (user != null) {
                avatarDrawable.setInfo(account, user);
                avatar.setForUserOrChat(user, avatarDrawable);
            } else {
                avatarDrawable.setInfo(row.authorId, row.authorName, "");
                avatar.setImageDrawable(avatarDrawable);
            }
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
            canvas.drawLine(AndroidUtilities.dp(76), getHeight() - 1, getWidth(), getHeight() - 1, Theme.dividerPaint);
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
            ((ThreadCell) holder.itemView).bind(currentAccount, rows.get(position));
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }
}
