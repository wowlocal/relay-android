package org.telegram.relay.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.GridLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.R;
import org.telegram.relay.bridge.RelayProfile;
import org.telegram.relay.mattermost.model.CustomStatus;
import org.telegram.relay.mattermost.text.EmojiTable;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.EditTextCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;
import java.util.Calendar;

/**
 * Settings → Status → Custom Status (A7c, as iOS phase3f-custom-status): an emoji, a text and when to clear it.
 * Saved with {@code PUT /users/me/status/custom}; everyone sees it as the Telegram emoji status next to the name.
 */
public class RelayCustomStatusActivity extends UniversalFragment {
    private static final int ID_EMOJI = 1;
    private static final int ID_CLEAR_BASE = 10;
    private static final int ID_SET = 30;
    private static final int ID_REMOVE = 31;

    /** Mattermost durations and their labels. */
    static final String[] DURATIONS = {"", "thirty_minutes", "one_hour", "four_hours", "today", "this_week"};
    static final int[] DURATION_LABELS = {R.string.RelayClearNever, R.string.RelayClear30m, R.string.RelayClear1h, R.string.RelayClear4h, R.string.RelayClearToday, R.string.RelayClearWeek};

    /** Common status emoji (Mattermost names), as the web app's suggestions. */
    static final String[] EMOJI = {
            "calendar", "palm_tree", "house", "car", "airplane", "face_with_thermometer", "hamburger", "coffee",
            "computer", "headphones", "speech_balloon", "sleeping", "sunglasses", "tada", "rocket", "fire",
            "eyes", "hourglass_flowing_sand", "construction", "no_entry", "books", "baby", "beach_with_umbrella", "smile",
            "thinking_face", "muscle", "pray", "heart", "white_check_mark", "red_circle", "large_green_circle", "train",
    };

    private EditTextCell textEdit;
    private String emoji = "speech_balloon";
    private int duration = 0;

    @Override
    protected boolean useSimpleActionBar() {
        return true;
    }

    @Override
    public View createView(Context context) {
        setHasOwnBackground(true);
        CustomStatus current = RelayProfile.cachedCustomStatus(currentAccount);
        textEdit = new EditTextCell(context, getString(R.string.RelayCustomStatusHint), false, false, 100, resourceProvider);
        textEdit.hideKeyboardOnEnter();
        if (current != null) {
            if (current.emoji != null && !current.emoji.isEmpty()) {
                emoji = current.emoji;
            }
            textEdit.setText(current.text);
            for (int i = 0; i < DURATIONS.length; i++) {
                if (DURATIONS[i].equals(current.duration)) {
                    duration = i;
                }
            }
        }
        fragmentView = super.createView(context);
        listView.setSections();
        listView.setSectionsDrawBackground(true);
        return fragmentView;
    }

    @Override
    protected RecyclerListView getListViewForSimpleGlass() {
        return listView;
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.RelayCustomStatus);
    }

    private CharSequence emojiGlyph() {
        String unicode = EmojiTable.unicode(emoji);
        return Emoji.replaceEmoji(unicode != null ? unicode : ":" + emoji + ":", null, false);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(getString(R.string.RelayStatus)));
        items.add(UItem.asButton(ID_EMOJI, getString(R.string.RelayCustomStatusEmoji), emojiGlyph()));
        items.add(UItem.asCustom(textEdit));
        items.add(UItem.asShadow(getString(R.string.RelayCustomStatusInfo)));
        items.add(UItem.asHeader(getString(R.string.RelayCustomStatusClearAfter)));
        for (int i = 0; i < DURATIONS.length; i++) {
            items.add(UItem.asRadio(ID_CLEAR_BASE + i, getString(DURATION_LABELS[i])).setChecked(i == duration));
        }
        items.add(UItem.asShadow(null));
        items.add(UItem.asButton(ID_SET, getString(R.string.RelayCustomStatusSet)).accent());
        if (RelayProfile.cachedCustomStatus(currentAccount) != null) {
            items.add(UItem.asButton(ID_REMOVE, getString(R.string.RelayCustomStatusClear)).red());
        }
        items.add(UItem.asShadow(null));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_EMOJI) {
            showEmojiPicker();
        } else if (item.id >= ID_CLEAR_BASE && item.id < ID_CLEAR_BASE + DURATIONS.length) {
            duration = item.id - ID_CLEAR_BASE;
            listView.adapter.update(true);
        } else if (item.id == ID_SET) {
            String text = textEdit.getText() != null ? textEdit.getText().toString().trim() : "";
            RelayProfile.setCustomStatus(currentAccount, emoji, text, DURATIONS[duration], expiresAt(DURATIONS[duration]), (result, error) -> {
                if (result == null) {
                    BulletinFactory.of(this).createErrorBulletin(error != null ? error : "").show();
                } else {
                    finishFragment();
                }
            });
        } else if (item.id == ID_REMOVE) {
            RelayProfile.clearCustomStatus(currentAccount, (result, error) -> {
                if (result == null) {
                    BulletinFactory.of(this).createErrorBulletin(error != null ? error : "").show();
                } else {
                    finishFragment();
                }
            });
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    /** When a Mattermost duration ends (ms), 0 for "don't clear". */
    static long expiresAt(String duration) {
        long now = System.currentTimeMillis();
        Calendar c = Calendar.getInstance();
        switch (duration) {
            case "thirty_minutes":
                return now + 30 * 60_000L;
            case "one_hour":
                return now + 3600_000L;
            case "four_hours":
                return now + 4 * 3600_000L;
            case "today":
                c.set(Calendar.HOUR_OF_DAY, 23);
                c.set(Calendar.MINUTE, 59);
                c.set(Calendar.SECOND, 59);
                return c.getTimeInMillis();
            case "this_week":
                c.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY);
                c.set(Calendar.HOUR_OF_DAY, 23);
                c.set(Calendar.MINUTE, 59);
                c.set(Calendar.SECOND, 59);
                if (c.getTimeInMillis() < now) {
                    c.add(Calendar.WEEK_OF_YEAR, 1);
                }
                return c.getTimeInMillis();
            default:
                return 0;
        }
    }

    private void showEmojiPicker() {
        Context context = getContext();
        if (context == null) {
            return;
        }
        GridLayout grid = new GridLayout(context);
        grid.setColumnCount(6);
        grid.setPadding(dp(12), dp(8), dp(12), dp(8));
        AlertDialog[] dialog = new AlertDialog[1];
        for (String name : EMOJI) {
            String unicode = EmojiTable.unicode(name);
            if (unicode == null) {
                continue;
            }
            TextView cell = new TextView(context);
            cell.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 28);
            cell.setGravity(Gravity.CENTER);
            cell.setText(Emoji.replaceEmoji(unicode, cell.getPaint().getFontMetricsInt(), false));
            cell.setContentDescription(name);
            cell.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), Theme.RIPPLE_MASK_ROUNDRECT_6DP));
            cell.setOnClickListener(v -> {
                emoji = name;
                if (dialog[0] != null) {
                    dialog[0].dismiss();
                }
                listView.adapter.update(true);
            });
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = dp(48);
            lp.height = dp(48);
            grid.addView(cell, lp);
        }
        ScrollView scroll = new ScrollView(context);
        scroll.addView(grid);
        dialog[0] = new AlertDialog.Builder(context, resourceProvider)
                .setTitle(getString(R.string.RelayCustomStatusEmoji))
                .setView(scroll)
                .setNegativeButton(getString(R.string.Cancel), null)
                .create();
        showDialog(dialog[0]);
    }
}
