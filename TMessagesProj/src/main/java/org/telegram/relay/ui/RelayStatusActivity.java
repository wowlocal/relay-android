package org.telegram.relay.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.text.SpannableStringBuilder;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.relay.bridge.RelayProfile;
import org.telegram.relay.mattermost.model.CustomStatus;
import org.telegram.relay.mattermost.model.UserStatus;
import org.telegram.relay.mattermost.text.EmojiTable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalFragment;

import java.util.ArrayList;
import java.util.Calendar;

/**
 * Settings → Status (A7c, as iOS phase3f-status-*): Online / Away / Do Not Disturb / Offline, Do Not Disturb with
 * an end time, and the custom status. {@link #showQuickMenu} is the Telegram-styled popup on the Settings row.
 */
public class RelayStatusActivity extends UniversalFragment {
    private static final int ID_STATUS_BASE = 10;
    private static final int ID_DND_OFF = 20;
    private static final int ID_DND_30M = 21;
    private static final int ID_DND_1H = 22;
    private static final int ID_DND_2H = 23;
    private static final int ID_DND_TOMORROW = 24;
    private static final int ID_CUSTOM = 30;

    private String status;
    private long dndEnd;

    @Override
    protected boolean useSimpleActionBar() {
        return true;
    }

    @Override
    public View createView(Context context) {
        setHasOwnBackground(true);
        status = RelayProfile.cachedStatus(currentAccount);
        fragmentView = super.createView(context);
        listView.setSections();
        listView.setSectionsDrawBackground(true);
        RelayProfile.loadStatus(currentAccount, (result, error) -> {
            if (result != null) {
                status = result.status;
                dndEnd = result.dndEndTime;
                if (listView != null) {
                    listView.adapter.update(true);
                }
            }
        });
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
        return getString(R.string.RelayStatus);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (listView != null) {
            listView.adapter.update(false);
        }
    }

    public static String label(String status) {
        if (UserStatus.AWAY.equals(status)) {
            return getString(R.string.RelayStatusAway);
        } else if (UserStatus.DND.equals(status)) {
            return getString(R.string.RelayStatusDnd);
        } else if (UserStatus.OFFLINE.equals(status)) {
            return getString(R.string.RelayStatusOffline);
        }
        return getString(R.string.RelayStatusOnline);
    }

    static int color(String status) {
        if (UserStatus.AWAY.equals(status)) {
            return 0xFFFFBC1F;
        } else if (UserStatus.DND.equals(status)) {
            return 0xFFD24B4E;
        } else if (UserStatus.OFFLINE.equals(status)) {
            return 0xFF9E9E9E;
        }
        return 0xFF3DB887;
    }

    /** A filled circle in the status colour (the menu and row icons). */
    public static Drawable dot(String status) {
        return new Drawable() {
            final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            public void draw(Canvas canvas) {
                paint.setColor(color(status));
                float r = Math.min(getBounds().width(), getBounds().height()) * 0.3f;
                canvas.drawCircle(getBounds().exactCenterX(), getBounds().exactCenterY(), r, paint);
            }

            @Override
            public int getIntrinsicWidth() {
                return AndroidUtilities.dp(24);
            }

            @Override
            public int getIntrinsicHeight() {
                return AndroidUtilities.dp(24);
            }

            @Override
            public void setAlpha(int alpha) {
            }

            @Override
            public void setColorFilter(ColorFilter colorFilter) {
            }

            @Override
            public int getOpacity() {
                return PixelFormat.TRANSLUCENT;
            }
        };
    }

