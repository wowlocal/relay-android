package org.telegram.relay.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.relay.bridge.RelayFinding;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.FlickerLoadingView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Browse Channels (Mattermost's channel directory): the public channels of each team, joined or not, with
 * member count and purpose; "Archived Channels" switches to the archived ones. A channel the user is not in
 * opens as a preview with Telegram's Join button ({@code channels.joinChannel} → join in Mattermost).
 * Opened from New Message.
 */
public class RelayBrowseChannelsActivity extends UniversalFragment {

    private static final int ID_SEARCH = 1;
    private static final int ID_ARCHIVED = 2;
    private static final int ID_NEW = 3;
    private static final int ID_ROW = 1000;

    private LinkedHashMap<String, String> teams;
    private final Map<String, List<RelayFinding.ChannelRow>> rows = new LinkedHashMap<>();
    private boolean archived;
    private String query = "";
    private int generation;
    private boolean loading = true;
    private final ArrayList<RelayFinding.ChannelRow> flat = new ArrayList<>();

    @Override
    public boolean onFragmentCreate() {
        RelayFinding.loadTeams(currentAccount, (result, error) -> {
            teams = result != null ? result : new LinkedHashMap<>();
            reload();
        });
        return super.onFragmentCreate();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (teams != null && !loading) {
            reload();
        }
    }

    private void reload() {
        final int current = ++generation;
        loading = true;
        if (teams == null || teams.isEmpty()) {
            loading = false;
            update();
            return;
        }
        final Map<String, List<RelayFinding.ChannelRow>> next = new LinkedHashMap<>();
        final int[] pending = {teams.size()};
        for (String teamId : teams.keySet()) {
            RelayFinding.browseChannels(currentAccount, teamId, archived, query, (result, error) -> {
                if (current != generation) {
                    return;
                }
                next.put(teamId, result != null ? result : new ArrayList<>());
                if (--pending[0] == 0) {
                    rows.clear();
                    for (String id : teams.keySet()) {
                        List<RelayFinding.ChannelRow> list = next.get(id);
                        if (list != null) {
                            rows.put(id, list);
                        }
                    }
                    loading = false;
                    update();
                }
            });
        }
        update();
    }

    private void update() {
        if (listView != null) {
            listView.adapter.update(true);
        }
    }

    @Override
    public View createView(Context context) {
        View view = super.createView(context);
        ActionBarMenu menu = actionBar.createMenu();
        ActionBarMenuItem search = menu.addItem(ID_SEARCH, R.drawable.outline_header_search).setIsSearchField(true).setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
            @Override
            public void onSearchCollapse() {
                setQuery("");
            }

            @Override
            public void onTextChanged(EditText editText) {
                setQuery(editText.getText().toString());
            }
        });
        search.setSearchFieldHint(getString(R.string.RelayBrowseSearch));
        search.setContentDescription(getString(R.string.RelayBrowseSearch));
        menu.addItem(ID_NEW, R.drawable.msg_add).setContentDescription(getString(R.string.RelayNewChannel));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == ID_NEW) {
                    presentFragment(new RelayNewChannelActivity());
                }
            }
        });
        return view;
    }

    private Runnable searchRunnable;

    private void setQuery(String text) {
        String q = text == null ? "" : text.trim();
        if (TextUtils.equals(q, query)) {
            return;
        }
        query = q;
        if (searchRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(searchRunnable);
        }
        AndroidUtilities.runOnUIThread(searchRunnable = this::reload, 300);
    }

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.RelayBrowseChannels);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asCheck(ID_ARCHIVED, getString(R.string.RelayArchivedChannels)).setChecked(archived));
        items.add(UItem.asShadow(null));
        flat.clear();
        if (loading && rows.isEmpty()) {
            items.add(UItem.asFlicker(FlickerLoadingView.USERS_TYPE));
            items.add(UItem.asFlicker(FlickerLoadingView.USERS_TYPE));
            return;
        }
        boolean any = false;
        for (Map.Entry<String, List<RelayFinding.ChannelRow>> entry : rows.entrySet()) {
            List<RelayFinding.ChannelRow> list = entry.getValue();
            if (list.isEmpty()) {
                continue;
            }
            any = true;
            items.add(UItem.asHeader(teams.get(entry.getKey())));
            for (RelayFinding.ChannelRow row : list) {
                UItem item = UItem.asProfileCell(row.chat);
                item.id = ID_ROW + flat.size();
                item.subtext = subtitle(row);
                items.add(item);
                flat.add(row);
            }
            items.add(UItem.asShadow(null));
        }
        if (!any) {
            items.add(UItem.asShadow(getString(R.string.RelayBrowseEmpty)));
        }
    }

    private static CharSequence subtitle(RelayFinding.ChannelRow row) {
        StringBuilder s = new StringBuilder();
        if (row.joined) {
            s.append(getString(R.string.RelayJoined));
        }
        if (row.memberCount >= 0) {
            if (s.length() > 0) s.append(" · ");
            s.append(LocaleController.formatPluralString("Members", row.memberCount));
        }
        if (row.isPrivate) {
            if (s.length() > 0) s.append(" · ");
            s.append(getString(R.string.RelayChannelPrivateShort));
        }
        if (!TextUtils.isEmpty(row.purpose)) {
            if (s.length() > 0) s.append(" · ");
            s.append(row.purpose.replace('\n', ' '));
        }
        return s;
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_ARCHIVED) {
            archived = !archived;
            rows.clear();
            reload();
            return;
        }
        int index = item.id - ID_ROW;
        if (index < 0 || index >= flat.size()) {
            return;
        }
        RelayFinding.ChannelRow row = flat.get(index);
        MessagesController.getInstance(currentAccount).putChat(row.chat, false);
        Bundle args = new Bundle();
        args.putLong("chat_id", row.chat.id);
        presentFragment(new ChatActivity(args));
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }
}
