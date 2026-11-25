package org.telegram.relay.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.relay.account.RelaySession;
import org.telegram.relay.account.RelaySessionStore;
import org.telegram.relay.bridge.RelayFinding;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.RadioCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * New Channel (Mattermost): team, name, URL, public or private, purpose and header, as Telegram's channel
 * settings screens lay such forms out. Creating opens the new channel. Opened from New Message and Browse
 * Channels.
 */
public class RelayNewChannelActivity extends BaseFragment {

    private static final int ID_DONE = 1;

    private EditTextBoldCursor nameField;
    private EditTextBoldCursor urlField;
    private EditTextBoldCursor purposeField;
    private EditTextBoldCursor headerField;
    private TextInfoPrivacyCell urlInfo;
    private TextInfoPrivacyCell typeInfo;
    private RadioCell publicCell;
    private RadioCell privateCell;
    private LinearLayout teamContainer;
    private final List<RadioCell> teamCells = new ArrayList<>();
    private final List<String> teamIds = new ArrayList<>();
    private String teamId;
    private boolean isPrivate;
    private boolean urlEdited;
    private boolean ignoreUrlChange;
    private boolean creating;
    private ActionBarMenuItem doneItem;

    @Override
    public boolean onFragmentCreate() {
        RelayFinding.loadTeams(currentAccount, (teams, error) -> setTeams(teams));
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.RelayNewChannel));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == ID_DONE) {
                    create();
                }
            }
        });
        doneItem = actionBar.createMenu().addItemWithWidth(ID_DONE, R.drawable.ic_ab_done, dp(56));
        doneItem.setContentDescription(getString(R.string.Create));

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(column, new ScrollView.LayoutParams(ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        fragmentView = scroll;
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        teamContainer = new LinearLayout(context);
        teamContainer.setOrientation(LinearLayout.VERTICAL);
        column.addView(teamContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        nameField = field(context, column, getString(R.string.RelayChannelName), getString(R.string.RelayChannelNameHint), false);
        nameField.addTextChangedListener(new SimpleWatcher(() -> {
            if (!urlEdited) {
                ignoreUrlChange = true;
                urlField.setText(RelayFinding.slug(nameField.getText().toString()));
                ignoreUrlChange = false;
            }
            updateUrlInfo();
        }));
        urlField = field(context, column, getString(R.string.RelayChannelUrl), "release-train", false);
        urlField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_URI);
        urlField.addTextChangedListener(new SimpleWatcher(() -> {
            if (!ignoreUrlChange) {
                urlEdited = true;
            }
            updateUrlInfo();
        }));
        urlInfo = new TextInfoPrivacyCell(context);
        column.addView(urlInfo, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        HeaderCell typeHeader = new HeaderCell(context);
        typeHeader.setText(getString(R.string.RelayChannelType));
        typeHeader.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        column.addView(typeHeader, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        publicCell = new RadioCell(context);
        publicCell.setBackground(Theme.getSelectorDrawable(true));
        publicCell.setText(getString(R.string.RelayChannelPublic), true, true);
        publicCell.setOnClickListener(v -> setPrivate(false));
        column.addView(publicCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        privateCell = new RadioCell(context);
        privateCell.setBackground(Theme.getSelectorDrawable(true));
        privateCell.setText(getString(R.string.RelayChannelPrivate), false, false);
        privateCell.setOnClickListener(v -> setPrivate(true));
        column.addView(privateCell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        typeInfo = new TextInfoPrivacyCell(context);
        typeInfo.setText(getString(R.string.RelayChannelPublicInfo));
        column.addView(typeInfo, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        purposeField = field(context, column, getString(R.string.RelayChannelPurpose), getString(R.string.RelayChannelPurposeHint), true);
        TextInfoPrivacyCell purposeInfo = new TextInfoPrivacyCell(context);
        purposeInfo.setText(getString(R.string.RelayChannelPurposeInfo));
        column.addView(purposeInfo, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        headerField = field(context, column, getString(R.string.RelayChannelHeader), getString(R.string.RelayChannelHeaderHint), true);
        TextInfoPrivacyCell headerInfo = new TextInfoPrivacyCell(context);
        headerInfo.setText(getString(R.string.RelayChannelHeaderInfo));
        column.addView(headerInfo, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        updateUrlInfo();
        if (pendingTeams != null) {
            setTeams(pendingTeams);
        }
        return fragmentView;
    }

    private LinkedHashMap<String, String> pendingTeams;

    private void setTeams(LinkedHashMap<String, String> teams) {
        if (teams == null) {
            return;
        }
        pendingTeams = teams;
        if (teamContainer == null) {
            return;
        }
        teamContainer.removeAllViews();
        teamCells.clear();
        teamIds.clear();
        if (teams.size() > 1) {
            HeaderCell header = new HeaderCell(getContext());
            header.setText(getString(R.string.RelayTeam));
            header.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            teamContainer.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
        int i = 0;
        for (Map.Entry<String, String> team : teams.entrySet()) {
            final String id = team.getKey();
            teamIds.add(id);
            if (teams.size() > 1) {
                RadioCell cell = new RadioCell(getContext());
                cell.setBackground(Theme.getSelectorDrawable(true));
                cell.setText(team.getValue(), i == 0, i < teams.size() - 1);
                cell.setOnClickListener(v -> selectTeam(id));
                teamContainer.addView(cell, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
                teamCells.add(cell);
            }
            i++;
        }
        if (teams.size() > 1) {
            teamContainer.addView(new TextInfoPrivacyCell(getContext()), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
        teamId = teamIds.isEmpty() ? null : teamIds.get(0);
        updateUrlInfo();
    }

    private void selectTeam(String id) {
        teamId = id;
        for (int i = 0; i < teamCells.size(); i++) {
            teamCells.get(i).setChecked(teamIds.get(i).equals(id), true);
        }
        updateUrlInfo();
    }

    private void setPrivate(boolean value) {
        isPrivate = value;
        publicCell.setChecked(!value, true);
        privateCell.setChecked(value, true);
        typeInfo.setText(getString(value ? R.string.RelayChannelPrivateInfo : R.string.RelayChannelPublicInfo));
    }

    private void updateUrlInfo() {
        if (urlInfo == null || urlField == null) {
            return;
        }
        String url = urlField.getText().toString();
        if (!url.isEmpty() && !RelayFinding.isValidChannelName(url)) {
            urlInfo.setText(getString(R.string.RelayChannelUrlInvalid));
            urlInfo.getTextView().setTextColor(Theme.getColor(Theme.key_text_RedRegular));
            return;
        }
        urlInfo.getTextView().setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText4));
        RelaySession session = RelaySessionStore.get(currentAccount);
        String host = session != null ? session.serverUrl.replaceFirst("^https?://", "") : "";
        String teamName = teamId != null && pendingTeamNames().containsKey(teamId) ? pendingTeamNames().get(teamId) : "";
        urlInfo.setText(host + "/" + teamName.toLowerCase().replace(' ', '-') + "/channels/" + (url.isEmpty() ? "…" : url));
    }

    private Map<String, String> pendingTeamNames() {
        return pendingTeams != null ? pendingTeams : new LinkedHashMap<>();
    }

    private EditTextBoldCursor field(Context context, LinearLayout column, String title, String hint, boolean multiline) {
        HeaderCell header = new HeaderCell(context);
        header.setText(title);
        header.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        column.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        FrameLayout box = new FrameLayout(context);
        box.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        EditTextBoldCursor edit = new EditTextBoldCursor(context);
        edit.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
        edit.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        edit.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        edit.setBackground(null);
        edit.setPadding(0, 0, 0, dp(8));
        edit.setHint(hint);
        edit.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        edit.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        edit.setCursorSize(dp(20));
        edit.setCursorWidth(1.5f);
        if (multiline) {
            edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            edit.setMaxLines(4);
        } else {
            edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            edit.setSingleLine(true);
        }
        box.addView(edit, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 21, 4, 21, 8));
        column.addView(box, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        return edit;
    }

    private void create() {
        if (creating) {
            return;
        }
        String name = nameField.getText().toString().trim();
        String url = urlField.getText().toString().trim();
        if (TextUtils.isEmpty(name)) {
            AndroidUtilities.shakeViewSpring(nameField);
            showError(getString(R.string.RelayChannelNameRequired));
            return;
        }
        if (!url.isEmpty() && !RelayFinding.isValidChannelName(url)) {
            AndroidUtilities.shakeViewSpring(urlField);
            return;
        }
        if (teamId == null) {
            return;
        }
        creating = true;
        AlertDialog progress = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        progress.setCanCancel(false);
        showDialog(progress);
        RelayFinding.createChannel(currentAccount, teamId, name, url, isPrivate, purposeField.getText().toString().trim(),
                headerField.getText().toString().trim(), (chat, error) -> {
                    creating = false;
                    progress.dismiss();
                    if (chat == null) {
                        showError(error != null ? error : getString(R.string.RelayChannelCreateFailed));
                        return;
                    }
                    MessagesController.getInstance(currentAccount).putChat(chat, false);
                    getNotificationCenter().postNotificationName(NotificationCenter.closeChats);
                    Bundle args = new Bundle();
                    args.putLong("chat_id", chat.id);
                    presentFragment(new ChatActivity(args), true);
                });
    }

    private void showError(String text) {
        if (getParentActivity() == null) {
            return;
        }
        showDialog(new AlertDialog.Builder(getParentActivity())
                .setTitle(getString(R.string.RelayChannelCreateFailed))
                .setMessage(text)
                .setPositiveButton(getString(R.string.OK), null)
                .create());
    }

    private static final class SimpleWatcher implements TextWatcher {
        private final Runnable onChange;

        SimpleWatcher(Runnable onChange) {
            this.onChange = onChange;
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }

        @Override
        public void afterTextChanged(Editable s) {
            onChange.run();
        }
    }
}