    /** The custom status as "emoji text" for rows, or null. */
    public static CharSequence customStatusText(int account) {
        CustomStatus cs = RelayProfile.cachedCustomStatus(account);
        if (cs == null) {
            return null;
        }
        String emoji = EmojiTable.unicode(cs.emoji);
        SpannableStringBuilder text = new SpannableStringBuilder();
        if (emoji != null) {
            text.append(emoji).append(' ');
        } else if (cs.emoji != null && !cs.emoji.isEmpty()) {
            text.append(':').append(cs.emoji).append(": ");
        }
        text.append(cs.text != null ? cs.text : "");
        return Emoji.replaceEmoji(text, null, false);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        String[] statuses = RelayProfile.STATUSES;
        for (int i = 0; i < statuses.length; i++) {
            items.add(UItem.asRadio(ID_STATUS_BASE + i, label(statuses[i])).setChecked(statuses[i].equals(status)));
        }
        items.add(UItem.asShadow(getString(R.string.RelayStatusInfo)));
        items.add(UItem.asHeader(getString(R.string.RelayStatusDnd)));
        items.add(UItem.asButton(ID_DND_OFF, getString(R.string.RelayDndUntilOff)).accent());
        items.add(UItem.asButton(ID_DND_30M, getString(R.string.RelayDnd30m)).accent());
        items.add(UItem.asButton(ID_DND_1H, getString(R.string.RelayDnd1h)).accent());
        items.add(UItem.asButton(ID_DND_2H, getString(R.string.RelayDnd2h)).accent());
        items.add(UItem.asButton(ID_DND_TOMORROW, getString(R.string.RelayDndTomorrow)).accent());
        String dndInfo = getString(R.string.RelayStatusDndInfo);
        if (UserStatus.DND.equals(status) && dndEnd > 0) {
            dndInfo = LocaleController.formatString(R.string.RelayStatusDndUntil, LocaleController.formatDateTime(dndEnd, true)) + "\n" + dndInfo;
        }
        items.add(UItem.asShadow(dndInfo));
        CharSequence custom = customStatusText(currentAccount);
        items.add(UItem.asButton(ID_CUSTOM, getString(R.string.RelayCustomStatus), custom != null ? custom : getString(R.string.RelayCustomStatusSet)));
        items.add(UItem.asShadow(null));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id >= ID_STATUS_BASE && item.id < ID_STATUS_BASE + RelayProfile.STATUSES.length) {
            apply(this, RelayProfile.STATUSES[item.id - ID_STATUS_BASE], 0, this::onApplied);
        } else if (item.id >= ID_DND_OFF && item.id <= ID_DND_TOMORROW) {
            apply(this, UserStatus.DND, dndEndFor(item.id), this::onApplied);
        } else if (item.id == ID_CUSTOM) {
            presentFragment(new RelayCustomStatusActivity());
        }
    }

    private void onApplied(UserStatus result) {
        status = result.status;
        dndEnd = result.dndEndTime;
        if (listView != null) {
            listView.adapter.update(true);
        }
    }

    @Override
    protected boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    static long dndEndFor(int id) {
        long now = System.currentTimeMillis() / 1000;
        switch (id) {
            case ID_DND_30M:
                return now + 30 * 60;
            case ID_DND_1H:
                return now + 3600;
            case ID_DND_2H:
                return now + 2 * 3600;
            case ID_DND_TOMORROW: {
                Calendar c = Calendar.getInstance();
                c.add(Calendar.DAY_OF_YEAR, 1);
                c.set(Calendar.HOUR_OF_DAY, 9);
                c.set(Calendar.MINUTE, 0);
                c.set(Calendar.SECOND, 0);
                return c.getTimeInMillis() / 1000;
            }
            default:
                return 0;
        }
    }

    interface Applied {
        void done(UserStatus status);
    }

    static void apply(BaseFragment fragment, String status, long dndEndSeconds, Applied applied) {
        RelayProfile.setStatus(fragment.getCurrentAccount(), status, dndEndSeconds, (result, error) -> {
            if (result == null) {
                BulletinFactory.of(fragment).createErrorBulletin(error != null ? error : "").show();
                return;
            }
            if (applied != null) {
                applied.done(result);
            }
        });
    }

    /**
     * The quick menu on Settings' Status row (iOS phase3f-status-quick-menu): the four statuses with a check, Do Not
     * Disturb opening its durations, then the custom status. {@code changed} runs after a change.
     */
    public static void showQuickMenu(BaseFragment fragment, View anchor, Runnable changed) {
        int account = fragment.getCurrentAccount();
        String current = RelayProfile.cachedStatus(account);
        ItemOptions options = ItemOptions.makeOptions(fragment, anchor, true);
        for (String s : RelayProfile.STATUSES) {
            if (UserStatus.DND.equals(s)) {
                options.addChecked(UserStatus.DND.equals(current), dot(s), label(s), () -> showDndMenu(fragment, anchor, changed));
            } else {
                options.addChecked(s.equals(current), dot(s), label(s), () -> apply(fragment, s, 0, r -> {
                    if (changed != null) {
                        changed.run();
                    }
                }));
            }
        }
        options.addGap();
        CharSequence custom = customStatusText(account);
        options.add(R.drawable.msg_smile_status, custom != null ? custom : getString(R.string.RelayCustomStatusSet), () -> fragment.presentFragment(new RelayCustomStatusActivity()));
        options.add(R.drawable.msg_settings, getString(R.string.RelayStatus), () -> fragment.presentFragment(new RelayStatusActivity()));
        options.setDrawScrim(false).show();
    }

    static void showDndMenu(BaseFragment fragment, View anchor, Runnable changed) {
        ItemOptions options = ItemOptions.makeOptions(fragment, anchor, true);
        int[][] rows = {
                {ID_DND_OFF, R.string.RelayDndUntilOff},
                {ID_DND_30M, R.string.RelayDnd30m},
                {ID_DND_1H, R.string.RelayDnd1h},
                {ID_DND_2H, R.string.RelayDnd2h},
                {ID_DND_TOMORROW, R.string.RelayDndTomorrow},
        };
        for (int[] row : rows) {
            options.add(getString(row[1]), () -> apply(fragment, UserStatus.DND, dndEndFor(row[0]), r -> {
                if (changed != null) {
                    changed.run();
                }
            }));
        }
        options.setDrawScrim(false).show();
    }
}
