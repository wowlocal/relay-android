package org.telegram.relay;

import android.util.Log;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.NotificationCenter;
import org.telegram.relay.ui.RelayMainTabs;
import org.telegram.relay.ui.RelayPostListActivity;
import org.telegram.relay.ui.RelayThreadsActivity;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.MainTabsActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Tab bar debug helpers behind {@code org.relaychat.DEBUG_TABS --es op …} (debug builds only, see
 * {@code RelayTabsDebugHooks}). Results are logged with tag {@code RelayDebug} as {@code DEBUG_TABS <op> ok}.
 *
 * <ul>
 *   <li>{@code state}: {@code files/relay-tabs.txt}: {@code top <class>} (the navigation stack's top), {@code page <class>}
 *       (the visible main tab), {@code badge <threads badge>}, {@code threads_desc <the Threads tab's description>};
 *       for the Threads page {@code threads_team}, {@code threads_teams}, {@code threads_rows <root post:unread:mentions,…>},
 *       {@code empty <text>}; for the Mentions page {@code mentions_rows <post ids>}; {@code first <first visible row>}.</li>
 *   <li>{@code theme --es dark 1|0}: switches to Telegram's night ("Dark Blue") or day ("Blue") theme.</li>
 * </ul>
 */
public final class RelayDebugTabs {
    private static final String TAG = "RelayDebug";

    private RelayDebugTabs() {
    }

    public static void run(int account, String op, android.content.Intent intent) {
        try {
            switch (op == null ? "" : op) {
                case "state":
                    state(account);
                    break;
                case "theme": {
                    boolean dark = "1".equals(intent.getStringExtra("dark"));
                    Theme.ThemeInfo theme = Theme.getTheme(dark ? "Dark Blue" : "Blue");
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.needSetDayNightTheme, theme, false, null, -1);
                    Log.i(TAG, "DEBUG_TABS theme ok dark=" + dark);
                    break;
                }
                default:
                    Log.i(TAG, "DEBUG_TABS unknown op " + op);
            }
        } catch (Throwable t) {
            Log.e(TAG, "DEBUG_TABS " + op + " failed", t);
        }
    }

    private static void state(int account) throws Exception {
        StringBuilder out = new StringBuilder();
        BaseFragment top = LaunchActivity.getLastFragment();
        out.append("top\t").append(top != null ? top.getClass().getSimpleName() : "-").append('\n');
        if (top instanceof MainTabsActivity) {
            MainTabsActivity tabs = (MainTabsActivity) top;
            BaseFragment page = tabs.getCurrentVisibleFragment();
            out.append("page\t").append(page != null ? page.getClass().getSimpleName() : "-").append('\n');
            if (tabs.tabs != null && tabs.tabs.length > 1 && tabs.tabs[1] != null) {
                CharSequence desc = tabs.tabs[1].getContentDescription();
                out.append("threads_desc\t").append(desc != null ? desc : "-").append('\n');
            }
            if (page instanceof RelayThreadsActivity) {
                ((RelayThreadsActivity) page).debugState(out);
            } else if (page instanceof RelayPostListActivity) {
                ((RelayPostListActivity) page).debugState(out);
            }
        }
        String badge = RelayMainTabs.threadsBadge(account);
        out.append("badge\t").append(badge != null ? badge : "-").append('\n');
        File file = new File(ApplicationLoader.applicationContext.getFilesDir(), "relay-tabs.txt");
        try (FileOutputStream stream = new FileOutputStream(file)) {
            stream.write(out.toString().getBytes(StandardCharsets.UTF_8));
        }
        Log.i(TAG, "DEBUG_TABS state ok");
    }
}
